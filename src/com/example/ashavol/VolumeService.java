package com.example.ashavol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Foreground service that keeps its own GATT connection to the bonded ASHA
 * hearing devices and overrides the volume Android writes to them.
 *
 * Android maps the music volume index 1..15 to -42..0 dB, which is far too
 * loud for these processors. After every event that makes Android write its
 * own value (volume change, playback start, reconnect) this service writes a
 * value from its own table instead, a few times with growing delays so that
 * it lands after Android's write.
 *
 * Intent extras for tuning (via the activity):
 *   min  dB written at index 1 (default -127)
 *   max  dB written at the top index (default -95)
 */
public class VolumeService extends Service {
    static final String TAG = "AshaVol";
    static final String PREFS = "ashavol";
    private static final String CHANNEL = "service";
    private static final UUID ASHA_SERVICE =
            UUID.fromString("0000fdf0-0000-1000-8000-00805f9b34fb");
    private static final UUID VOLUME_CHAR =
            UUID.fromString("00e4ca9e-ab14-41e4-8823-f9e70c7e91df");
    // The device notifies this after every Start command, which carries Android's volume.
    private static final UUID AUDIO_STATUS_CHAR =
            UUID.fromString("38663f1a-e711-4cac-b641-326b56404837");
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final long[] RETRY_DELAYS_MS = {15, 30, 50, 80, 120, 300, 1000, 2500};
    // VOLUME_CHANGED_ACTION arrives ~0.5 s after Android's own write, so the index is
    // also polled. The handler clock stops in deep sleep, so this costs nothing then.
    private static final long POLL_MS = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // Accessed only on the main thread.
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();
    private final Map<String, BluetoothGattCharacteristic> volumeChars = new HashMap<>();
    private AudioManager audio;
    private int minDb;
    private int maxDb;
    private boolean wasPlaying;
    private int lastIndex = -1;

    /**
     * Prefs live in device-protected storage so that the service can read the
     * table before the first unlock; ASHA already streams on the lock screen.
     */
    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Moves prefs written by older versions to device-protected storage. Needs an unlocked user. */
    static void migratePrefs(Context context) {
        if (!context.createDeviceProtectedStorageContext()
                .moveSharedPreferencesFrom(context, PREFS)) {
            Log.w(TAG, "moving prefs to device-protected storage failed");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ASHA volume",
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ASHA volume override active")
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        registerReceiver(volumeReceiver, new IntentFilter(VOLUME_CHANGED),
                Context.RECEIVER_EXPORTED);
        audio.registerAudioPlaybackCallback(playbackCallback, handler);
        audio.registerAudioDeviceCallback(deviceCallback, handler);
        connectAll();
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = prefs(this);
        if (intent != null && (intent.hasExtra("min") || intent.hasExtra("max"))) {
            p.edit()
                    .putInt("min", clampDb(intent.getIntExtra("min", p.getInt("min", -127))))
                    .putInt("max", clampDb(intent.getIntExtra("max", p.getInt("max", -95))))
                    .apply();
        }
        minDb = p.getInt("min", -127);
        maxDb = p.getInt("max", -95);
        Log.i(TAG, "table min=" + minDb + " max=" + maxDb);
        scheduleWrites("start");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(volumeReceiver);
        audio.unregisterAudioPlaybackCallback(playbackCallback);
        audio.unregisterAudioDeviceCallback(deviceCallback);
        for (BluetoothGatt g : gatts.values()) {
            g.close();
        }
        gatts.clear();
        volumeChars.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static int clampDb(int db) {
        return Math.max(-128, Math.min(0, db));
    }

    /** Maps the current music index to dB; null means leave Android's value alone. */
    private Integer targetDb() {
        int index = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int top = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (index <= 0) {
            return null;
        }
        if (top <= 1) {
            return minDb;
        }
        return minDb + Math.round((float) (maxDb - minDb) * (index - 1) / (top - 1));
    }

    private void scheduleWrites(String reason) {
        handler.removeCallbacks(writeRunnable);
        Log.i(TAG, "schedule writes: " + reason);
        for (long d : RETRY_DELAYS_MS) {
            handler.postDelayed(writeRunnable, d);
        }
    }

    private final Runnable writeRunnable = this::writeAll;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            int index = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (index != lastIndex) {
                lastIndex = index;
                writeAll();
                scheduleWrites("polled index " + index);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void writeAll() {
        Integer db = targetDb();
        if (db == null) {
            return;
        }
        byte[] value = new byte[] {(byte) (int) db};
        for (Map.Entry<String, BluetoothGattCharacteristic> e : volumeChars.entrySet()) {
            BluetoothGatt g = gatts.get(e.getKey());
            if (g == null) {
                continue;
            }
            int rc = g.writeCharacteristic(e.getValue(), value,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            Log.i(TAG, "write " + db + " to " + e.getKey() + " rc=" + rc);
        }
    }

    private void connectAll() {
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String name = d.getName();
            if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE && name != null
                    && name.contains("Hearing")) {
                Log.i(TAG, "connecting " + d.getAddress() + " " + name);
                // autoConnect lets the stack reconnect us whenever the device comes back.
                gatts.put(d.getAddress(),
                        d.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE));
            }
        }
    }

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (stream == AudioManager.STREAM_MUSIC) {
                scheduleWrites("volume index "
                        + intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1));
            }
        }
    };

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            // Only active players are reported to third-party apps.
            boolean playing = !configs.isEmpty();
            if (playing && !wasPlaying) {
                scheduleWrites("playback started");
            }
            wasPlaying = playing;
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            for (AudioDeviceInfo d : added) {
                if (d.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                    scheduleWrites("hearing aid output added");
                    return;
                }
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int st, int newState) {
            String addr = gatt.getDevice().getAddress();
            Log.i(TAG, addr + " state=" + newState + " status=" + st);
            handler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices();
                } else {
                    volumeChars.remove(addr);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int st) {
            String addr = gatt.getDevice().getAddress();
            BluetoothGattService s = gatt.getService(ASHA_SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(VOLUME_CHAR);
            if (c == null) {
                Log.w(TAG, addr + ": ASHA volume characteristic not found");
                return;
            }
            BluetoothGattCharacteristic status = s.getCharacteristic(AUDIO_STATUS_CHAR);
            // The stack's own ASHA client already enabled the CCCD; registering here
            // only makes the notifications reach this app too.
            boolean subscribed = status != null && gatt.setCharacteristicNotification(status, true);
            Log.i(TAG, addr + " audio status notifications " + subscribed);
            handler.post(() -> {
                volumeChars.put(addr, c);
                scheduleWrites(addr + " ready");
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic c, byte[] value) {
            if (AUDIO_STATUS_CHAR.equals(c.getUuid())) {
                String addr = gatt.getDevice().getAddress();
                int st = value.length > 0 ? value[0] : -1;
                handler.post(() -> scheduleWrites(addr + " audio status " + st));
            }
        }
    };
}

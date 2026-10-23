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
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   min    dB written at index 1 (default -127)
 *   max    dB written at the top index (default -95)
 *   left   dB added for the left device (default 0)
 *   right  dB added for the right device (default 0)
 * Every written value is clamped to MIN_DB..MAX_DB.
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
    // Byte 1 is DeviceCapabilities; bit 0 set means the right side.
    private static final UUID PROPERTIES_CHAR =
            UUID.fromString("6333651e-c481-4a3e-9169-7c902aad37bb");
    // -128 is mute, which belongs to index 0 only.
    static final int MIN_DB = -127;
    // Safety ceiling; Android's own -42 dB at index 1 is already far too loud here.
    static final int MAX_DB = -40;
    static final int MAX_OFFSET_DB = 30;
    static final String[] TUNING_KEYS = {"min", "max", "left", "right"};
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final long[] RETRY_DELAYS_MS = {15, 30, 50, 80, 120, 300, 1000, 2500};
    // VOLUME_CHANGED_ACTION arrives ~0.5 s after Android's own write, so the index is
    // also polled, but only while something plays to a hearing aid output: a blip is
    // audible only then. Polling all the time kept the CPU busy with the screen off
    // and drained the battery (37 % overnight).
    private static final long POLL_MS = 20;
    // The stack sometimes drops our autoConnect request when the processors restart,
    // so a client that stays disconnected is recreated.
    private static final long CHECK_MS = 60_000;
    private static final long CHECK_AFTER_EVENT_MS = 5_000;
    private static final long RECONNECT_GRACE_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // Accessed only on the main thread.
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();
    private final Map<String, BluetoothGattCharacteristic> volumeChars = new HashMap<>();
    // true = right; missing until ReadOnlyProperties has been read.
    private final Map<String, Boolean> rightSide = new HashMap<>();
    private final Set<String> connected = new HashSet<>();
    // elapsedRealtime of the last connectGatt per address.
    private final Map<String, Long> connectStartedAt = new HashMap<>();
    private AudioManager audio;
    private int minDb;
    private int maxDb;
    private int leftOffsetDb;
    private int rightOffsetDb;
    private boolean wasPlaying;
    private boolean hearingAidOutput;
    private boolean polling;
    private int lastStream = -1;
    private int lastIndex = -1;
    private boolean inCall;

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
        registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                Context.RECEIVER_EXPORTED);
        registerReceiver(aclReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
                Context.RECEIVER_EXPORTED);
        audio.registerAudioPlaybackCallback(playbackCallback, handler);
        audio.registerAudioDeviceCallback(deviceCallback, handler);
        audio.addOnModeChangedListener(handler::post, modeListener);
        connectAll();
        inCall = isCallMode(audio.getMode());
        wasPlaying = !audio.getActivePlaybackConfigurations().isEmpty();
        hearingAidOutput = hasHearingAidOutput();
        updatePolling();
        handler.postDelayed(checkRunnable, CHECK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // MainActivity writes the tuning to prefs before starting the service.
        SharedPreferences p = prefs(this);
        minDb = tuning(p, "min");
        maxDb = tuning(p, "max");
        leftOffsetDb = tuning(p, "left");
        rightOffsetDb = tuning(p, "right");
        Log.i(TAG, "table min=" + minDb + " max=" + maxDb
                + " left=" + leftOffsetDb + " right=" + rightOffsetDb);
        scheduleWrites("start");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(volumeReceiver);
        unregisterReceiver(bondReceiver);
        unregisterReceiver(aclReceiver);
        audio.unregisterAudioPlaybackCallback(playbackCallback);
        audio.unregisterAudioDeviceCallback(deviceCallback);
        audio.removeOnModeChangedListener(modeListener);
        for (BluetoothGatt g : gatts.values()) {
            g.close();
        }
        gatts.clear();
        volumeChars.clear();
        rightSide.clear();
        connected.clear();
        connectStartedAt.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static int clampDb(int db) {
        return Math.max(MIN_DB, Math.min(MAX_DB, db));
    }

    static int clampOffset(int db) {
        return Math.max(-MAX_OFFSET_DB, Math.min(MAX_OFFSET_DB, db));
    }

    /** One of {@link #TUNING_KEYS} from prefs, with its default and clamped. */
    static int tuning(SharedPreferences p, String key) {
        switch (key) {
            case "min":
                return clampDb(p.getInt(key, -127));
            case "max":
                return clampDb(p.getInt(key, -95));
            default:
                return clampOffset(p.getInt(key, 0));
        }
    }

    /** Offset for one device; an unknown side gets the quieter of the two. */
    private int offsetDb(String addr) {
        Boolean right = rightSide.get(addr);
        if (right == null) {
            return Math.min(leftOffsetDb, rightOffsetDb);
        }
        return right ? rightOffsetDb : leftOffsetDb;
    }

    /**
     * The stream whose index Android sends to the hearing devices, mirroring
     * AudioService.getBluetoothContextualVolumeStream: the voice call stream during
     * a call or VoIP session, or while voice communication plays; music otherwise.
     */
    private int volumeStream() {
        switch (audio.getMode()) {
            case AudioManager.MODE_IN_CALL:
            case AudioManager.MODE_IN_COMMUNICATION:
                return AudioManager.STREAM_VOICE_CALL;
            case AudioManager.MODE_NORMAL:
            case AudioManager.MODE_RINGTONE:
                for (AudioPlaybackConfiguration c : audio.getActivePlaybackConfigurations()) {
                    if (c.getAudioAttributes().getUsage()
                            == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                        return AudioManager.STREAM_VOICE_CALL;
                    }
                }
                return AudioManager.STREAM_MUSIC;
            default:
                return AudioManager.STREAM_MUSIC;
        }
    }

    private static String streamName(int stream) {
        return stream == AudioManager.STREAM_VOICE_CALL ? "call" : "music";
    }

    /**
     * Maps the index of the stream Android follows to dB; null means leave Android's
     * value alone. The lowest index of the stream (1 for calls) maps to min.
     */
    private Integer targetDb() {
        int stream = volumeStream();
        int index = audio.getStreamVolume(stream);
        int bottom = Math.max(1, audio.getStreamMinVolume(stream));
        int top = audio.getStreamMaxVolume(stream);
        if (index <= 0) {
            return null;
        }
        if (top <= bottom) {
            return minDb;
        }
        return minDb + Math.round((float) (maxDb - minDb)
                * (Math.min(Math.max(index, bottom), top) - bottom) / (top - bottom));
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
            int stream = volumeStream();
            int index = audio.getStreamVolume(stream);
            if (stream != lastStream || index != lastIndex) {
                lastStream = stream;
                lastIndex = index;
                writeAll();
                scheduleWrites("polled " + streamName(stream) + " index " + index);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private boolean hasHearingAidOutput() {
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                return true;
            }
        }
        return false;
    }

    /**
     * Polls only while something plays, or a call runs, with a hearing aid output.
     * Call audio need not show up as a player. A change made while polling is off is
     * still caught by VOLUME_CHANGED_ACTION, and the next playback start triggers
     * writes of its own.
     */
    private void updatePolling() {
        boolean want = (wasPlaying || inCall) && hearingAidOutput;
        if (want == polling) {
            return;
        }
        polling = want;
        Log.i(TAG, "polling " + (want ? "on" : "off"));
        if (want) {
            handler.post(pollRunnable);
        } else {
            handler.removeCallbacks(pollRunnable);
        }
    }

    private void writeAll() {
        Integer db = targetDb();
        if (db == null) {
            return;
        }
        for (Map.Entry<String, BluetoothGattCharacteristic> e : volumeChars.entrySet()) {
            BluetoothGatt g = gatts.get(e.getKey());
            if (g == null) {
                continue;
            }
            int v = clampDb(db + offsetDb(e.getKey()));
            int rc = g.writeCharacteristic(e.getValue(), new byte[] {(byte) v},
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            Log.i(TAG, "write " + v + " to " + e.getKey() + " rc=" + rc);
        }
    }

    /** Connects every bonded hearing device that has no GATT client yet. */
    private void connectAll() {
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            connect(d);
        }
    }

    private void connect(BluetoothDevice d) {
        String name = d.getName();
        if (d.getType() != BluetoothDevice.DEVICE_TYPE_LE || name == null
                || !name.contains("Hearing") || gatts.containsKey(d.getAddress())) {
            return;
        }
        Log.i(TAG, "connecting " + d.getAddress() + " " + name);
        // autoConnect lets the stack reconnect us whenever the device comes back.
        gatts.put(d.getAddress(),
                d.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE));
        connectStartedAt.put(d.getAddress(), SystemClock.elapsedRealtime());
    }

    private void forget(String addr) {
        BluetoothGatt g = gatts.remove(addr);
        if (g == null) {
            return;
        }
        Log.i(TAG, "forgetting " + addr);
        g.close();
        volumeChars.remove(addr);
        rightSide.remove(addr);
        connected.remove(addr);
        connectStartedAt.remove(addr);
    }

    /**
     * Recreates every client that is not connected and has had time to connect.
     * Seen in practice: after the processors restarted, our client stayed registered
     * but the stack had no pending autoConnect for it any more, while the link itself
     * was up. Recreating a client of a device that is out of range is harmless.
     */
    private void checkConnections() {
        long now = SystemClock.elapsedRealtime();
        for (String addr : new ArrayList<>(gatts.keySet())) {
            Long started = connectStartedAt.get(addr);
            if (connected.contains(addr)
                    || (started != null && now - started < RECONNECT_GRACE_MS)) {
                continue;
            }
            BluetoothDevice d = gatts.get(addr).getDevice();
            Log.i(TAG, addr + " not connected, recreating client");
            forget(addr);
            connect(d);
        }
        connectAll();
    }

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkConnections();
            handler.postDelayed(this, CHECK_MS);
        }
    };

    private void scheduleCheck() {
        handler.removeCallbacks(checkRunnable);
        handler.postDelayed(checkRunnable, CHECK_AFTER_EVENT_MS);
    }

    // A hearing device link coming up is the moment our client should connect too.
    private final BroadcastReceiver aclReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice.class);
            if (d != null && gatts.containsKey(d.getAddress())) {
                Log.i(TAG, d.getAddress() + " link up");
                scheduleCheck();
            }
        }
    };

    // Re-pairing the processors (e.g. after a Nucleus Smart update) removes the old
    // bonds and creates new ones while the service keeps running.
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice.class);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            if (d == null) {
                return;
            }
            if (state == BluetoothDevice.BOND_BONDED) {
                connect(d);
            } else if (state == BluetoothDevice.BOND_NONE) {
                forget(d.getAddress());
            }
        }
    };

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (stream == AudioManager.STREAM_MUSIC || stream == AudioManager.STREAM_VOICE_CALL) {
                scheduleWrites(streamName(stream) + " index "
                        + intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1));
            }
        }
    };

    private static boolean isCallMode(int mode) {
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    // Entering or leaving a call switches Android to the other stream's volume.
    private final AudioManager.OnModeChangedListener modeListener = mode -> {
        inCall = isCallMode(mode);
        scheduleWrites("audio mode " + mode);
        updatePolling();
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
            updatePolling();
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            hearingAidOutput = hasHearingAidOutput();
            updatePolling();
            for (AudioDeviceInfo d : added) {
                if (d.getType() == AudioDeviceInfo.TYPE_HEARING_AID) {
                    // Catches a newly bonded device whose name was not known at bond time.
                    connectAll();
                    scheduleCheck();
                    scheduleWrites("hearing aid output added");
                    return;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            hearingAidOutput = hasHearingAidOutput();
            updatePolling();
        }
    };

    private void markReady(BluetoothGatt gatt, BluetoothGattCharacteristic volume) {
        String addr = gatt.getDevice().getAddress();
        if (gatts.get(addr) != gatt) {
            return;
        }
        volumeChars.put(addr, volume);
        scheduleWrites(addr + " ready");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int st, int newState) {
            String addr = gatt.getDevice().getAddress();
            Log.i(TAG, addr + " state=" + newState + " status=" + st);
            handler.post(() -> {
                // A client closed by checkConnections may still have a callback queued.
                if (gatts.get(addr) != gatt) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.add(addr);
                    gatt.discoverServices();
                } else {
                    connected.remove(addr);
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
            // Writes wait for the side so that each device gets its own offset;
            // a pending read would also make the writes fail as busy.
            BluetoothGattCharacteristic props = s.getCharacteristic(PROPERTIES_CHAR);
            if (props == null || !gatt.readCharacteristic(props)) {
                Log.w(TAG, addr + ": cannot read ASHA properties, side unknown");
                handler.post(() -> markReady(gatt, c));
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic c, byte[] value, int st) {
            if (!PROPERTIES_CHAR.equals(c.getUuid())) {
                return;
            }
            String addr = gatt.getDevice().getAddress();
            BluetoothGattCharacteristic vol = c.getService().getCharacteristic(VOLUME_CHAR);
            Boolean right = st == BluetoothGatt.GATT_SUCCESS && value.length > 1
                    ? (value[1] & 1) != 0 : null;
            Log.i(TAG, addr + " side " + (right == null ? "unknown, status " + st
                    : right ? "right" : "left"));
            handler.post(() -> {
                if (gatts.get(addr) != gatt) {
                    return;
                }
                if (right != null) {
                    rightSide.put(addr, right);
                }
                markReady(gatt, vol);
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

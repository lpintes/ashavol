package com.example.ashavol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.TextView;

/**
 * Starts or stops {@link VolumeService} and shows its volume table.
 *
 * Usage:
 *   adb shell am start -n com.example.ashavol/.MainActivity --ei min -127 --ei max -95
 *   adb shell am start -n com.example.ashavol/.MainActivity --ez stop true
 */
public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setTextSize(22);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        setContentView(status);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        handle(getIntent());
    }

    private void handle(Intent intent) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Bluetooth permission needed");
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        Intent svc = new Intent(this, VolumeService.class);
        SharedPreferences p = getSharedPreferences(VolumeService.PREFS, MODE_PRIVATE);
        if (intent.getBooleanExtra("stop", false)) {
            p.edit().putBoolean("enabled", false).apply();
            stopService(svc);
            status.setText("ASHA volume override stopped");
            return;
        }
        if (intent.hasExtra("min")) {
            svc.putExtra("min", intent.getIntExtra("min", -127));
        }
        if (intent.hasExtra("max")) {
            svc.putExtra("max", intent.getIntExtra("max", -95));
        }
        // BootReceiver restarts the service only if it was left running.
        p.edit().putBoolean("enabled", true).apply();
        startForegroundService(svc);
        status.setText("ASHA volume override running, table "
                + svc.getIntExtra("min", p.getInt("min", -127)) + " to "
                + svc.getIntExtra("max", p.getInt("max", -95)) + " dB");
        // Motorola's freezer stops the service while the screen is off unless the app
        // is exempt from battery optimization; Android's volume then goes through.
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(
                getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }
}

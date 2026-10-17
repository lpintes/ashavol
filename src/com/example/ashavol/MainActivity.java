package com.example.ashavol;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Starts or stops {@link VolumeService} and lets the user tune its volume table
 * in 1 dB steps. Every change is stored and applied immediately.
 *
 * Usage:
 *   adb shell am start -n com.example.ashavol/.MainActivity --ei min -127 --ei max -95 --ei left 0 --ei right 0
 *   adb shell am start -n com.example.ashavol/.MainActivity --ez stop true
 */
public class MainActivity extends Activity {
    // Not a public constant; the page with connect/disconnect for each hearing device.
    private static final String HEARING_DEVICES_SETTINGS =
            "android.settings.HEARING_DEVICES_SETTINGS";
    // Same order as VolumeService.TUNING_KEYS.
    private static final String[] LABELS = {"Minimum", "Maximum", "Left", "Right"};

    private TextView status;
    private final TextView[] values = new TextView[LABELS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setTextSize(22);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);

        Button devices = new Button(this);
        devices.setText("Hearing devices");
        devices.setOnClickListener(v -> openHearingDevices());
        root.addView(devices);

        for (int i = 0; i < LABELS.length; i++) {
            root.addView(tuningRow(i));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
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

    /** Value, then "Quieter" and "Louder"; the value is announced when it changes. */
    private LinearLayout tuningRow(int i) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView value = new TextView(this);
        value.setTextSize(20);
        value.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(value, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        values[i] = value;
        row.addView(stepButton(i, "Quieter", -1));
        row.addView(stepButton(i, "Louder", 1));
        return row;
    }

    private Button stepButton(int i, String text, int step) {
        Button b = new Button(this);
        b.setText(text);
        b.setContentDescription(LABELS[i] + " " + text.toLowerCase());
        b.setOnClickListener(v -> change(i, step));
        return b;
    }

    private void change(int i, int step) {
        SharedPreferences p = VolumeService.prefs(this);
        String key = VolumeService.TUNING_KEYS[i];
        int v = VolumeService.tuning(p, key) + step;
        // Keep min <= max; the service clamps every written value as well.
        switch (key) {
            case "min":
                v = Math.max(VolumeService.MIN_DB, Math.min(VolumeService.tuning(p, "max"), v));
                break;
            case "max":
                v = Math.max(VolumeService.tuning(p, "min"), Math.min(VolumeService.MAX_DB, v));
                break;
            default:
                v = VolumeService.clampOffset(v);
        }
        p.edit().putInt(key, v).apply();
        if (p.getBoolean("enabled", false)) {
            // Rereads the prefs and writes the new volume right away.
            startForegroundService(new Intent(this, VolumeService.class));
        }
        showValues();
    }

    private void showValues() {
        SharedPreferences p = VolumeService.prefs(this);
        int min = VolumeService.tuning(p, "min");
        int max = VolumeService.tuning(p, "max");
        for (int i = 0; i < LABELS.length; i++) {
            String key = VolumeService.TUNING_KEYS[i];
            int v = VolumeService.tuning(p, key);
            boolean limit;
            String text;
            switch (key) {
                case "min":
                    limit = v == VolumeService.MIN_DB || v == max;
                    text = v + " dB";
                    break;
                case "max":
                    limit = v == VolumeService.MAX_DB || v == min;
                    text = v + " dB";
                    break;
                default:
                    limit = Math.abs(v) == VolumeService.MAX_OFFSET_DB;
                    text = String.format("%+d dB", v);
            }
            values[i].setText(LABELS[i] + ": " + text + (limit ? ", limit" : ""));
        }
    }

    private void openHearingDevices() {
        // Without CLEAR_TASK an open Settings task only comes to front on its old page.
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        try {
            startActivity(new Intent(HEARING_DEVICES_SETTINGS).addFlags(flags));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(flags));
        }
    }

    private void handle(Intent intent) {
        VolumeService.migratePrefs(this);
        SharedPreferences p = VolumeService.prefs(this);
        showValues();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Bluetooth permission needed");
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        Intent svc = new Intent(this, VolumeService.class);
        if (intent.getBooleanExtra("stop", false)) {
            p.edit().putBoolean("enabled", false).apply();
            stopService(svc);
            status.setText("ASHA volume override stopped");
            return;
        }
        SharedPreferences.Editor e = p.edit();
        for (String key : VolumeService.TUNING_KEYS) {
            if (intent.hasExtra(key)) {
                e.putInt(key, intent.getIntExtra(key, 0));
            }
        }
        // BootReceiver restarts the service only if it was left running.
        e.putBoolean("enabled", true).apply();
        startForegroundService(svc);
        status.setText("ASHA volume override running");
        showValues();
        // Motorola's freezer stops the service while the screen is off unless the app
        // is exempt from battery optimization; Android's volume then goes through.
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(
                getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }
}

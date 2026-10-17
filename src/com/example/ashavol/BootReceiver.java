package com.example.ashavol;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Starts {@link VolumeService} after boot and after the app is updated, but only
 * if the user left it running (pref "enabled", set by {@link MainActivity}).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        boolean enabled = context.getSharedPreferences(VolumeService.PREFS,
                Context.MODE_PRIVATE).getBoolean("enabled", false);
        if (!enabled) {
            Log.i(VolumeService.TAG, action + ": service not enabled");
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(VolumeService.TAG, action + ": no Bluetooth permission");
            return;
        }
        Log.i(VolumeService.TAG, action + ": starting service");
        context.startForegroundService(new Intent(context, VolumeService.class));
    }
}

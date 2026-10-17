package com.example.ashavol;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.util.Log;

/**
 * Starts {@link VolumeService} after boot and after the app is updated, but only
 * if the user left it running (pref "enabled", set by {@link MainActivity}).
 * LOCKED_BOOT_COMPLETED starts it before the first unlock, because ASHA already
 * streams on the lock screen; BOOT_COMPLETED follows after unlock and is harmless.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (context.getSystemService(UserManager.class).isUserUnlocked()) {
            VolumeService.migratePrefs(context);
        }
        if (!VolumeService.prefs(context).getBoolean("enabled", false)) {
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

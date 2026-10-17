# TODO

- Start the service on boot (`BOOT_COMPLETED` receiver). Check whether a
  `connectedDevice` foreground service may be started from boot on Android 15.
- Tune the volume table from the phone: a simple accessible UI for `min` and
  `max` (or a per-index table), usable with Corvus.
- Consider a non-linear table if 4.5 dB linear steps feel uneven.
- Replace the `com.example.ashavol` package name (requires uninstall, the
  stored table is lost).
- Check battery usage of the 20 ms polling in Android battery stats.
- Verify behaviour while the processors listen to the TV Streamer: our writes
  must not change the TV Streamer volume.
- Handle Bluetooth being turned off and on (GATT objects may need recreating),
  and newly bonded processors without restarting the service.
- Optional: notification action to stop the override.

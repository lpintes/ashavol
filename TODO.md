# TODO

- Run before the first unlock after boot. Today the service starts only at
  `BOOT_COMPLETED` (after the PIN), so the processors get Android's loud
  volume until then; the user turns Bluetooth off before rebooting. Option:
  `directBootAware`, `LOCKED_BOOT_COMPLETED` and prefs in device-protected
  storage. Check whether Bluetooth and the hearing aid profile already run
  before unlock.
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
- Watch whether Motorola still kills the service ("Foreground kill") now that
  it has the battery exemption. If it does, add a safety net, e.g. a Tasker
  task that starts `MainActivity` (or the service) every few minutes.

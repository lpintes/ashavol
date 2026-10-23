# TODO

- Consider a non-linear table if 4.5 dB linear steps feel uneven.
- Replace the `com.example.ashavol` package name (requires uninstall, the
  stored table is lost).
- The `TYPE_HEARING_AID` output disappears ~10 s after playback ends, and the
  music index then switches to the speaker's value. A poll can see that index
  before the removal callback and write a burst for it (seen: speaker index 2
  vs hearing aid index 1). Nothing plays at that moment and the next Start
  rewrites the volume, but it is a louder value than intended; consider
  ignoring index changes when no hearing aid output is present.
- Sometimes only one processor connects for streaming after a service restart
  (e.g. `adb install -r`) or after switching from the TV Streamer back to the
  phone; the user has to disconnect and reconnect them, sometimes several
  times. Not yet known whether our GATT client causes it. Next time, capture
  `adb logcat -b all` around the event and compare `hearing_aid.cc`
  OnGattConnected / ConnectSocket for both sides with our `state=` lines.
- Watchdog log noise: while the processors are off, it recreates both clients
  every minute and logs 3 lines per side (116 recreations in one hour off).
  Log only the first recreation per disconnect, or back off the interval.
- Handle Bluetooth being turned off and on (GATT objects may need recreating),
  and newly bonded processors without restarting the service.
- Optional: notification action to stop the override.
- Watch whether Motorola still kills the service ("Foreground kill") now that
  it has the battery exemption. If it does, add a safety net, e.g. a Tasker
  task that starts `MainActivity` (or the service) every few minutes.

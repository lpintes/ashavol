# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About the project

A no-root workaround for ASHA hearing aid streaming that is far too loud. The
user is blind, wears two Cochlear Nucleus sound processors and uses the phone
with the Corvus screen reader (`sk.stopka.corvus`), which likes loud speech.
The service runs on the user's everyday phone; every change is judged by
listening, and a bug can mean painfully loud audio straight into the implants.
Treat anything that may write a louder value than intended as a serious defect.

`README.md` explains the problem and the mechanism for outsiders. Open ideas
are in `TODO.md`.

## Hardware and environment

- Phone: Motorola edge 40 neo (manaus), Android 15 (SDK 35), MediaTek.
  Reached through adb over Wi-Fi (TLS pairing). `adb devices` may list the phone
  twice (two mDNS TLS entries); then pick one with `adb -s <id>`.
- Hearing devices: two Cochlear processors, bonded LE devices named
  "<owner> Hearing Devi".
  Companion app `com.cochlear.clientremote` (Nucleus Smart).
- The user often switches the processors to a Cochlear TV Streamer (computer
  audio). The phone then drops the ASHA profile, but the LE link, and this
  service's GATT connection, may stay up.

## Architecture

Three classes, no Gradle:

- `VolumeService` — foreground service (type `connectedDevice`). Owns a GATT
  connection (`autoConnect=true`) to each bonded LE device whose name contains
  "Hearing" (at start, on `ACTION_BOND_STATE_CHANGED` to BONDED, and when a
  `TYPE_HEARING_AID` output appears; BOND_NONE closes the client). A watchdog
  (every minute and 5 s after `ACTION_ACL_CONNECTED` or a hearing aid output)
  recreates a client that has stayed disconnected: once, after the processors
  restarted, the stack kept our client registered with no pending autoConnect
  while the link was up. It writes a signed byte to the ASHA Volume characteristic with
  `WRITE_TYPE_NO_RESPONSE`. Triggers, each followed by a burst of retries
  (`RETRY_DELAYS_MS`):
  - polling `getStreamVolume(STREAM_MUSIC)` every `POLL_MS` (immediate write on change),
    only while some playback is active and a `TYPE_HEARING_AID` output exists
    (`updatePolling`). Polling all the time cost 37 % battery overnight: the
    CPU is awake much of the time with the screen off.
  - `VOLUME_CHANGED_ACTION` for `STREAM_MUSIC` (backup, arrives ~0.5 s late),
  - AudioStatusPoint notification (each ASHA Start command),
  - `AudioDeviceCallback` adding `TYPE_HEARING_AID`, services discovered,
    playback start, service start.
  Index 0 is left to Android. Index 1..max maps linearly to `min..max` dB,
  plus a per-side offset `left`/`right` (side from bit 0 of byte 1 of the ASHA
  ReadOnlyProperties characteristic, read before the device counts as ready;
  an unknown side gets the quieter offset). Every written value is clamped to
  `MIN_DB..MAX_DB` (-127..-40), so no setting can exceed the ceiling. Values are
  stored in SharedPreferences `ashavol` in device-protected storage
  (`VolumeService.prefs`), readable before the first unlock. Older versions
  kept them in normal storage; `migratePrefs` moves them (verified). All state
  is touched on the main thread; GATT callbacks post to the handler.
- `MainActivity` — starts/stops the service and is the settings screen:
  rows Minimum, Maximum, Left, Right with "Quieter"/"Louder" buttons in 1 dB
  steps (keeps `min <= max`), plus a "Hearing devices" button. It writes the
  tuning (buttons and adb extras alike) straight to prefs, then starts the
  service, which rereads them through `VolumeService.tuning`. Stores pref
  `enabled` (true on start, false on `--ez stop true`); a button press
  restarts the service only if `enabled` is set. Each value row is a polite
  live region; Corvus announces it only sometimes, the user accepts that.
- `BootReceiver` — on `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED` and
  `MY_PACKAGE_REPLACED` starts the service if `enabled` is set. Receiver and
  service are `directBootAware`, because ASHA streams on the lock screen
  (Corvus speaks there) before the first unlock. Android 15 still allows a
  `connectedDevice` foreground service from boot (the ban covers dataSync,
  camera, mediaPlayback, phoneCall, mediaProjection, microphone). Verified
  after a reboot: the service starts at `LOCKED_BOOT_COMPLETED`, ~3 s after
  the screen comes up and before the PIN, and the lock screen speech is quiet.

## Verified facts (do not re-derive)

- Android sends the hearing aid volume through `BluetoothHearingAid.setVolume`:
  music index 0 → -128 (mute), 1..15 → -42..0 dB in ~3 dB steps. The
  `DEVICE_CATEGORY_HEARING_AID` curves in `dumpsys media.audio_policy` are
  not what is used. Not changeable without root.
- The processors accept volume writes from a second GATT client and go far
  below -42 dB. Comfortable range for this user: about -127..-64 dB.
- At index 0 the PCM is muted, so keeping Android at 0 and writing our own
  volume does not work; the service must follow index 1..15.
- Every stream start (also the BT audio HAL waking from STANDBY, e.g. after
  screen unlock) sends a Start command carrying Android's volume. There is no
  app-visible event for it except the AudioStatusPoint notification. Local
  `setCharacteristicNotification` is enough; the stack's own ASHA client
  already enabled the CCCD.
- The music index changes slightly before Android's write reaches the device,
  so our write can land first and be overwritten. Short blips on volume change
  remain; the user accepts them. Fully fixing this needs root (e.g. an LSPosed
  hook on `BluetoothHearingAid.setVolume`).
- Nucleus Smart has no streaming level setting for ASHA.
- Per-side offsets work: right 0 vs -30 dB at index 8 is a big audible
  difference. When testing by listening, note that every `am start` brings
  `MainActivity` to front and Corvus reads it aloud over the stream, which
  masks the change; compare A/B at a fixed index instead.
- An app cannot connect or disconnect the hearing devices without root
  (`BluetoothHearingAid.connect/disconnect` and `setConnectionPolicy` need
  `BLUETOOTH_PRIVILEGED`). The non-public action
  `android.settings.HEARING_DEVICES_SETTINGS` opens the system page that can,
  and an app may start it; it needs `FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK`,
  otherwise an open Settings task only comes to front on its old page.
- With offsets the user's balance is left +3 dB, right 0.
- Our ASHA volume writes do not affect the TV Streamer volume; the two are
  independent (verified by listening).
- `adb shell cmd media_session volume --stream 3 --set 0` does not change the
  hearing aid index; the user has to press the volume keys.
- Motorola's "moto_freezer" freezes the process ~10 s after screen off even
  though it runs a foreground service (`freezing ... reason=moto_freezer,
  adj=200, adjType=fg-service`). Volume changes then go through at Android's
  level until the screen turns on. An exemption from battery optimization
  (deviceidle user whitelist) stops this; `MainActivity` asks for it.
  Manually: `adb shell dumpsys deviceidle whitelist +com.example.ashavol`.
- Motorola also kills idle processes, including foreground services and TTS
  engines: `am_uid_stopped` then `am_kill ... Foreground kill`. It acts like a
  force stop, so `START_STICKY` does not bring the service back. Not yet known
  whether the battery exemption prevents it.

## Build, install, debug

    ./build.sh                       # javac + d8 + aapt2 + zipalign + apksigner
    adb install -r ashavol.apk
    adb shell am start -n com.example.ashavol/.MainActivity --ei min -127 --ei max -64
    adb logcat -s AshaVol

`build.sh` has the SDK path hard-coded (scoop `android-clt`, build-tools
36.0.0, platform android-36). Run it from Git Bash. `debug.keystore` is not in
git but must be kept: the installed app is signed with it, a new key would
force an uninstall. `adb install -r` kills the service; `BootReceiver`
restarts it within about a second if it was enabled (verified), otherwise
start it with `am start`.

Useful when diagnosing timing:

    adb logcat -b all -v time > /tmp/asha.log
    grep -E "BluetoothHearingAid\( *[0-9]+\): setVolume|AshaVol|hearing_aid.cc.*SendStart" /tmp/asha.log

## Testing

There are no automated tests. Verification is the user listening, so:
install, ask the user to try the specific scenario (volume keys, screen
lock/unlock, playback start, processor reconnect, TV Streamer switch), and
read the log timing afterwards. Logs contain personal data (device addresses,
other apps); keep them out of the repository.

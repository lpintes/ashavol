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
  Reached through adb over Wi-Fi (TLS pairing); `adb devices` shows one device.
- Hearing devices: two Cochlear processors, bonded LE devices named
  "<owner> Hearing Devi".
  Companion app `com.cochlear.clientremote` (Nucleus Smart).
- The user often switches the processors to a Cochlear TV Streamer (computer
  audio). The phone then drops the ASHA profile, but the LE link, and this
  service's GATT connection, may stay up.

## Architecture

Two classes, no Gradle:

- `VolumeService` — foreground service (type `connectedDevice`). Owns a GATT
  connection (`autoConnect=true`) to each bonded LE device whose name contains
  "Hearing", and writes a signed byte to the ASHA Volume characteristic with
  `WRITE_TYPE_NO_RESPONSE`. Triggers, each followed by a burst of retries
  (`RETRY_DELAYS_MS`):
  - polling `getStreamVolume(STREAM_MUSIC)` every `POLL_MS` (immediate write on change),
  - `VOLUME_CHANGED_ACTION` for `STREAM_MUSIC` (backup, arrives ~0.5 s late),
  - AudioStatusPoint notification (each ASHA Start command),
  - `AudioDeviceCallback` adding `TYPE_HEARING_AID`, services discovered,
    playback start, service start.
  Index 0 is left to Android. Index 1..max maps linearly to `min..max` dB,
  stored in SharedPreferences `ashavol`. All state is touched on the main
  thread; GATT callbacks post to the handler.
- `MainActivity` — only starts/stops the service and passes `min`/`max`.

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
- `adb shell cmd media_session volume --stream 3 --set 0` does not change the
  hearing aid index; the user has to press the volume keys.

## Build, install, debug

    ./build.sh                       # javac + d8 + aapt2 + zipalign + apksigner
    adb install -r ashavol.apk
    adb shell am start -n com.example.ashavol/.MainActivity --ei min -127 --ei max -64
    adb logcat -s AshaVol

`build.sh` has the SDK path hard-coded (scoop `android-clt`, build-tools
36.0.0, platform android-36). Run it from Git Bash. `debug.keystore` is not in
git but must be kept: the installed app is signed with it, a new key would
force an uninstall. `adb install -r` kills the service; start it again with
`am start`.

Useful when diagnosing timing:

    adb logcat -b all -v time > /tmp/asha.log
    grep -E "BluetoothHearingAid\( *[0-9]+\): setVolume|AshaVol|hearing_aid.cc.*SendStart" /tmp/asha.log

## Testing

There are no automated tests. Verification is the user listening, so:
install, ask the user to try the specific scenario (volume keys, screen
lock/unlock, playback start, processor reconnect, TV Streamer switch), and
read the log timing afterwards. Logs contain personal data (device addresses,
other apps); keep them out of the repository.

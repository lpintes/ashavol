# ashavol

Android foreground service that overrides the volume Android sends to ASHA
(Audio Streaming for Hearing Aids) devices.

## Problem

Android maps the music volume index 1..15 for hearing aids to a narrow range,
on the tested phone -42..0 dB in 3 dB steps (index 0 is mute, -128). For some
devices, here Cochlear Nucleus sound processors, even -42 dB is far too loud;
the usable range is roughly -127..-64 dB. Android cannot be reconfigured
without root.

## How it works

ASHA devices accept writes to the Volume characteristic
(`00e4ca9e-ab14-41e4-8823-f9e70c7e91df`, service `FDF0`) from a second GATT
client. The service keeps its own GATT connection to every bonded LE device
whose name contains "Hearing" and writes a value from its own table whenever
Android may have written its own:

- music volume index change (polled every 20 ms, plus `VOLUME_CHANGED_ACTION`),
- every stream start, detected through the AudioStatusPoint notification
  (`38663f1a-e711-4cac-b641-326b56404837`) that follows each Start command,
- hearing aid audio output added, device services discovered, service start.

Each event triggers several writes with growing delays so that the last write
lands after Android's. A very short loud blip on volume change can still occur.

Index 0 is left to Android (muted). Index 1..max maps linearly to `min..max` dB.
A per-side offset (`left`, `right`, up to ±30 dB) is added, so a weaker side
can be balanced; the side comes from the ASHA ReadOnlyProperties
characteristic. Every written value is clamped to -127..-40 dB, so no setting
can go above the -40 dB ceiling.

## Build

No Gradle. Needs the Android command line tools, build-tools 36.0.0 and
platform android-36; adjust `SDK` in `build.sh`.

    ./build.sh
    adb install -r ashavol.apk

## Use

Opening the app starts the service. The screen has a "Hearing devices" button,
which opens the system page for connecting and disconnecting hearing devices,
and rows Minimum, Maximum, Left and Right, each with "Quieter" and "Louder"
buttons in 1 dB steps. Changes apply immediately. The same values can be set
from adb:

    adb shell am start -n com.example.ashavol/.MainActivity --ei min -127 --ei max -64 --ei left 3 --ei right 0
    adb shell am start -n com.example.ashavol/.MainActivity --ez stop true

The table is stored and reused on later starts. Log tag: `AshaVol`.

Tested on Motorola edge 40 neo, Android 15, with two Cochlear Nucleus processors.

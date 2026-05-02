# RRStreamer

[![Releases](https://img.shields.io/github/v/release/abcsds/RRStreamer?label=download%20APK&logo=android)](https://github.com/abcsds/RRStreamer/releases/latest)

Android client that streams heart rate (HR) and R-R interval (RR) data from a
BLE heart-rate band (Polar H10, Polar OH1, Wahoo TICKR, Garmin HRM-Pro,
Coospo, Decathlon Kalenji, Suunto, or any other band advertising the standard
BLE Heart Rate service `0x180D`) to the network as
[Lab Streaming Layer](https://labstreaminglayer.org/) marker streams.
Behaviour matches the Python and Rust references at
[`HRBand-LSL`](https://github.com/abcsds/HRBand-LSL).

Project page (with screenshots and architecture):
[**abcsds.github.io/RRStreamer**](https://abcsds.github.io/RRStreamer/) ·
Source:
[**github.com/abcsds/RRStreamer**](https://github.com/abcsds/RRStreamer)

## Install

The easiest way to get the app is the prebuilt APK from the
[latest release](https://github.com/abcsds/RRStreamer/releases/latest):

1. On your Android phone, allow installs from your browser
   (Settings → Apps → Special access → Install unknown apps).
2. Download `RRStreamer-vX.Y.Z.apk` from the release page.
3. Open the file. Android will prompt for confirmation; tap *Install*.

Min Android version: **8.0 (API 26)**. Tested on **Android 15 (API 35)**.

## Build from source

The project ships a Nix flake that provisions JDK 17, Gradle 8, the Android
SDK + NDK, `adb`, and Python with `pylsl`. The phone needs developer mode
with USB debugging enabled and connected over `adb`.

```bash
git clone https://github.com/abcsds/RRStreamer.git
cd RRStreamer

nix run                   # build APK, install on connected device, launch (preferred)
nix run .#build           # just build APK; output ends up at app/build/outputs/apk/release/
nix build                 # ./result/bin/{rrstreamer-build,rrstreamer-run} wrappers
nix develop               # drop into a shell with gradle, adb, JDK, NDK, sdkmanager
```

> `nix build` produces wrapper scripts rather than a packaged APK because Gradle
> fetches its dependencies from Maven Central during the build and pure Nix
> derivations can't access the network. Run `result/bin/rrstreamer-build` (or
> just `nix run .#build`) to get the APK at
> `app/build/outputs/apk/release/app-release.apk`.

## Using the app

1. Grant Bluetooth (and on Android 13+, notification) permissions.
2. Tap **Scan**, wait a few seconds, then tap a device (e.g. `Polar H10 ABC12345`).
3. The app connects, subscribes to BLE Heart Rate Measurement (`0x2A37`),
   parses HR/RR per the BLE spec, and pushes samples to two LSL outlets:

| Outlet | Stream Name       | Type    | Channels | Format | Source ID                  |
|--------|-------------------|---------|----------|--------|----------------------------|
| HR     | `HR <devicename>` | Markers | 1        | int32  | `HR_markers_<devicename>`  |
| RR     | `RR <devicename>` | Markers | 1        | int32  | `RR_markers_<devicename>`  |

R-R intervals are pushed as **milliseconds (rounded int)**. A foreground
service keeps the connection alive when the screen is off.

## Verify the stream

On any machine on the same network:

```bash
pip install pylsl
python scripts/verify_lsl.py
```

The script resolves both `HR …` and `RR …` streams and prints each sample as
it arrives. Inside `nix develop` it's already available as `python3
scripts/verify_lsl.py`.

## How LSL is bundled

- Native `liblsl.so` (v1.16.2) for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
  is fetched from
  [`mvidaldp/liblsl-android-builder`](https://github.com/mvidaldp/liblsl-android-builder)
  via Nix; Gradle reads the path from `RRSTREAMER_LSL_JNILIBS` and uses it
  directly without mutating the source tree.
- `libjnidispatch.so` ships with the JNA Android AAR
  (`net.java.dev.jna:jna:5.13.0@aar`).
- The Java bindings (`edu.ucsd.sccn.LSL`) are vendored from
  [`liblsl-Java`](https://github.com/labstreaminglayer/liblsl-Java) with two
  small upstream-bug patches noted in the file header.

## License

MIT — see [`LICENSE`](LICENSE).

Author: **Alberto Barradas** ([@abcsds](https://github.com/abcsds))

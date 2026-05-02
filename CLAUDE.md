# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

RRStreamer is the Android sibling of `~/code/HRBand-LSL` (Rust on `main`, Python on `python` branch). It must produce LSL outlets that look identical to those references so any downstream pylsl/LabRecorder receiver works without changes:

| Outlet | Name              | Type    | Channels | Format | Source ID                |
|--------|-------------------|---------|----------|--------|--------------------------|
| HR     | `HR <devicename>` | Markers | 1        | int32  | `HR_markers_<devicename>` |
| RR     | `RR <devicename>` | Markers | 1        | int32  | `RR_markers_<devicename>` |

The device-name "filter" rule from the Python/Rust refs is preserved: drop devices whose advertised name contains `-` or is empty. **RR samples carry milliseconds (rounded int)** — this is a deliberate divergence from the Python and Rust references, which forwarded the raw 16-bit 1/1024-s value. Conversion happens once at the parse boundary in `HeartRateParser.rawToMs()`; everything downstream (UI, RMSSD, LSL push) speaks ms.

## Working in this repo

Everything is driven through Nix. Don't try to run `gradle` from outside `nix develop` — the SDK/NDK/JDK paths are set by the dev shell.

```bash
nix develop          # enter toolchain shell (JDK17, gradle, SDK34, NDK26, adb, python+pylsl)
nix run              # build → adb install → launch → logcat tail (preferred dev loop)
nix run .#build      # just rebuild the APK at app/build/outputs/apk/release/
nix build            # produces wrapper scripts at result/bin/ (see "Landmines" for why)
```

Tight iteration loop while developing:

```bash
nix develop --command bash -c '
  gradle --no-daemon --console=plain :app:assembleRelease &&
  adb install -r -t -g app/build/outputs/apk/release/app-release.apk &&
  adb shell am start -n io.github.abcsds.rrstreamer/.MainActivity'
```

Useful adb probes (run inside the dev shell so adb is on PATH):

```bash
adb logcat -d -t 60 BleScanner:D HeartRateClient:D HeartRateService:D LslStreamer:D AndroidRuntime:E '*:S'
adb exec-out screencap -p > /tmp/rr.png   # then Read it to inspect UI state
```

To verify the LSL stream from the dev machine (phone must be on the same Wi-Fi):

```bash
nix develop --command python3 scripts/verify_lsl.py
```

There are no unit tests yet. The verification path is **build → install → screenshot/logcat → pylsl receiver**.

## Architecture

Three independent units, plus the vendored LSL bridge.

```
io.github.abcsds.rrstreamer/
├── MainActivity.kt          Compose UI + AppState (StateFlows). Binds to HeartRateService.
├── HeartRateService.kt      Foreground service (type=connectedDevice). Owns the GATT
│                            connection AND the LSL outlets for the device's lifetime.
│                            Also defines the `StreamingState` sealed interface used
│                            across the activity and the service.
├── ble/
│   ├── BleScanner.kt        callbackFlow over BluetoothLeScanner, filtered by HR service UUID.
│   ├── HeartRateClient.kt   GATT connect + subscribe to char 0x2A37; emits parsed
│   │                        HeartRateMeasurement via callback.
│   ├── HeartRateParser.kt   Pure parser for the BLE 0x2A37 byte layout (uint8/uint16 HR,
│   │                        sensor contact, energy, RR pairs). Mirrors the Rust/Python parsers.
│   └── HeartRateUuids.kt    HR_SERVICE_UUID / HR_MEASUREMENT_UUID / CCCD_UUID constants.
└── lsl/
    └── LslHeartRateStreamer.kt  Two `edu.ucsd.sccn.LSL.StreamOutlet`s; pushes int32 samples.
```

**Data flow.** UI scan → `BleScanner.scan()` Flow → user taps a device → `HeartRateService.start()` is invoked → service creates `LslHeartRateStreamer` *first* (so LSL init failures abort before BLE) → `HeartRateClient.connect()` → on each notification, `HeartRateService.handleSample` pushes one HR sample plus N RR samples and updates `state: StateFlow<StreamingState>`. The activity reads `state` through a bound `LocalBinder`.

**Why it's a foreground service.** Polar H10 holds an exclusive BLE link; if the activity is paused while we're connected, an unbound GATT connection would be killed by the system. The service is `foregroundServiceType="connectedDevice"` (Android 14+ requires this typed declaration) so streaming continues with the screen off.

**LSL bridge.** Java bindings live at `app/src/main/java/edu/ucsd/sccn/LSL.java` (vendored from `labstreaminglayer/liblsl-Java`, package rewritten to `edu.ucsd.sccn`). At runtime `LSL.java` calls `Native.load("lsl", ...)` which maps to `liblsl.so` packed into the APK from `app/src/main/jniLibs/<abi>/`. The flake's `liblslJniLibs` derivation downloads and unpacks `mvidaldp/liblsl-android-builder` v1.16.2 release jniLibs, and `copyJniLibs` (run from both the dev-shell `shellHook` and the build/run scripts) installs them into the source tree. JNA's own `libjnidispatch.so` ships in the JNA Android AAR; `app/build.gradle.kts` has `packagingOptions.jniLibs.pickFirsts` covering both `**/liblsl.so` and `**/libjnidispatch.so` so the build doesn't choke on duplicates.

## Landmines

- **Compose plugin is mandatory.** This project uses `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.0.20). Do **not** add `composeOptions { kotlinCompilerExtensionVersion = ... }` — mixing the legacy compiler-extension path with Kotlin 2.0 produces a runtime crash on first composition: `java.lang.ArrayIndexOutOfBoundsException: length=0; index=-N` at `androidx.compose.runtime.SlotTableKt.key`.
- **`nix build` does not produce an APK directly.** Gradle fetches Maven deps from the network during the build, which a pure derivation cannot do. Marking the derivation `__impure` only works if the user has the `impure-derivations` experimental feature enabled (most stock-NixOS users don't), and `__noChroot` only works with `sandbox = relaxed`. The flake therefore exposes `packages.default` as a `symlinkJoin` of two wrapper scripts: `result/bin/rrstreamer-build` (gradle build) and `result/bin/rrstreamer-run` (build + install + launch). To get a real APK, use `nix run .#build` (or run the wrapper). To turn `nix build` into a true APK build later, wire `androidenv.buildGradleApp` with a generated deps manifest.
- **Two python3s on PATH.** The Android SDK derivation pulls a vanilla `python3` onto PATH. The flake's `shellHook` prepends `pythonWithLsl` so `python3 scripts/verify_lsl.py` finds pylsl. If you remove that prepend, the receiver script fails with `ModuleNotFoundError: No module named 'pylsl'`.
- **Stream-name and source-id format are load-bearing.** Existing pylsl receivers and LabRecorder configs may match by exact stream name (`HR <devicename>`) or `source_id` (`HR_markers_<devicename>`). Don't rename these fields without coordinating with downstream consumers.
- **BLE permission set is API-level-split.** Pre-S (API 30 and below) requires `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION`; API 31+ uses `BLUETOOTH_SCAN` (with `neverForLocation`) and `BLUETOOTH_CONNECT`. The manifest declares both with `maxSdkVersion` gates. `MainActivity.requiredPermissions()` builds the right subset at runtime, including `POST_NOTIFICATIONS` only on API 33+ (the manifest declares it unconditionally because that's harmless on older OS versions). Touch all three sites when changing perms.
- **`adb install -g` auto-grants permissions for the dev loop**, hiding the runtime permission UI. When testing the permission-gate Composable manually, install without `-g` (or revoke perms in app settings).
- **`liblsl.so` comes from a Nix-managed path, not the source tree.** The flake exports `RRSTREAMER_LSL_JNILIBS` pointing at the `liblslJniLibs` derivation; `app/build.gradle.kts` reads it and adds it to `sourceSets.main.jniLibs.srcDirs`. The vendored `app/src/main/jniLibs/<abi>/liblsl.so` files are kept in git only as a fallback for non-Nix builds — when building under `nix develop` / `nix run`, AGP ignores the source-tree copies and uses the store path. To bump the liblsl version, change the URL+sha256 in `flake.nix` and (optionally) update the in-git fallback by copying from the new `liblslJniLibs` output.

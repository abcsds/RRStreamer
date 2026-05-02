{
  description = "RRStreamer — Android client streaming BLE heart rate to Lab Streaming Layer";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Pinned Android toolchain.
        platformToolsVersion = "35.0.2";
        buildToolsVersion    = "34.0.0";
        platformVersion      = "34";
        ndkVersion           = "26.1.10909125";

        android = pkgs.androidenv.composeAndroidPackages {
          platformToolsVersion = platformToolsVersion;
          buildToolsVersions   = [ buildToolsVersion ];
          platformVersions     = [ platformVersion ];
          ndkVersions          = [ ndkVersion ];
          abiVersions          = [ "arm64-v8a" "armeabi-v7a" "x86" "x86_64" ];
          includeNDK           = true;
          includeEmulator      = false;
          includeSystemImages  = false;
        };

        sdkRoot = "${android.androidsdk}/libexec/android-sdk";
        ndkRoot = "${sdkRoot}/ndk/${ndkVersion}";

        jdk = pkgs.jdk17_headless;

        pythonWithLsl = pkgs.python3.withPackages (ps: [ ps.pylsl ]);

        # Prebuilt liblsl.so per ABI from mvidaldp/liblsl-android-builder v1.16.2.
        liblslJniLibs = pkgs.stdenv.mkDerivation {
          pname = "liblsl-android-jniLibs";
          version = "1.16.2";
          src = pkgs.fetchurl {
            url = "https://github.com/mvidaldp/liblsl-android-builder/releases/download/v1.16.2/liblsl-1.16.2-release_jniLibs.tar.gz";
            sha256 = "sha256-ii9UaVZSWCTT9PaZ6UreUZMTt9ma6wIlyZJ0VoApkhs=";
          };
          # Tarball has one top-level dir per ABI; unpack manually so default phase doesn't choke.
          dontUnpack = true;
          installPhase = ''
            runHook preInstall
            mkdir -p $out
            tar -xzf $src -C $out
            runHook postInstall
          '';
        };

        # Wrapper that exports an env for gradle (used by both the build derivation and the apps).
        # RRSTREAMER_LSL_JNILIBS is read by app/build.gradle.kts; it points Gradle at the
        # Nix-managed jniLibs path so we don't have to mutate the source tree.
        envExports = ''
          export JAVA_HOME=${jdk}
          export ANDROID_HOME=${sdkRoot}
          export ANDROID_SDK_ROOT=${sdkRoot}
          export ANDROID_NDK_ROOT=${ndkRoot}
          export ANDROID_NDK_HOME=${ndkRoot}
          export RRSTREAMER_LSL_JNILIBS=${liblslJniLibs}
          export PATH=${sdkRoot}/platform-tools:${jdk}/bin:$PATH
        '';

        commonRuntime = [
          pkgs.gradle
          pkgs.coreutils
          pkgs.findutils
          pkgs.gnused
          pkgs.gnugrep
          android.androidsdk
          android.platform-tools
          jdk
        ];

        buildScript = pkgs.writeShellApplication {
          name = "rrstreamer-build";
          runtimeInputs = commonRuntime;
          text = ''
            ${envExports}
            cd "''${PROJECT_ROOT:-$PWD}"
            echo "==> Building RRStreamer release APK"
            gradle --no-daemon --console=plain :app:assembleRelease
            APK="app/build/outputs/apk/release/app-release.apk"
            if [ ! -f "$APK" ]; then
              echo "Expected APK not found at $APK" >&2
              find app/build/outputs -type f -name '*.apk' >&2 || true
              exit 1
            fi
            echo "==> Built: $APK"
          '';
        };

        deployScript = pkgs.writeShellApplication {
          name = "rrstreamer-run";
          runtimeInputs = commonRuntime;
          text = ''
            ${envExports}
            cd "''${PROJECT_ROOT:-$PWD}"

            # 1. Verify a device is attached.
            if ! adb get-state >/dev/null 2>&1; then
              echo "No adb device. Plug in your phone with USB debugging on." >&2
              adb devices >&2 || true
              exit 1
            fi

            # 2. Build.
            echo "==> Building RRStreamer release APK"
            gradle --no-daemon --console=plain :app:assembleRelease
            APK="app/build/outputs/apk/release/app-release.apk"
            if [ ! -f "$APK" ]; then
              APK="$(find app/build/outputs -type f -name '*.apk' | head -n1)"
            fi
            test -n "$APK" || { echo "No APK produced" >&2; exit 1; }

            # 3. Install + launch.
            echo "==> Installing $APK"
            adb install -r -t -g "$APK"
            echo "==> Launching io.github.abcsds.rrstreamer/.MainActivity"
            adb shell am start -n io.github.abcsds.rrstreamer/.MainActivity
            echo "==> Tailing logcat (Ctrl+C to stop)"
            exec adb logcat -v brief \
              BleScanner:D HeartRateClient:D HeartRateService:D LslStreamer:D AndroidRuntime:E '*:S'
          '';
        };
      in {
        # `nix build` → wrapper scripts in result/bin/. The actual APK is produced by
        # running `result/bin/rrstreamer-build` (or `nix run .#build`).
        #
        # Why a wrapper and not a real APK derivation: gradle resolves Maven deps over
        # the network during the build, which neither pure-sandbox derivations nor the
        # `__impure` attribute can do without enabling the `impure-derivations`
        # experimental feature. Wrapping keeps the flake usable on stock Nix.
        packages.default = pkgs.symlinkJoin {
          name = "rrstreamer-scripts";
          paths = [ buildScript deployScript ];
        };

        # `nix run` → build + adb install + launch + logcat tail.
        apps.default = {
          type = "app";
          program = "${deployScript}/bin/rrstreamer-run";
        };

        apps.build = {
          type = "app";
          program = "${buildScript}/bin/rrstreamer-build";
        };

        # `nix develop` → full toolchain shell.
        devShells.default = pkgs.mkShell {
          buildInputs = commonRuntime ++ [
            pkgs.android-tools  # adb, fastboot
            pythonWithLsl
          ];
          shellHook = ''
            ${envExports}
            # Shadow the python that android-sdk-tools drops on PATH so pylsl is importable.
            export PATH=${pythonWithLsl}/bin:$PATH
            echo "RRStreamer dev shell ready (LSL jniLibs: $RRSTREAMER_LSL_JNILIBS)."
            echo "  gradle :app:assembleRelease   # build APK"
            echo "  adb install -r app/build/outputs/apk/release/app-release.apk"
            echo "  nix run                       # build + deploy + launch on connected device"
          '';
        };
      });
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.abcsds.rrstreamer"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.abcsds.rrstreamer"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    // The flake's `liblslJniLibs` derivation contains liblsl.so per ABI.
    // When building under Nix, RRSTREAMER_LSL_JNILIBS points at that store path
    // and we use it as the *only* jniLibs source — `setSrcDirs` replaces the
    // default `src/main/jniLibs`, otherwise AGP would see the same .so twice
    // and fail mergeReleaseJniLibFolders. Outside Nix the env var is unset and
    // AGP picks up the vendored copies in `src/main/jniLibs/` (tracked in git).
    System.getenv("RRSTREAMER_LSL_JNILIBS")?.let { path ->
        sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(path))
    }

    signingConfigs {
        // A debug-style signing key generated at build time inside Nix; see flake.nix.
        // For local gradle builds outside Nix, fall back to the default debug keystore.
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Sign release with the debug key so `nix run` can install without keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // liblsl.so and libjnidispatch.so come from two sources (vendored + JNA AAR).
        // Keep the first occurrence so duplicates from JNA don't fail the build.
        jniLibs {
            pickFirsts += listOf("**/liblsl.so", "**/libjnidispatch.so")
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle / coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // JNA — provides libjnidispatch.so for all ABIs and the JNA Java runtime.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

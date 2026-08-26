plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pruge.quicmic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pruge.quicmic"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Debug-only distribution via adb for now (no release signing yet).
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // BuildConfig.VERSION_NAME is shown on the settings tab.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Framework APIs plus exactly two feature libraries for the native home /
// settings screens (T04): CameraX drives the scanner preview and ML Kit's
// bundled barcode model decodes QR frames fully on-device. Versions are
// pinned in gradle/libs.versions.toml.
dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
}

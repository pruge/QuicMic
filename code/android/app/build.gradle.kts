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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Intentionally empty: the wrapper uses only framework APIs (WebView,
// HttpsURLConnection, java.security) so the build needs no artifact downloads
// beyond the pinned Gradle/AGP/Kotlin toolchain itself.
dependencies {
}

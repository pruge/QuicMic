// QuicMic Android wrapper — root build script.
// Toolchain is pinned to the same versions as the reference project
// (Gradle 8.11.1 / AGP 8.7.3 / Kotlin 2.1.0) so local Gradle caches are reused.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

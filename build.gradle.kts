plugins {
    // No org.jetbrains.kotlin.android: AGP 9's built-in Kotlin support compiles
    // Android-module Kotlin sources directly, that plugin is no longer needed
    // (only org.jetbrains.kotlin.jvm, for the pure-JVM flasher-core module).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

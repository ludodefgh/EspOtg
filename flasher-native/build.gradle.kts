import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
    // compiles Kotlin sources directly, that plugin is no longer needed/allowed.
}

android {
    namespace = "com.espotg.flasher"
    compileSdk = 36
    ndkVersion = libs.versions.ndkVersion.get()

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmakeVersion.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        // Without an explicit jvmToolchain() request, Gradle just uses whatever
        // JVM is currently running the daemon for javac instead of provisioning
        // one - on this machine that's a JRE with no compiler at all. See
        // CLAUDE.md "Toolchain / environment gotchas".
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}

dependencies {
    implementation(project(":flasher-core"))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floatwm.launcher"
    // compileSdk / targetSdk pinned to Android 16 (API 36) rather than Android
    // 17 (API 37) purely for tooling maturity at the time this was written --
    // API 37 shipped stable in June 2026 and this code has no known
    // incompatibilities with it. Bump both to 37 once your installed Android
    // Studio / SDK Platform for 37 is in place; nothing in this app depends
    // on a 36-specific behavior.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.floatwm.launcher"
        // Floored at Android 14 (API 34) deliberately: every version-gated
        // branch this app would otherwise need (POST_NOTIFICATIONS runtime
        // permission, foreground service types, WindowMetrics) is already
        // mandatory by API 34, so minSdk = 34 lets the code stay branch-free
        // instead of carrying dead compatibility paths for OS versions this
        // app doesn't claim to support.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

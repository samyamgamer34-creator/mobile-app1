plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.theftguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theftguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed signing key so every build (local or CI) is signed the same way and
    // updates install over the previous version without an uninstall. This key is
    // only for a personal, sideloaded app; it is not a Play Store upload key.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("theftguard.keystore")
            storePassword = "theftguard"
            keyAlias = "theftguard"
            keyPassword = "theftguard"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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

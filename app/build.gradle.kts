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
        versionCode = 2
        versionName = "1.1"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
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

    // The two mail libraries both ship META-INF license/notice files; drop the
    // duplicates so the APK packager doesn't fail merging them.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    // Shizuku: lets the app run shell-privileged actions (turn on mobile data,
    // Wi-Fi and location when the alarm triggers) if the user has Shizuku running.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Send the location by email over SMTP (used when the user picks email delivery).
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}

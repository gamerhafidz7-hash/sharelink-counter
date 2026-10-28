plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hafidz.jamcounter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hafidz.jamcounter"
        minSdk = 26
        targetSdk = 34
        // Diisi workflow GitHub (-PappVersionCode=<nomor run>) supaya tiap rilis lebih baru dari sebelumnya.
        val vc = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "1.$vc"
    }

    // Kunci tetap (kredensial standar debug) supaya APK dari build mana pun bisa saling menimpa (update).
    signingConfigs {
        create("fc") {
            storeFile = file("keystore/fc.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fc")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fc")
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

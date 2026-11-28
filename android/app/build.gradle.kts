plugins {
    id("com.android.application")
}

android {
    namespace = "com.matthewcodergamer.mcnative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matthewcodergamer.mcnative"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (!System.getenv("MC_ANDROID_KEYSTORE").isNullOrBlank()) {
            create("release") {
                storeFile = file(System.getenv("MC_ANDROID_KEYSTORE"))
                storePassword = System.getenv("MC_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MC_ANDROID_KEY_ALIAS") ?: "mcnative"
                keyPassword = System.getenv("MC_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        jniLibs {
            // Keep the .so extractable. NativeActivity then loads it from the
            // app's native library directory on every supported Android release.
            useLegacyPackaging = true
        }
    }
}

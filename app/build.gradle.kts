plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки приходит из GitHub Actions (run_number), локально — 1
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.hm.courierradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.hm.courierradar"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Постоянный ключ, чтобы новые сборки ставились поверх старой без удаления
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("radar-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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

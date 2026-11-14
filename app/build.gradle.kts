plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.myai.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.myai.assistant"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("myai-release.jks")
            storePassword = System.getenv("MYAI_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MYAI_KEY_ALIAS")
            keyPassword = System.getenv("MYAI_KEY_PASSWORD")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

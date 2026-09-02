plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cupflow.glass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cupflow.glass"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Official Rokid CXR-S bridge for native YodaOS-Sprite glasses apps.
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pleosracing.probe"
    compileSdk = 34

    // AAOS Car API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.pleosracing.probe"
        minSdk = 28
        targetSdk = 34
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
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}

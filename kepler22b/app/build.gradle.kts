plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.byeolmajung.app"
    compileSdk = 34

    // AAOS Car API (CarUxRestrictionsManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.byeolmajung.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Contest builds are signed with the debug key until a release keystore is set up.
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":sky"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    // Screenshots of the real activity on the JVM (no emulator needed): ./gradlew :app:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Robolectric's Android runtime has no car library; the SDK stub jar lets car-dependent classes load.
    testImplementation(files("${android.sdkDirectory}/platforms/android-34/optional/android.car.jar"))
}

tasks.withType<Test>().configureEach {
    systemProperty("screenshot.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

// Sky engine: astronomy, catalog, projection and scene building, in pure Kotlin.
// - jvm: used by the Android app; unit-tested and previewed on the JVM.
// - js: the same engine in a web demo (src/jsMain) that runs in any browser.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "byeolmajung.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnit()
    // Preview images are written here by SkyPreviewTest.
    systemProperty("preview.dir", layout.buildDirectory.dir("preview").get().asFile.absolutePath)
    systemProperty("assets.dir", rootProject.file("app/src/main/assets").absolutePath)
    systemProperty("java.awt.headless", "true")
}

// Web demo bundle: compiled engine + page + sky data, ready to publish (build/web).
val webDemo by tasks.registering(Copy::class) {
    dependsOn("jsBrowserDistribution")
    from(layout.buildDirectory.dir("dist/js/productionExecutable"))
    from(rootProject.file("app/src/main/assets/sky")) { into("sky") }
    into(layout.buildDirectory.dir("web"))
}

// Pure Kotlin sky engine: astronomy, catalog, projection and scene building.
// No Android dependency, so it is unit-tested and previewed on the JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Preview images are written here by SkyPreviewTest.
    systemProperty("preview.dir", layout.buildDirectory.dir("preview").get().asFile.absolutePath)
    systemProperty("assets.dir", rootProject.file("app/src/main/assets").absolutePath)
    systemProperty("java.awt.headless", "true")
}

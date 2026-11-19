// The Lumos engine: everything that isn't a screen. One Kotlin codebase, used by the Android TV app
// directly and by the iPad/iPhone/Mac app as a framework.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val ktor = "3.0.1"

kotlin {
    jvmToolchain(17)
    jvm()
    // LumosEngine.xcframework (iPhone and simulator): ./gradlew :engine:assembleLumosEngineReleaseXCFramework
    val xcf = XCFramework("LumosEngine")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "LumosEngine"; isStatic = true; xcf.add(this) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("io.ktor:ktor-client-core:$ktor")
        }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktor") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:$ktor") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:$ktor")
        }
    }
}

tasks.withType<Test> {
    systemProperty("live", System.getProperty("live") ?: "false")
    testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.withType<Test> { systemProperty("sync", System.getProperty("sync") ?: "") }

// The Lumos engine (copied from Lumos, JVM only): Live TV, sports, the guide and the sync server client, for Lumen on phones.

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val ktor = "3.0.1"

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("io.ktor:ktor-client-core:$ktor")
        }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktor") }
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

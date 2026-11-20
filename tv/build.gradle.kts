// Lumos for Android TV: Lumen TV's own screens and player (copied unchanged from dxbi-stremio/tv: src/main, src/shared,
// src/lumen), worked by the Lumos engine instead of Stremio's web page. No web page, no torrent player on the TV.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The TMDB key Lumen uses (cast photos, trailers, facts), from Lumen TV's own keystore.properties, kept out of git
val lumenProps = Properties().apply {
    val f = rootProject.file("../dxbi-stremio/tv/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Lumos's own release key (tv/lumos-release.jks, its passwords in tv/keystore.properties: both kept out of git and backed up,
// as a TV only takes an update signed with the key it was installed with). -PdebugSigned builds with the debug key instead,
// for a box that still has a debug-signed build
val lumosProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKey = lumosProps.getProperty("storeFile")?.let { file(it) }?.takeIf { it.exists() && !project.hasProperty("debugSigned") }
val lumenKey = lumenProps.getProperty("storeFile")?.let { rootProject.file("../dxbi-stremio/tv/$it") }?.takeIf { it.exists() }
val tmdbKey: String = (lumenProps.getProperty("tmdbKey") ?: System.getenv("LUMEN_TMDB_KEY") ?: "").trim().replace("\"", "")

android {
    // (the code's packages stay Lumen's, so its files are unchanged copies; the app's id is Lumos's own)
    namespace = "com.notdxbi.dxbi.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.harrythedingle.lumos.tv"
        minSdk = 24
        targetSdk = 35
        versionName = "0.1.8"
        versionCode = 108
        buildConfigField("String", "UPDATE_REPO", "\"\"")
        // (where a newer Lumos is found: sync/release-tv.sh puts it there)
        buildConfigField("String", "UPDATE_FEED", "\"https://lumos-sync.hedigital.workers.dev/update/tv/release.json\"")
        buildConfigField("String", "APP_NAME", "\"Lumos\"")
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
        buildConfigField("boolean", "SELF_UPDATE", "true")
        buildConfigField("boolean", "IMMERSIVE", "true")
        buildConfigField("boolean", "PHONE_UI", "false")
        buildConfigField("String", "APK_NAME", "\"lumos-tv.apk\"")
    }

    signingConfigs {
        // (Lumen TV's own key, from dxbi-stremio/tv/keystore.properties: Lumen 3 must be signed as Lumen 2 was)
        if (lumenKey != null) create("lumen") {
            storeFile = lumenKey
            storePassword = lumenProps.getProperty("storePassword")
            keyAlias = lumenProps.getProperty("keyAlias")
            keyPassword = lumenProps.getProperty("keyPassword")
        }
        if (releaseKey != null) create("lumos") {
            storeFile = releaseKey
            storePassword = lumosProps.getProperty("storePassword")
            keyAlias = lumosProps.getProperty("keyAlias")
            keyPassword = lumosProps.getProperty("keyPassword")
        }
    }

    // "device": one flavor, "lumen", so src/lumen lays over src/main exactly as it does in Lumen TV.
    // "brand": what it's installed as. lumos: Lumos, next to Lumen (its own id and key, updates from the sync server).
    // lumen3: Lumen 3, the update to Lumen TV (Lumen's id and key, so Lumen 2 offers it and installs over itself; Lumen's
    // icon and logo from src/lumen3/res; later updates from Lumen's GitHub releases as before)
    flavorDimensions += listOf("brand", "device")
    productFlavors {
        create("lumos") {
            dimension = "brand"
            signingConfig = if (releaseKey != null) signingConfigs.getByName("lumos") else signingConfigs.getByName("debug")
        }
        create("lumen3") {
            dimension = "brand"
            applicationId = "com.harrythedingle.lumen.tv"
            versionName = "3.0.12"
            versionCode = 30012
            buildConfigField("String", "APP_NAME", "\"Lumen\"")
            buildConfigField("String", "UPDATE_REPO", "\"HarryTheDingle/lumen\"")
            buildConfigField("String", "UPDATE_FEED", "\"\"")
            buildConfigField("String", "APK_NAME", "\"lumen-tv.apk\"")
            signingConfig = if (lumenKey != null) signingConfigs.getByName("lumen") else signingConfigs.getByName("debug")
        }
        create("lumen") { dimension = "device" }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug { ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") } }
        release {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            isMinifyEnabled = !project.hasProperty("profiling")
            isShrinkResources = !project.hasProperty("profiling")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // (the brand flavors say which key)
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["lumen"].java.srcDir("src/shared/java")
    sourceSets["lumen"].res.srcDir("src/shared/res")
}

dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}

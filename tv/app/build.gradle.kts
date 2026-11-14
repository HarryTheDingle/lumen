import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The TV app shares dx.bi's version number: it's read from the plugin at the repository root
val pluginFile = rootProject.file("../dxbi.plugin.js")
val dxbiVersion = Regex("""@version\s+([\d.]+)""").find(pluginFile.readText())!!.groupValues[1]
val versionParts = dxbiVersion.split(".").map { it.toInt() }

// Release signing: tv/keystore.properties (not in git) or the DXBI_TV_KEYSTORE* environment variables in CI
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

// Lumen's TMDB key (age ratings, languages, cast pages, reviews without typing one in): tmdbKey in tv/keystore.properties
// (not in git), or LUMEN_TMDB_KEY in CI (a GitHub Actions secret). Empty when neither has it: the apps then need a key in Settings
val lumenTmdbKey: String = (keystoreProps.getProperty("tmdbKey") ?: System.getenv("LUMEN_TMDB_KEY") ?: "").trim().replace("\"", "")

// Where the app looks for its updates: the repository it was built from (GitHub Actions says which), so
// a fork's TVs follow the fork's own releases rather than being offered builds signed with another key
val updateRepo = System.getenv("DXBI_UPDATE_REPO") ?: System.getenv("GITHUB_REPOSITORY") ?: "HarryTheDingle/lumen"

android {
    namespace = "com.notdxbi.dxbi.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notdxbi.dxbi.tv"
        minSdk = 24
        targetSdk = 35
        versionName = dxbiVersion
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // What the app calls itself in what it says (Lumen overrides it)
        buildConfigField("String", "APP_NAME", "\"dx.bi\"")
        buildConfigField("String", "TMDB_KEY", "\"\"")
        // Released builds keep themselves current; test builds run the plugin they were built with
        buildConfigField("boolean", "SELF_UPDATE", "true")
        // The status and gesture bars hidden (dx.bi's way: the page fills the screen). Lumen for phones keeps them, as iOS does
        buildConfigField("boolean", "IMMERSIVE", "true")
    }

    // Two apps from this code, each released and updated on its own:
    //  - tv: dx.bi TV for Android TV, Google TV and Fire TV (Stremio's page, worked by remote)
    //  - mobile: dx.bi for Android phones and tablets (native screens over Stremio's page)
    //  - lumen: Lumen for Android TV and Google TV (native screens worked by remote, over the same page)
    //  - lumenphone: Lumen for Android phones and the Pixel Fold (the iPhone and iPad app's look, over the same page)
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "PHONE_UI", "false")
            buildConfigField("String", "APK_NAME", "\"dxbi-tv.apk\"")
            // dx.bi's intro film as it opens
            buildConfigField("boolean", "INTRO", "true")
            buildConfigField("boolean", "OWN_INTRO", "false")
        }
        create("mobile") {
            dimension = "device"
            applicationId = "com.notdxbi.dxbi.mobile"
            buildConfigField("boolean", "PHONE_UI", "true")
            buildConfigField("String", "APK_NAME", "\"dxbi-mobile.apk\"")
            buildConfigField("boolean", "INTRO", "false")
            buildConfigField("boolean", "OWN_INTRO", "false")
            proguardFiles("proguard-mobile.pro")
            // (what src/phonecommon/AndroidManifest.xml leaves to each phone app: the page's screen handles the same
            // changes as dx.bi TV's, and Back is the old kind)
            manifestPlaceholders["mainConfigChanges"] = "orientation|screenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode"
            manifestPlaceholders["predictiveBack"] = "false"
        }
        create("lumenphone") {
            dimension = "device"
            applicationId = "com.harrythedingle.lumen"
            buildConfigField("boolean", "PHONE_UI", "true")
            buildConfigField("String", "APK_NAME", "\"lumen-phone.apk\"")
            // (no film: the iPhone app has none, Lumen opens straight to its own loading screen)
            buildConfigField("boolean", "INTRO", "false")
            buildConfigField("boolean", "OWN_INTRO", "false")
            buildConfigField("String", "APP_NAME", "\"Lumen\"")
            buildConfigField("String", "TMDB_KEY", "\"$lumenTmdbKey\"")
            buildConfigField("boolean", "IMMERSIVE", "false")
            proguardFiles("proguard-mobile.pro")
            // The Pixel Fold folding or unfolding changes the smallest width too: handled here, so the page (and
            // the sign-in and Home on screen) carry on instead of starting again. Back is the predictive kind.
            manifestPlaceholders["mainConfigChanges"] = "orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode"
            manifestPlaceholders["predictiveBack"] = "true"
            // (released builds keep themselves current from this repository's releases: lumen-phone.apk; preview and
            // debug builds install as com.harrythedingle.lumen.dev and don't, so a test build never touches the family's copy)
        }
        create("lumen") {
            dimension = "device"
            applicationId = "com.harrythedingle.lumen.tv"
            buildConfigField("boolean", "PHONE_UI", "false")
            buildConfigField("String", "APK_NAME", "\"lumen-tv.apk\"")
            // Lumen's own intro film (src/lumen/res/raw/intro.mp4, from tools/render_lumen_intro.py), in its own colours
            buildConfigField("boolean", "INTRO", "true")
            buildConfigField("boolean", "OWN_INTRO", "true")
            buildConfigField("String", "APP_NAME", "\"Lumen\"")
            buildConfigField("String", "TMDB_KEY", "\"$lumenTmdbKey\"")
            // (released Lumen builds keep themselves current from this repository's releases: lumen-tv.apk;
            // preview and debug builds don't, they run what they were built with)
        }
    }

    buildFeatures {
        buildConfig = true
        // The phone app's screens (TVs keep Stremio's page)
        compose = true
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "DXBI_TV_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "DXBI_TV_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DXBI_TV_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DXBI_TV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // the emulator is x86
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        }
        release {
            // TVs and sticks are ARM: only those copies of the torrent engine (about 14 MB each)
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingValue("storeFile", "DXBI_TV_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
        // A release build to test on a phone: as fast as a release (shrunk, optimised, not debuggable),
        // signed with the debug key and installed beside the released app like a debug build
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-preview"
            matchingFallbacks += "release"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The torrent engine's native code compressed in the APK: a smaller download for updates
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // The built-in dx.bi plugin is the one at the repository root, copied in at build time
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/dxbiAssets"))
    // What the phone app and Lumen share: Stremio as native screens see it (src/shared), and the
    // script that lets them (dxbi-engine.js, in the phone app's assets)
    for (flavor in listOf("mobile", "lumen", "lumenphone")) {
        sourceSets[flavor].java.srcDir("src/shared/java")
        sourceSets[flavor].res.srcDir("src/shared/res")
    }
    // (the phone app's own assets, dxbi-engine.js, are already in `mobile`)
    for (flavor in listOf("lumen", "lumenphone")) sourceSets[flavor].assets.srcDir("src/mobile/assets")
    // What the two phone apps share (dx.bi's and Lumen's): the player's controls, gestures, small window and
    // lock screen, casting, downloads, other players, and the manifest entries that go with them
    for (flavor in listOf("mobile", "lumenphone")) {
        sourceSets[flavor].java.srcDir("src/phonecommon/java")
        sourceSets[flavor].res.srcDir("src/phonecommon/res")
        sourceSets[flavor].manifest.srcFile("src/phonecommon/AndroidManifest.xml")
    }
    // Lumen's icon, name and pictures, for its phone app too
    sourceSets["lumenphone"].res.srcDir("src/lumen/res")
}

androidComponents {
    // The phone app's test builds install next to its released copy (a different key can't replace it),
    // with their own data, and run the plugin they were built with (a released one fetched over it would
    // hide the work being tested). dx.bi TV's test builds stay as they were.
    for ((flavor, id) in listOf("mobile" to "com.notdxbi.dxbi.mobile.dev", "lumenphone" to "com.harrythedingle.lumen.dev")) {
        onVariants(selector().withFlavor("device" to flavor).withBuildType("debug")) { variant ->
            variant.applicationId.set(id)
            variant.buildConfigFields.put("SELF_UPDATE", com.android.build.api.variant.BuildConfigField("boolean", "false", null))
            variant.outputs.forEach { it.versionName.set("$dxbiVersion-dev") }
        }
    }
    // (the preview build is the phone apps', and Lumen TV's: there it's what goes on the TV, as fast as a
    // release, and installs over the debug build, so you stay signed in)
    beforeVariants(selector().withFlavor("device" to "tv").withBuildType("preview")) { it.enable = false }
    onVariants(selector().withFlavor("device" to "lumen").withBuildType("preview")) { variant ->
        variant.applicationId.set("com.harrythedingle.lumen.tv")
    }
}

val copyPlugin by tasks.registering(Copy::class) {
    from(pluginFile)
    into(layout.buildDirectory.dir("generated/dxbiAssets"))
}
tasks.named("preBuild") { dependsOn(copyPlugin) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // Sound the device can't decode itself: most phones have no Dolby or DTS decoder, so those films
    // played silently. Jellyfin's build of Media3's FFmpeg extension; the hardware still goes first
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Torrents played on the TV itself (no streaming server needed): libtorrent, and a small local
    // web server that hands the downloading file to the player
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // The phone apps only (the mobile and lumenphone flavors): dx.bi TV and Lumen TV have none of these
    for (phone in listOf("mobile", "lumenphone")) {
        // lock screen, headphone and picture-in-picture controls
        "${phone}Implementation"("androidx.media3:media3-session:1.5.1")
        // casting to a Chromecast or Google TV (only started when something is cast)
        "${phone}Implementation"("com.google.android.gms:play-services-cast-framework:22.0.0")
        "${phone}Implementation"("androidx.mediarouter:mediarouter:1.7.0")
        // native screens drawn with Compose, over the page that does Stremio's work
        "${phone}Implementation"(platform("androidx.compose:compose-bom:2024.12.01"))
        "${phone}Implementation"("androidx.compose.ui:ui")
        "${phone}Implementation"("androidx.compose.foundation:foundation")
        "${phone}Implementation"("androidx.compose.material3:material3")
        "${phone}Implementation"("androidx.compose.material:material-icons-extended")
        "${phone}Implementation"("androidx.navigation:navigation-compose:2.8.5")
        // the frosted tab bar: what's behind it, blurred (Android 12 and later; a tint before that)
        "${phone}Implementation"("dev.chrisbanes.haze:haze:1.1.1")
        "${phone}Implementation"("androidx.activity:activity-compose:1.9.3")
        "${phone}Implementation"("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
        "${phone}Implementation"("io.coil-kt.coil3:coil-compose:3.0.4")
        "${phone}Implementation"("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
        "${phone}Implementation"("androidx.compose.ui:ui-tooling-preview")
    }
    // Lumen for phones: its screens' ear for the window (phone, or the Fold opened out flat: how wide, which way it folds)
    "lumenphoneImplementation"("androidx.compose.material3:material3-window-size-class")
    "lumenphoneImplementation"("androidx.window:window:1.3.0")
    "lumenphoneImplementation"("androidx.profileinstaller:profileinstaller:1.3.1")
    // Lumen: its screens drawn with Compose for TV (focus, the remote's D-pad, cards that grow when chosen)
    "lumenImplementation"(platform("androidx.compose:compose-bom:2025.04.01"))
    "lumenImplementation"("androidx.compose.ui:ui")
    "lumenImplementation"("androidx.compose.foundation:foundation")
    "lumenImplementation"("androidx.tv:tv-material:1.0.0")
    "lumenImplementation"("androidx.compose.material:material-icons-extended")
    "lumenImplementation"("androidx.navigation:navigation-compose:2.8.5")
    "lumenImplementation"("androidx.activity:activity-compose:1.9.3")
    "lumenImplementation"("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    "lumenImplementation"("io.coil-kt.coil3:coil-compose:3.0.4")
    "lumenImplementation"("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // (the baseline profile, baseline-prof.txt, applied on install: Lumen's screens compiled ahead of time, not interpreted)
    "lumenImplementation"("androidx.profileinstaller:profileinstaller:1.3.1")
    // (the Compose compiler wants its runtime in every build: dx.bi TV uses none of it, and its release leaves it out)
    "tvImplementation"(platform("androidx.compose:compose-bom:2024.12.01"))
    "tvImplementation"("androidx.compose.runtime:runtime")
}

# Lumen for Android TV

Lumen for Android TV and Google TV. It is the `lumen` product flavor: its screens are in `app/src/lumen/`. For what it is and how to install it, see the [main README](../README.md). The notes below describe the shared engine it is built on (from dx.bi, see [docs/dxbi](../docs/dxbi)), which also builds the original `tv` and `mobile` flavors.

How it works:

- `MainActivity` shows Stremio's web app (web.stremio.com) in a WebView laid out 1280 CSS pixels wide, with a desktop browser's identity plus `dxbi-tv/<version>` (the plugin recognises it and switches to its TV mode).

- `assets/dxbi-tv.js` runs in the page before Stremio's own scripts. It stands in for Stremio Enhanced's plugin API (settings, alerts, prompts), loads the dx.bi plugin, adds a remote-friendly *dx.bi settings* entry to Stremio's Settings, and handles the remote's Back button.

- Playback: when Stremio opens its player, the stream (with its headers, subtitles and saved position) is handed to `PlayerActivity`, a Media3/ExoPlayer player. Stremio's player screen stays underneath, silent, and gets `TimeChanged` / `PausedChanged` / `Ended` from the native player, so Continue Watching and watched marks work as usual.

- The dx.bi plugin is the one at the repository root, copied in at build time. `Updates` keeps a newer copy from GitHub between app releases, and offers new app releases (downloaded and handed to Android's installer).

- Torrents need a Stremio streaming server elsewhere on the network (Settings → Streaming); debrid streams play directly.

## dx.bi for Android phones

The same code builds a second app, `com.notdxbi.dxbi.mobile`, as the `mobile` product flavor (`tv` is dx.bi TV). They're released and updated separately: `dxbi-tv.apk` and `dxbi-mobile.apk`, from the same release.

- dx.bi TV is unchanged by it. The phone app's code, resources, libraries and manifest additions are all in `app/src/mobile/`; dx.bi TV's code calls into it at a few places through a `Phone` object (see `PhoneHooks.kt`), which `app/src/tv/` defines as doing nothing. dx.bi TV's APK has none of the phone app in it.
- The page still does Stremio's work underneath, laid out but covered by native Compose screens (`src/mobile/…/phone/`): `Phone.attach` adds them to `MainActivity`, and `src/mobile/assets/dxbi-engine.js` to the page.
- `dxbi-engine.js` is the screens' way into the page: they ask it to load Stremio's models and dispatch actions (`__dxbiEngine.handle({id, op, args})`), and it sends back the models they watch, trimmed to what they show, through `DxbiTV.engine`. dx.bi's own features come from the plugin's `api.phone` (billboard, rows, stream choices, reviews, cast, stats, watch party). The iPhone app uses the same script.
- Playing still goes through Stremio's player route and `dxbi-tv.js`'s handoff, so watch progress, up next and the watch party work as on a TV. On a phone `PlayerActivity` gets `PhonePlayer` (its `PlayerHooks`): `PhoneControls`, `PlayerGestures`, picture-in-picture and a media session. `Phone.Target` sends a stream to another app, a Chromecast (`Cast`) or `Downloads` instead.
- `tools/engine-test.mjs` tests the engine, and `tools/engine-contract-test.mjs` checks the plugin, `dxbi-tv.js` and the bridge still offer what the phone screens rely on.

## Lumen for Android phones and the Pixel Fold

The `lumenphone` product flavor (`com.harrythedingle.lumen`, released as `lumen-phone.apk`) is Lumen's phone app: the iPhone and iPad app's tabs and look (`ios/Dxbi`), for Pixel phones and the Pixel Fold, over the same hidden Stremio page. Its screens are in `app/src/lumenphone/` (Compose, black and white like Apple TV: `Theme.kt`, `Cards.kt`, and one file per screen).

- It shares the phone app's player and services with dx.bi for phones rather than copying them: `app/src/phonecommon/` (`PhonePlayer`, `PhoneControls`, `PlayerGestures`, `Downloads`, `Cast`, `ExternalPlayer`, `PhoneSupport`, and the manifest entries for them) is compiled into both `mobile` and `lumenphone`. dx.bi's own screens (`src/mobile/…/phone/`) stay dx.bi's. Each flavor has its own `Phone` object (see `PhoneHooks.kt`).
- The layout follows the window, never the device (`Adaptive.kt`): a phone, or the Fold folded, gets a tab bar along the bottom; the Fold opened out (or any window about 600 dp wide or more) gets the tabs down the side. Folding and unfolding doesn't restart the page: `MainActivity` handles those configuration changes itself.
- It keeps the status and gesture bars (`BuildConfig.IMMERSIVE` is false), draws edge to edge, and uses predictive back.
- Released builds update themselves from this repository's releases (`lumen-phone.apk`, signed with Lumen's key like `lumen-tv.apk`: `.github/workflows/lumen-tv.yml` builds both). Test builds install as `com.harrythedingle.lumen.dev`, beside the family's copy, and don't update themselves.

```sh
cd tv
./gradlew assembleLumenphoneDebug      # app/build/outputs/apk/lumenphone/debug/app-lumenphone-debug.apk (com.harrythedingle.lumen.dev)
./gradlew assembleLumenphonePreview    # release-fast, debug-signed: the one to judge speed with, on a phone
./gradlew assembleLumenphoneRelease    # signed with the release key when tv/keystore.properties exists (see below)
adb install -r app/build/outputs/apk/lumenphone/debug/app-lumenphone-debug.apk
```

## Building

Needs JDK 17 or 21 and the Android SDK (platform 35).

```sh
cd tv
./gradlew assembleTvDebug          # dx.bi TV: app/build/outputs/apk/tv/debug/app-tv-debug.apk
./gradlew assembleMobileDebug      # the phone app: app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
./gradlew assembleTvRelease assembleMobileRelease   # signed when tv/keystore.properties (or the DXBI_TV_* variables) exist
adb install -r app/build/outputs/apk/tv/debug/app-tv-debug.apk
```

The phone app also has `assembleMobilePreview`: a release build (as fast, shrunk) signed with the debug key, for trying it on a phone. Its test builds install as `com.notdxbi.dxbi.mobile.dev`, next to the released app, and don't update themselves.

Debug builds can be inspected from `chrome://inspect` on a computer. `tv/keystore.properties` (not in git) points at the release key:

```
storeFile=/path/to/dxbi-tv-release.jks
storePassword=…
keyAlias=dxbi-tv
keyPassword=…
```

Keep that key safe: every update must be signed with the same one, or Android won't install it over the old version. Publishing a release builds `dxbi-tv.apk` and `dxbi-mobile.apk` on GitHub when the key is in the repository's secrets (see `.github/workflows/tv.yml`). The phone app is signed with the same key; as a different app, it updates separately.

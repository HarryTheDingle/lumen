# Lumos

A personal app for watching at home: Lumen's screens on a native engine, with no Stremio app inside.

- `engine/`: Kotlin Multiplatform. Talks to addons over HTTP (manifest, catalogs, details, streams) and to TorBox directly
  (TorBox's own search plus cached-torrent playback). Builds for the JVM (tests, and Android TV next) and as
  `LumosEngine.framework` for iPad, iPhone and Mac.

Build and test (JDK 17):

    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :engine:jvmTest            # -Dlive=true also times Cinemeta
    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :engine:linkDebugFrameworkIosSimulatorArm64

- `sync/`: Lumos's own sync server, a Cloudflare Worker on the existing `lumen-sync` D1 database (it only adds
  tables). Accounts, signing a TV in with a code approved from another device, and per-profile resume points,
  watched and My List, merged title by title. `/import/stremio` brings Lumen's profiles and Stremio's history across once.

      node sync/test.mjs                 # the Worker's checks, on Node's SQLite
      node sync/serve.mjs                # the Worker on this Mac (setup phrase "local-setup-phrase")
      ./gradlew :engine:jvmTest -Dsync=http://localhost:8787   # the engine signing in and syncing against it

  Deploying (once): `npx wrangler d1 execute lumen-sync --remote --file sync/schema.sql`, then
  `npx wrangler secret put LUMOS_SETUP` (a phrase only you know) and `npx wrangler deploy` in `sync/`.

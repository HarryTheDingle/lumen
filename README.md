<p align="center">
  <img src="graphics/Lumen%20Logo%20transparent.png" alt="Lumen" width="320">
</p>

<p align="center">
  <b>A Netflix-style, Apple TV-clean app for your Stremio library.</b><br>
  Sign in with your Stremio account. Your addons, library and Continue Watching come with you.
</p>

## What it is

Lumen is a personal app for watching with Stremio's addons (including debrid services like TorBox), made for the
screens at home. It's not a streaming service, it hosts and provides no content, and it isn't affiliated with
Stremio, Netflix or Apple.

| Where | What |
|---|---|
| iPad, iPhone and Mac | Native SwiftUI app: Home with a swiping billboard, Discover by streaming service and genre, title pages, search, Library, downloads and its own player (`ios/`) |
| Android TV and Google TV | Native screens worked by remote: Home, Discover, genres, title pages, Search and a remote-friendly player (`tv/`, the `lumen` flavor) |

Both apps use the same engine underneath: Stremio's web app running out of sight, with the plugin at the repository root
(`dxbi.plugin.js`) doing the work of picking streams, tracking progress and syncing.

## Installing

- **Android TV / Google TV:** the latest release's `lumen-tv.apk`. Once installed, the app offers each new release itself
  (allow *Install unknown apps* for Lumen the first time).
- **iPad, iPhone and Mac:** build from `ios/` (see [ios/README.md](ios/README.md)).

## Building

- TV: [tv/README.md](tv/README.md) (`./gradlew assembleLumenRelease`).
- Apple devices: [ios/README.md](ios/README.md).

## Credits and licence

Lumen is built on [dx.bi](https://github.com/notdxbi/dxbi-stremio), a free Stremio front end, and on the work of its
contributors. Both are released under the GNU General Public License v3 or later: see [LICENSE](LICENSE) and
[NOTICE.md](NOTICE.md) for the copyright notices and the other projects' licences that come with it.
dx.bi's own documentation is kept in [docs/dxbi](docs/dxbi).

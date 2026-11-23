# Lumen 3 IPTV UI Handoff for Claude

All non-visual IPTV infrastructure is built, isolated on branch `feat/iptv` inside `/Users/harryelliott/Developer/Lumen/Lumos-iptv`, and verified green. The backend, multi-source parser, UK EPG guide service, catch-up resolver, multi-provider channel normalizer, Media3 live player engine, failover loop, and sync state are ready.

Your job in Claude Code is designing and building the Apple TV-clean Android TV visual experience on top of these stable contracts.

## What is already built and verified

1. **Shared KMP IPTV engine (`engine/src/commonMain/kotlin/lumos/iptv/`).**
   - Supports all three IPTV formats (`IptvSource.Xtream`, `IptvSource.M3u`, `IptvSource.Stalker`), merging multiple accounts into unified `CanonicalChannel` records.
   - Prevents TiViMate and Smarters Pro bugs at the boundary (`CanonicalChannel` enforces non-blank names, `UkChannelRegistry` maps 200+ UK/IE/US canonical channels and separates `+1` channels, `ChannelNormalizer` collapses duplicates and extracts `StreamQuality` tags like `4K · 50fps`, `StreamRanker` picks the best stream for TV display height and measured bandwidth, and `StreamHealthTracker` puts failing streams on exponential backoff cooldown).
   - Supports Catch-up across Xtream (`timeshift`), M3U (`default`, `append`, `shift`, `flussonic`, `xc`), and Stalker (`create_link` archive playback).
   - Provides `EpgStore` with Now/Next, timeline windows, `catchupProgrammes`, `liveSportsNow`, and unified `search` across channels, currently airing shows, upcoming shows, and catch-up replays.
2. **UK EPG and QR sync backend (`sync/`).**
   - Additive D1 tables in `sync/schema.sql` (`lumos_epg_channels`, `lumos_epg_programmes`, `lumos_iptv_setup`).
   - `sync/epg.js` fetches UK TV guide schedules on the 15-minute cron (supporting Freeview UK JSON and optional `UK_EPG_XMLTV_URL`), retains a rolling catch-up + upcoming window, detects live sports, and serves `POST /epg/uk`.
   - `sync/iptv-setup.js` serves a mobile-friendly setup page at `GET /iptv?code=XXXX-XXXX` so users type or paste Xtream, M3U, and Stalker credentials on a phone instead of a TV remote.
   - `sync/worker.js` allows `iptvSources` and `iptvPrefs:<viewer>` in `/sync`.
3. **Android TV Live Player and State Store (`tv/src/main/java/com/notdxbi/dxbi/tv/live/`).**
   - `LiveExoEngine.kt` wraps Media3 `1.5.1` with FFmpeg software audio fallback, low-latency live buffering (`800ms` startup, `1800ms` rebuffer), HLS/DASH/raw MPEG-TS support, synchronous single-connection teardown (`releaseCurrentSync()`) before tuning a new stream, and a 6-second stall/freeze failover watchdog.
   - `LivePlayerActivity.kt` handles automatic multi-feed failover, hardware `KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN` zapping, catch-up seek/pause, and mounts a Compose `LivePlayerOverlay`.
   - `LiveTvStore.kt` (`LiveTvRuntime.store(context, engine)`) loads lazily only when Live TV or Settings -> Live TV opens, restores warm disk caches immediately, syncs sources/prefs per profile, detects 4K HDMI modes via `DisplayManager`, and drives QR phone pairing polling.
   - `LogKeeper.kt` scrubs IPTV usernames, passwords, URLs, and MAC addresses from problem reports.

## File ownership boundaries

### Files you own (safe to redesign or split into new files)

- `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/live/LiveTvScreen.kt` (replace the placeholder shell with the full Apple TV-clean Live TV Hub, Live Sports strip, spacious UK TV Guide grid, Catch-up archive browser, Search view, and QR setup flow; you may add sibling UI files inside `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/live/`).
- `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/live/LivePlayerOverlay.kt` (redesign the Live Player OSD, Now/Next timeline bar, Catch-up scrubber, `Feeds` switcher drawer, `Audio & Subtitles` drawer, `Mini-Guide` zapper, and `Unavailable` error screen).
- `SettingsScreen.kt` (`liveTvSectionItems` only, if visual adjustments are needed) and `sync/iptv-setup.js` (`renderSetupPage` HTML/CSS only, if you want to match Lumen's branding tighter).

### Files you must NOT modify

- `tv/src/main/java/com/notdxbi/dxbi/tv/PlayerActivity.kt` (VOD player)
- `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/HomeScreen.kt` (VOD home)
- `tv/src/shared/java/com/notdxbi/dxbi/tv/phone/Stremio.kt` and VOD methods in `Engine.kt`
- `engine/src/commonMain/kotlin/lumos/iptv/*` (unless fixing a verified parser/engine bug)
- `tv/src/main/java/com/notdxbi/dxbi/tv/live/LiveExoEngine.kt`, `LiveTvStore.kt`, and `LivePlayerActivity.kt` (core playback and state contracts)
- `sync/epg.js`, `sync/worker.js`, `sync/schema.sql`

## Contracts you consume

### `LiveTvRuntime` and `LiveTvStore` (`com.notdxbi.dxbi.tv.live`)

Obtain the lazy singleton inside Compose with `val store = remember { LiveTvRuntime.store(context, engine) }` and trigger `LaunchedEffect(Unit) { store.ensureLoaded() }`.

**Exposed `StateFlow` properties.**

- `uiState: StateFlow<LiveUiState>` (`loading`, `refreshing`, `error`, `epgUpdatedAtMs`, `lastLoadedAtMs`)
- `channels: StateFlow<List<CanonicalChannel>>` (visible, deduplicated, ranked channels)
- `categories: StateFlow<List<Pair<ChannelCategory, Int>>>` (non-empty categories in display order)
- `favourites: StateFlow<List<CanonicalChannel>>`
- `recents: StateFlow<List<CanonicalChannel>>`
- `liveSportsNow: StateFlow<List<Pair<CanonicalChannel, EpgProgramme>>>`
- `sources: StateFlow<List<IptvSource>>`
- `sourceReports: StateFlow<List<SourceLoadReport>>` (`success`, `streamCount`, `error`, `accountInfo`)
- `prefs: StateFlow<IptvProfilePrefs>` (`favourites`, `hiddenChannels`, `recentChannels`, `preferredStreamByChannel`)
- `setupPairing: StateFlow<QrSetupPairing?>` (`code`, `setupUrl`, `qrImageUrl`, `expiresAtMs`)
- `epgVersion: StateFlow<Long>` (increments whenever guide data updates)

**Methods on `LiveTvStore`.**

- `ensureLoaded()`
- `refreshAll(forceEpg: Boolean = false)`
- `startQrSetup()` / `stopQrSetup()`
- `toggleFavourite(channelId: String)`
- `hideChannel(channelId: String)`
- `pinPreferredStream(channelId: String, streamId: String?)`
- `nowAndNext(channel: CanonicalChannel, nowMs: Long = System.currentTimeMillis()): Pair<EpgProgramme?, EpgProgramme?>`
- `programmesInWindow(channel: CanonicalChannel, fromMs: Long, toMs: Long): List<EpgProgramme>`
- `catchupFor(channel: CanonicalChannel, nowMs: Long = System.currentTimeMillis()): List<EpgProgramme>`
- `search(query: String, nowMs: Long = System.currentTimeMillis()): LiveSearchResults`
- `playLive(context: Context, channel: CanonicalChannel, streamId: String? = null)`
- `playCatchup(context: Context, channel: CanonicalChannel, programme: EpgProgramme)`

### `LivePlayerOverlay` contracts (`com.notdxbi.dxbi.tv.live`)

`LivePlayerActivity` passes two arguments into `LivePlayerOverlay(state: LiveOverlayState, actions: LiveOverlayActions)`:

- **`LiveOverlayState` fields.** `channel: CanonicalChannel`, `playbackState: LivePlaybackState`, `nowProgramme: EpgProgramme?`, `nextProgramme: EpgProgramme?`, `isCatchup: Boolean`, `catchupProgramme: EpgProgramme?`, `osdVisible: Boolean`, `activePanel: LiveOverlayPanel` (`NONE`, `FEEDS`, `AUDIO_SUBTITLES`, `MINI_GUIDE`), `streams: List<StreamSource>`, `activeStreamId: String?`, `pinnedStreamId: String?`, `videoInfo: LiveVideoInfo` (`width`, `height`, `fps`, `hdrLabel`, `audioCodec`), `audioTracks: List<LiveTrackOption>`, `subtitleTracks: List<LiveTrackOption>`, `isBuffering: Boolean`, `isPaused: Boolean`, `positionMs: Long`, `durationMs: Long`, `soundModeLabel: String`, `zoomMode: LiveZoomMode`, `miniGuideChannels: List<Pair<CanonicalChannel, EpgProgramme?>>`.
- **`LiveOverlayActions` callbacks.** `onShowOsd`, `onHideOsd`, `onOpenPanel(panel)`, `onClosePanel`, `onSelectStream(stream)`, `onPinStream(streamOrNull)`, `onSelectAudio(groupIdx, trackIdx)`, `onSelectSubtitle(groupIdx, trackIdx)`, `onDisableSubtitles`, `onTogglePause`, `onSeekBy(deltaMs)`, `onCycleSoundMode`, `onCycleZoom`, `onZapChannel(channel)`, `onRetry`, `onBack`.

### `lumos.iptv.*` Domain Models (`engine/.../IptvModels.kt`)

- `CanonicalChannel`: `id`, `number: Int?`, `name`, `category: ChannelCategory`, `logoUrl: String?`, `epgId: String?`, `streams: List<StreamSource>`, `isEventChannel`, `hasCatchup`, `maxCatchupDays`.
- `StreamSource`: `id`, `sourceId`, `sourceName`, `rawName`, `quality: StreamQuality` (`height`, `fps`, `codec`, `hdr`, `isRaw`, `isBackup`, `region`, computed `badge`), `catchup: CatchupConfig?`.
- `EpgProgramme`: `id`, `channelId`, `title`, `subtitle`, `description`, `category`, `startMs`, `endMs`, `iconUrl`, `isLiveSport`, `sportTag`, `hasCatchup`, plus `isOnAt(nowMs)`, `progressAt(nowMs)`, `remainingMinutesAt(nowMs)`, `isCatchupEligible(nowMs, catchupDays)`.
- `LiveSearchResults`: `channels`, `onNow: List<Pair<CanonicalChannel, EpgProgramme>>`, `upcoming: List<Pair<CanonicalChannel, EpgProgramme>>`, `catchup: List<Pair<CanonicalChannel, EpgProgramme>>`.

## Lumen UI design tokens and components to reuse

Mirror the exact Apple TV visual grammar already in `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/`:

1. **Theme tokens (`Theme.kt`).**
   - `Dx.Background` (`Color.Black`)
   - `Dx.Raised` (`Color(0xFF1C1C1E)`)
   - `Dx.Accent` (`Color.White`), `Dx.OnAccent` (`Color.Black`)
   - `Dx.Blue` (`Color(0xFF2F8BFF)`) for live/catch-up progress bars (`ProgressLine`) and active indicators
   - `Dx.Amber` (`Color(0xFFFFB547)`) for catch-up / sports accents where appropriate
   - `Dx.Muted` (`62% white`), `Dx.Faint` (`40% white`), `Dx.Gutter` (`48.dp` TV-safe margin)
   - Reusable composables in `Theme.kt`: `Badge(text, colour)`, `ProgressLine(value, modifier)`, `Modifier.skeleton(on, shape)` (uses shared `SkeletonClock`).
2. **Focus and glass surfaces (`Cards.kt`, `Glass.kt`).**
   - `FocusCard(onClick, modifier, corner, onLongClick, content)` gives the exact `1.13x` spring lift and `Modifier.glassEdge(..., tracked = focused)` rim reflection without the overhead of Compose TV `Card`.
   - `ScreenTag(nav)` sits in the top-left corner so moving Up/Left opens `SideMenu`.
   - `Pill(text, selected, onClick, modifier, tint, leading, compact)` for category tabs and time-jump chips (`Now`, `Tonight 8pm`, `Tomorrow`).
   - `ActionMenu(heading, actions, onDismiss)` and `PageWindow(onBack, dim, home, ...)` for long-press menus (Pin Favourite, Catch-up Replay, Switch Default Feed, Hide Channel).
   - `Field(...)` in `LumenApp.kt` for remote-friendly TV search input (`Search` mode).
3. **D-pad navigation exemplars.**
   - Read `CalendarScreen.kt` (two-pane / grid D-pad navigation, Back returning to header filters before exiting tab, `FocusRequester` management) and `SettingsScreen.kt` (`KeepInView` `BringIntoViewSpec`, white-pill row styling).

## Design targets and Mi Box performance guardrails

Fix every complaint from TiViMate and Smarters Pro:

1. **Apple TV-clean Live Hub (landing screen before the full grid).**
   - Top header row with `ScreenTag(nav)`, mode switcher pills (`Hub`, `TV Guide`, `Catch-up`, `Search`, `Add Source`), and `Refresh`.
   - **Continue / Recent & Favourites row.** Wide 16:9 `FocusCard` tiles showing channel logo, clean channel number + name, current programme title, remaining minutes, quality `Badge`, and `ProgressLine` (`Dx.Blue`). Long-press opens `ActionMenu` (Play, Catch-up, Pin Favourite, Hide Channel).
   - **Live Sports Now row.** Highlighted cards for matches and races currently airing (`liveSportsNow`), showing sport tag (`Football`, `Motorsport`, `Boxing`, `Cricket`, etc.), fixture title, channel name, and time remaining.
   - **Category pills + clean Channel browser.** Instant category switching (`UK TV`, `Entertainment`, `Sports`, `Movies`, `News`, `Documentaries`, `Kids`, `Music`, `Live Events`).
2. **Spacious UK TV Guide (EPG grid).**
   - **Top hero band (~28% height).** Shows the focused channel logo, number, name, quality badge, current programme title, start/end times, `ProgressLine`, description, next programme preview, and Catch-up availability pill. Keep live video preview off on single-connection sources (`maxConnections == 1`) so tuning never triggers provider connection kicks.
   - **Time-jump bar.** Pills for `Now`, `+2h`, `Tonight 8pm`, `Tomorrow`, and `-24h Catch-up` so users never tap Right 40 times.
   - **Tall, readable timeline rows (64dp to 72dp tall).** Left pinned channel column (`220dp` width) with clean channel logo and name. Horizontal 2-hour or 3-hour visible window rendered inside a 4 to 6 hour slice, with a crisp vertical `Dx.Blue` "Now" marker line. Selecting a live show plays immediately; selecting a past finished show on a catch-up-capable channel launches `playCatchup`; long-pressing opens `ActionMenu`.
3. **Catch-up Replay Drawer / Modal.**
   - Group finished programmes by day (`Today`, `Yesterday`, `2 days ago`, etc.) so finding last night's match or show takes seconds.
4. **Live Player OSD and Mini-Guide (`LivePlayerOverlay.kt`).**
   - Translucent dark frosted panels (`Color(0xFF141418).copy(alpha = 0.92f)`), white pill buttons, `Dx.Blue` live progress bar, `Dx.Amber` catch-up scrub bar (`-30s`, `Pause/Resume`, `+30s`), `Feeds` drawer showing source name + quality badge + RAW/Backup tags + `Pin as Default`, and left-edge `Mini-Guide` for fast channel surfing while watching.
5. **Mi Box performance guardrails.**
   - Avoid per-frame recomposition in scrolling grids. Compute `nowMs` on a 15-second or 30-second ticker, not inside item draw blocks.
   - Use `FocusCard` (or `Modifier.glassEdge(..., tracked = focused)`) rather than Compose TV `Card`, matching the optimization documented in `Cards.kt`.
   - Always give `LazyColumn` and `LazyRow` stable keys (`ch.id`, `"${ch.id}:${prog.startMs}"`).
   - Guard D-pad focus transitions when switching categories or closing modals so focus never drops to nothing or gets trapped offscreen.

## Verification commands

Run these after your UI edits from `/Users/harryelliott/Developer/Lumen/Lumos-iptv`:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :engine:jvmTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :tv:compileLumen3LumenDebugKotlin :tv:assembleLumen3LumenDebug
node sync/test.mjs && node sync/test-epg.mjs
git diff --stat v3
```

Verify on your Mi Box over `adb` once built:

```bash
adb install -r tv/build/outputs/apk/lumen3Lumen/debug/tv-lumen3-lumen-debug.apk
```

Confirm:
- Existing VOD Home, Discover, Calendar, and `PlayerActivity` behave identically to `v3`.
- Opening `Live TV` lands on `Hub`, navigates smoothly via D-pad through `TV Guide`, `Catch-up`, `Search`, and `QR Setup`, and launches `LivePlayerActivity` cleanly.
- Switching channels releases the prior stream before tuning the next, and broken feeds fail over cleanly without freezing the player.

## Deploying `sync/` updates to Cloudflare (when ready)

When you are ready to push the new UK EPG and QR IPTV setup routes live to `lumos-sync.hedigital.workers.dev`:

```bash
cd /Users/harryelliott/Developer/Lumen/Lumos-iptv/sync
npx wrangler d1 execute lumen-sync --remote --file schema.sql
# Optional if you have a curated UK XMLTV feed URL:
# npx wrangler secret put UK_EPG_XMLTV_URL
npx wrangler deploy
```

## Starter prompt for Claude Code

Run `claude` inside `/Users/harryelliott/Developer/Lumen/Lumos-iptv` and paste this:

> Read `IPTV_CLAUDE_HANDOFF.md`, `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/Theme.kt`, `Glass.kt`, `Cards.kt`, `Nav.kt`, `CalendarScreen.kt`, and `SettingsScreen.kt` first.
>
> All IPTV backend, KMP engine, UK EPG, sync, `LiveTvStore`, `LiveExoEngine`, and `LivePlayerActivity` systems are already built and verified on branch `feat/iptv`. Your task is designing and implementing the Apple TV-clean Android TV UI in `tv/src/lumen/java/com/notdxbi/dxbi/tv/lumen/live/LiveTvScreen.kt` (splitting clean UI subfiles in `lumen/live/` as helpful) and `LivePlayerOverlay.kt`.
>
> Do NOT modify `PlayerActivity.kt`, `HomeScreen.kt`, `Stremio.kt`, `LiveExoEngine.kt`, `LiveTvStore.kt`, `LivePlayerActivity.kt`, `engine/`, or `sync/` (except optional HTML polish in `sync/iptv-setup.js`).
>
> Build:
> 1. An Apple TV-style Live Hub (Recent/Favourites wide cards with glass rims and `Dx.Blue` progress bars, Live Sports Now strip, Category pills, Catch-up badge).
> 2. A spacious UK TV Guide grid (hero preview band on top, time-jump pills including Catch-up/Now/Tonight/Tomorrow, tall 64-72dp rows with `Dx.Blue` Now marker, long-press `ActionMenu`).
> 3. A day-grouped Catch-up browser and unified Live Search (channels, live now, upcoming, catch-up).
> 4. Polished Live Player OSD, Catch-up transport bar, Feeds switcher drawer, Audio/Subtitles drawer, and Mini-Guide channel zapper in `LivePlayerOverlay.kt`.
>
> Verify every change compiles cleanly with `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :tv:compileLumen3LumenDebugKotlin :tv:assembleLumen3LumenDebug` and keep Mi Box performance smooth (stable keys, no per-frame allocations, `FocusCard` instead of Compose TV `Card`).

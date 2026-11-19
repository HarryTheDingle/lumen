# Porting Lumen to Lumos

What Lumen (dxbi-stremio/ios, and the plugin under it) does, and where Lumos is with it. Order is the order of work.

## Done
- Sign-in and sync (own server), Stremio history and addons import, Stremio kept in step while Lumen's used
- Home rows, Continue Watching, title page with seasons and episodes, streams list, search, settings
- Player basics (Lumen's mpv, play/pause, ±10 s, scrubber, text subtitles), resume and progress sync

## How: Lumen's own screens, unchanged

Lumen's design is kept exactly. Lumen's iOS app (dxbi-stremio/ios/Dxbi: UI/, Player/, Engine/Models.swift, App.swift,
Assets) is copied into Lumos as it is. Only the layer under the screens changes: Engine/Stremio.swift (and Page.swift,
the hidden web page) are replaced by a Lumos version with the same properties and calls (about 130: rows, billboard,
continueWatching, details, launch, play, viewers, library, setWatched, discover, picks…), answered by the Kotlin
engine instead of Stremio's web app. Models.swift reads the same JSON shapes, so the engine hands back what the plugin
did. Lumos's own stand-in screens (ios/Lumos/*View.swift) go once Lumen's are in.

Features the plugin did that the engine doesn't yet show as empty (no row, no button) until they're ported.

## Batch 1 (done 2026-11-17)
- [x] Lumen's iOS app copied in unchanged (UI/, Player/, Models, App, Assets); Lumen's Stremio.swift kept, with only progress
      and player-closed reporting changed; ios/Lumos/Engine/Page.swift answers the page's requests from the engine
- [x] Engine phone API (engine/.../Phone.kt): board, Continue Watching, title details with resume and next episode,
      streams as addons answer, Lumen's stream description (Describe.kt), Just press Play pick, My List, profiles
- [x] Lumos sign-in in Lumen's welcome style (LumosWelcome), Profile → Lumos settings (TorBox, addons, devices, Stremio)
- [x] Checked on the iPhone: playing works (2026-11-17)

## Batch 2 (done 2026-11-17, to check on the iPhone)
- [x] Skip Intro / Recap / credits (TheIntroDB, AniSkip for anime: engine Skips.kt), Up Next card with the next episode's picture
- [x] Subtitles from subtitle addons and the stream's own (up to three per language, English first)
- [x] Player's Streams button (Lower resolution, Lighter, HDR / Not HDR, All streams), lighter streams offered when it buffers,
      seek-bar previews from a light stream, the check before Play (streams that aren't videos passed over)
- [x] Logo: the Lumos logo where Lumen's wordmark was, the wand as the app icon (gold wave animation still to come)
- [ ] Settings for these (Auto-skip, Up Next seconds, subtitle language): fixed at Lumen's defaults until Lumen Settings is ported

## Next
- [ ] Lumen's own Home rows (For You, Because you watched, Top 10), trailers, person pages, More Like This
- [ ] Discover (Stremio's catalog filters: types, catalogs, genres, next page)
- [ ] Profiles: add, rename, recolour, remove (synced viewers)
- [ ] Settings Lumen kept in the plugin (Lumen Settings, Playback), Customise Home (layout)
- [ ] Match my internet speed (speed check, networks), AI-upscaled streams never picked
- [ ] Discover: streaming services, genres, franchises, Genius Browse
- [ ] Taste and recommendations (Tune Suggestions, picks via the sync server's AI), Ask AI search, ratings
- [ ] Downloads, play in another app, watch party, reviews, stats
- [ ] Android TV app (Lumen's TV screens on the same engine)
- [ ] Branding: gold wave title animation

# Lumos TV (Android TV), started 2026-11-17

Harry's call (2026-11-17): the TV app first; the Lumos iPhone app is paused (Lumen on iOS works well).
Lumen TV's screens and player (dxbi-stremio/tv/app/src/main, shared, lumen) are copied into tv/src unchanged, except:
- shared/.../phone/Engine.kt: native, answers Lumen's page calls from the Lumos engine (twin of ios Page.swift)
- main/.../MainActivity.kt: no WebView, just the screens' window; Bridge.kt and ServerFinder.kt gone; TorrentEngine.kt a stub
  (no libtorrent: torrents play through TorBox)
- lumen/.../Phone.kt attach/progress/closed; lumen/.../LumosSignIn.kt (code sign-in, Stremio link import); LumenApp.kt's
  sign-in branch; the logo, banner and launcher icon are Lumos's
App id com.harrythedingle.lumos.tv (code packages stay Lumen's so the copies are exact). Build: ./gradlew :tv:assembleLumenDebug

## Phase 1 (done 2026-11-17)
- [x] Stream matching: a new title's streams never mix with the last one's (the iPhone's wrong-show streams); streams
      naming another episode or another year's film dropped (Matching.kt); TorBox picks a pack's file by the addon's file
      name, then the episode tag, never a guess (it could play another episode); anime episode read right
- [x] TV app builds and starts on the Mi Box: engine ready in 1.2 s (Lumen TV: 5–7 s); sign-in code from the sync server
- [x] Signed in on the Mi Box (code approved on the iPhone), StremThru + Easynews brought across with Stremio's link code

## Phase 2 (done 2026-11-17, Mi Box .81, release builds)
- [x] Profiles (synced Harry, Bob & Bet), Home in Lumen's design with the synced row order, Continue Watching, My List,
      Genius Browse, Top 10 rows; title page with resume, seasons, episodes and the 4K / Dolby Vision / Atmos badges
- [x] Lumen's synced taste and Home layout read (phone.taste, phone.layout); no "What do you like" for a profile with history
- [x] Stremio session key no longer written to the TV's log (Lumen's link code logged the answer)
- Measured, same box and presses: release APK 8.6 MB (Lumen TV 20 MB); start 0.84 s, engine ready 0.28 s (Lumen 5–7 s);
  janky frames 5.4% (Lumen 12.8%), 90th percentile frame 32 ms (48 ms); memory 133 MB (Lumen 213 MB + 235 MB web page)
- [ ] Search not tried yet (typing by remote)

## Phase 3 (done 2026-11-17, Mi Box .81, on a throwaway "Test" profile, since removed)
- [x] Resume through StremThru (TorBox): 4K Dolby Vision; place on the sync server the moment the player closes
- [x] Skip Intro (Breaking Bad S1E1, TheIntroDB 3:48–4:06): button shown in the intro, skips to its end
- [x] Seek-bar previews from the lightest stream; scrubbing
- [x] Player's Streams list (fixed: Lumos now gives each stream a Stremio-style player address, which the player reads),
      switching stream in place at the same second
- [x] Up Next at the credits: card with the next episode's picture and countdown, next episode started by itself
- [x] Progress to Stremio: main profile only (fixed: other profiles' watching went to Harry's Stremio library); "told: true"
- [x] Profiles: add, rename, recolour, remove ported (viewers.*), synced
- [x] Fixed: billboard My List marks were the last profile's; Up Next offered episodes not out yet; stream labels now
      Lumen's ("4K · Dolby Vision · Blu-ray")
- Timings: Play from a title page to picture 3.4 s; Resume from Home (streams not loaded yet) 7.9 s; addons answer in
  2.1 s (StremThru) and 3.0 s (Easynews)
- [x] Match my internet speed (2026-11-17, engine Speed.kt, Lumen's netSpeed/netMemory): Cloudflare speed test every
      10 min (never waited for once there's a reading), speed per network by address range, learnt from the player filling
      and buffering; picks fit 75% of it (else the lightest); per-network quality cap; Settings → Networks works.
      Mi Box: 30.9 Mbit/s measured, Dune picked a 4K HDR Blu-ray at 22.5 Mbit/s instead of a 100+ Mbit/s remux
- [x] Only streams chosen by hand are remembered for a title (Lumos had kept every one played, pinning titles); old ones dropped
- [x] Settings (2026-11-17): the 17 of Lumen's 72 settings that mean something on a TV (LumosSettings.kt, Lumen's wording
      and defaults, kept on the TV), applied by the engine and the player. New screen (SettingsScreen.kt): sections down the
      left (Playback, Subtitles, Episodes, Player, Home, Profiles & Account, Addons, About), the focused one's settings on the
      right without pressing OK, Right/Left/Back between them; choices open a ticked list in place (Lumen cycled through them
      with OK); help under the list; Apple TV app's look (pill rows, capitals headings, chevrons). Addons can be removed,
      Stremio kept in step stopped, brought again. Preferred addon picked from your addons rather than typed.
      Subtitles when a video starts: Off (Lumen TV's way) or On in English (the player now honours it)
- [ ] Back on Lumen's New Profile screen offers to leave the app (check Lumen TV does the same)
- [ ] "Accounts" on Who's watching is Stremio's account switcher: hide or make it Lumos's
- [ ] Lumen features still to port (For You/Top Picks, Discover catalogs, settings, ratings, Ask AI, party…): as in "Next"
- [ ] Release signing key for Lumos, and self-update (Lumen's own channel, own key)

## Home rebuild (started 2026-11-17)
- [x] sync/lists.js: Lumos's own Home lists on the Worker (TMDB trending, Top 10, new to stream, popular on Netflix/Prime/
      Disney+/Apple TV+/NOW/iPlayer, in cinemas, coming soon, top rated; IMDb Top 250 via MDBList), matched to IMDb ids,
      kept in D1 (tables in schema.sql), cron every 15 min (wrangler.toml), POST /lists and /lists/refresh. Tested locally:
      LUMOS_TMDB_KEY=… node sync/try-lists.mjs
- [ ] Deploy: apply schema.sql to D1, `npx wrangler secret put LUMOS_TMDB_KEY` (+ MDBLIST_KEY, Harry), `npx wrangler deploy`
- [ ] Check the MDBList lists pick the real IMDb Top 250 once the key is set
- [ ] Engine reads /lists; TV Home in the new order (billboard from picks + new, no repeats); then AI Top Picks (/picks)

## 2026-11-19: last features before replacing Lumen, and a full test pass (Mi Box .81)
- [x] Problem reports: LogKeeper.kt keeps the app's own log on the TV (two 2 MB files, picks up where it stopped, so a
      crash's lines are kept); Settings → About → Send a Problem Report, and an offer after a crash, send the last 30 minutes
      (links cut to their host, keys/tokens/long codes removed) to the sync server's /logs. The app shows a 6-letter
      reference; read it with `node sync/logs.mjs [REF]` (reports kept 30 days, in sync/reports/, git-ignored)
- [x] Top Picks and Because you watched/liked: sync/picks.js on the sync server (Lumen's recommender: weights, TMDB
      recommendations, scoring, then Lumen's AI prompt via Cloudflare AI, or Gemini with GEMINI_API_KEY), kept 12 h per
      profile; finishes over a few calls (50-request budget). Try locally: `node sync/try-picks.mjs entries.json main`
- [x] Stremio kept in step for whole-title watched marks and My List too (StremioBridge.watched/library; episodes'
      packed marks left alone)
- [x] Self-update from the sync server: Updates.kt reads UPDATE_FEED (release.json + lumos-tv.apk in sync/public/update/tv,
      served as Worker assets). Release: tv/make-release-key.sh once (then back up tv/lumos-release.jks + tv/keystore.properties),
      then sync/release-tv.sh 0.2.0 "- notes". Switching the Mi Box from the debug key needs an uninstall and a new sign-in
- [ ] Deploy the sync Worker (/logs, /picks, AI binding, update assets) — waiting for Harry's OK
- Test pass fixes: Accounts hidden on Who's watching (Stremio's switcher, and Back didn't leave it); white accent instead
  of cyan (taste screen buttons); title page no longer fades under the Rate box; player ⋯ fades the title while its buttons
  are out; episodes marked watched move Continue Watching on; thin episodes (new shows) get TMDB stills and descriptions
- Still open from the pass: title page Information is thin (phone.facts); Search's Left from results opens the menu rather
  than going back to the keyboard; the remote's text input (adb/keyboards) isn't taken by Search's own keyboard

## 2026-11-19 (later): Lumen 3, Stremio sign-in, sync fixes, card labels
- Builds: `:tv:assembleLumosLumenRelease` (Lumos, next to Lumen) and `:tv:assembleLumen3LumenRelease` (Lumen 3.0.0:
  Lumen's id com.harrythedingle.lumen.tv and key, Lumen's icon/logo from src/lumen3/res, updates from Lumen's GitHub
  releases). Add -PdebugSigned for the Mi Box's debug Lumos. Lumen 3 installs over Lumen 2 (checked on the Mi Box) and
  clears Lumen 2's web page storage on first start
- Sign in with Stremio (/signin/stremio, lumos_stremio table): a Stremio account links to its Lumos account; a Lumen user's
  own Stremio account gets a new Lumos account with its Lumen profiles and history (Harry's dad: Mark, Diane)
- Sync: Up Next (next episode at 0) stays in Continue Watching; finishing/marking an episode moves the show on; finishing
  marks the episode, not the show; Continue Watching's Remove rewinds the title in Stremio too
- Not Interested in the hold menu (hidden:<profile>), left out of picks and given to the AI as dropped
- Card labels (sync/labels.js, /labels, lumos_facts): In Cinemas, New Movie/Show, New Season/Episode, Returning, Coming
  21 Nov, #N Today; one per card, flat dark tab centred at the top; left off where the row's name says it. Title pages use
  the same TMDB (UK-first) dates for whether a film is out. Top 10 numbers removed (Harry), ✓ moved to bottom right
- Not done: Stremio per-episode watched marks (packed field); a real Lumen 3 release (waits for Harry)

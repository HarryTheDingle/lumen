# Lumos cleanup plan (audit of 2026-11-19)

Read-only audit of about 22,000 lines (engine, TV player and bridge, TV screens, sync Worker), by five agents, with the
biggest claims checked by hand. Nothing has been changed yet.

Most of the waste has one cause: the TV app's screens and player were copied from Lumen, which drove a hidden Stremio
web page and the dx.bi plugin. In Lumos, `tv/src/shared/.../phone/Engine.kt` answers those calls natively, and
**anything it doesn't answer returns null**. So whole features are wired up but can never work, and some screens poll
for answers that never come.

## Bugs found on the way (fix, don't delete)

| What | Where | Effect |
|---|---|---|
| Ratings are never saved | `Stremio.rating/setRating` call `phone.rating` / `phone.setRating`; Engine.kt doesn't answer them | The Rate box always opens on "5 Average" and forgets what you chose; ratings never reach the picks |
| Accounts switching would hang | `Stremio.switchAccount/beginAddAccount` set `engine.ready = false`, nothing sets it back | Unreachable now (Accounts is hidden), but the buttons would freeze the app 30 s |
| `phone.forYou` polled for nothing | `Stremio.refreshExtras`, 10 tries x 1.5 s when there are no picks | 15 s of wasted calls on every Home refresh for a profile with no picks |
| Calendar never shows your shows' episodes | `CalendarScreen` uses `phone.calendar`, answered `[]` | "My Library" calendar only has films |
| Junk `undefined:0` rows in `lumos_ids` | `sync/picks.js` writes the IMDb map even when TMDB has no match | Harmless, grows slowly |

## 1. Dead code (nothing can reach it, or it can never work)

| Item | Where | Size | Evidence | Risk |
|---|---|---|---|---|
| Old web-page script | `tv/src/main/assets/dxbi-tv.js` | 65 KB | only mentioned in a comment | none |
| Plugin self-update | `Updates.kt` `pluginSource/forTv/checkPlugin` + constants | ~40 lines | plugin asset no longer exists; Lumen 3 still downloads `dxbi.plugin.js` every 6 h and writes it where nothing reads it | none (keep `isNewer`) |
| Expanding preview cards | `Preview.kt` `PREVIEW_EXPANDS = false` and everything behind it (`open`, `lit`, wide branch, `FactsLine`, `InCardButton`, `scoreMemory`, `scoreName`, `CardActions.scores`) | ~250 lines | constant is `false`, so `open` never becomes true | only if you want hover cards back (Apple TV doesn't have them) |
| Old Stremio sign-in screen | `LumenApp.kt` private `SignIn` (+ `Stremio.signIn/signInWithToken/afterSignIn`, `Engine.events`) | ~110 lines | 0 callers; `LumosSignIn` replaced it | none |
| Watch party | `PlayerActivity` `party/partyTick/control()`, `PlaybackBus.onPartyTick/onPartyLeave/player`, `PlayerControls` party UI, `ctl_party` button, `Stremio.party*` | ~100 lines | only dxbi-tv.js ever called `control()` | check the player button row's focus order after removing `ctl_party` |
| Phone / picture-in-picture hooks | `PhoneHooks.kt` `PlayerHooks/PlayerHost`, `extras?.` calls, `onPictureInPictureModeChanged`, `Phone.player()` (always null), `PHONE_UI` branches | ~150 lines | `extras` is always null; manifest has no PiP | medium: `lumenLook` also gates HeldPlayer, fold carefully |
| Torrent player | `TorrentEngine.kt`, `torrentStatus` poll, torrent branches in `PlayerActivity`, `LowSpace` torrents folder | ~60 lines | payload always sends `torrent: false` | check `phone.source` never returns an empty URL |
| Christmas page | `ChristmasPage.kt`, `Stremio.christmas`, Discover's season check | ~130 lines | `phone.christmas` unanswered: page is always empty | visible: point the Christmas genre tile at the normal genre page instead |
| "Every catalog" browser | `DiscoverScreen.CatalogBrowser`, Browse pill, `Stremio.discover*` | ~100 lines | `discover` answered as an error stub | visible: the Browse pill goes |
| Addons store | `SettingsPages.AddonsScreen/AddonRow`, `Page.Addons`, `Stremio` install/uninstall/remote addons | ~140 lines | never navigated to; its calls are unanswered | none |
| Ask AI in Search | `AiSearchPanel.kt`, `AiSearch.kt`, `Stremio.aiSearch` | ~200 lines | `phone.aiSearch` unanswered: always "AI service failed" | **your call**: remove, or rebuild on the sync server's AI (like picks) |
| Accounts panel | `ProfileScreen` `ACCOUNTS`, `AccountsPanel`, `if (false)` pill, `Stremio` account switching, `Accounts.kt` vault | ~130 lines | hidden, and the vault is never filled | none |
| Engine's own browse/pick API | `Lumos.home/meta/search/streams/playable/rank/pick/memory`, `Row`, `Choice`, `Engine.home/meta/streams` | ~140 lines | used only by tests; the app uses `Phone` | delete or port `PickTest` |
| Intro film | `Intro.kt`, `intro.mp4` (863 KB) | 170 lines + 863 KB | `INTRO = false` | your call: keep if Lumos/Lumen 3 will get its own intro |
| Small unused bits | `ScreenScrolling`, `Wash`, `fadeBottom`, `Dx.Spectrum`, `Glass.FillStrong/Focused`, `Lumos.resumePoint`, `Stremio.person`, `Phone.realPicture`, `Phone.route`/`Target`, `openExternal`, `Engine.onMessage`, `lumos_art` table, `/lists/refresh` route, unused `label(s, …)` param | ~120 lines | 0 callers each | none |

## 2. Duplicate logic to consolidate

| What | Copies | Fix | Risk |
|---|---|---|---|
| Stream ranking | `Phone.score()` (list order) and `Phone.rank()` (the pick) use different formulas, plus the dead one in `Lumos` | one ranking | the pick could change: keep `PickTest` |
| Episode order / next episode | `Phone.guessVideo`, `Phone.nextVideo`, two more inline sorts | one `episodes(meta)` + one `nextVideo` | low |
| "Finished" = 92% | 5 places in `Lumos.kt` | one constant | none |
| "New" tags | `TitlePage.newTag` vs the server labels | drop `newTag` | page shows no tag where the server has none |
| Poster cards | `Cards.PosterCard` vs `Preview.PosterFace` (different ✓ marks, no dim) | one card | Discover grid looks slightly different: check on the TV |
| Date parsing | `SimpleDateFormat` built per call in ~10 places | one `Dates` helper | keep UTC vs local where it differs |
| Page scroll set-up | 7 screens repeat LazyColumn + PageScroller + CentredScrolling | one `PageColumn` | scroll tuning was fiddly: test each screen |
| TMDB "find" | `Tmdb.kt`, `DiscoverFeed`, `sync/picks.js`, `sync/labels.js`, `sync/art.js`; cached twice on the server (`find:` and `lumos_ids`) | one helper each side; art reuses the find cache (saves 1 of 3 TMDB calls per new title) | keep reading old cache rows |
| Server fetch helpers | `getJson`/`tmdb()` in 5 files, some without timeouts | one shared module with timeouts | keep `language=en-GB` where used |
| Genre maps, title builders | `lists.js` vs `picks.js` | one map + overrides | genre names could change |
| JSON `text()` helper, episode label strings | twice each | one | none |

## 3. Unnecessary complexity / performance

- **Polling that could be events** (biggest win on a weak TV): Home's picks loop (up to 14 x 1.5 s, re-run on every
  library or watched change, ~9 calls each pass), the profiles poll (12 x every 5 s), the player's stream list poll
  (every 0.5 s for 14 s), and the page follow loop. Have the engine push changes instead.
- `LumosSettings.value()` re-parses all settings JSON per call (~12 times per Play).
- Server `/sync` reads the account's whole data (up to 4 MB) to merge a few keys; read only the incoming keys.
- Server cache tables never cleaned: `lumos_meta` (`scores:`, `recs:`), `lumos_titleart`, `lumos_facts`. Add a cron
  clean-up of expired cache rows.
- Server `art.js` still writes one row at a time; `wrongArt` and `bads()` read/write in loops.
- `Phone.warmed` stream cache has no size cap (memory grows during a session).
- Pass-through layers (`Phone.kt` lumen glue, `PlaybackBus` JSON round trip, one-line `Lumos` -> `Sync` wrappers).
- A leftover `{ }` block in the Worker's `handle()`.

## Kept on purpose (looks dead, isn't)

Stremio keep-in-step (`StremioBridge`, `pullFromStremio`, ~210 lines) stays until Lumen 2 is retired everywhere.
`playLink`'s Stremio-style address is still how the player reads its stream. Lumen 3's GitHub update path is live.
The iOS-only engine calls (`createAccount`, `approveSignIn`) stay while the iPhone app exists.

## Recommended order

1. **Safe deletions, no visible change** (~900 lines + 65 KB): dxbi-tv.js, plugin update, old SignIn, watch party,
   torrent paths, Addons store, Accounts panel, expanding cards, small unused bits, engine browse/pick API (+ tests),
   `lumos_art`, `/lists/refresh`. One build and a run through the Mi Box after.
2. **Fix the bugs**: save ratings (engine + sync key `ratings:<profile>`, which the picks already read), stop the
   `forYou` loop, build the Calendar's episodes from TMDB, skip junk `lumos_ids` rows.
3. **Visible removals, your decisions**: Christmas page, Every-catalog browser, Ask AI, intro film.
4. **Consolidate**: ranking, episodes, dates, "New" tag, TMDB helpers, server fetch/genre/title helpers, poster cards.
5. **Performance**: push instead of poll (picks, profiles, streams), settings parsed once, narrower `/sync` read,
   server cache clean-up, art writes batched.

Estimated: about 1,600-1,900 lines and ~930 KB (script + intro) removable, five polling loops gone.

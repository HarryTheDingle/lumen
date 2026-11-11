import Foundation
import Observation
import UIKit

/**
 * Stremio as the app's screens see it: what to show (kept current, and remembered between launches
 * so the app opens on the last Home it showed) and what they can do. All of it through [Page].
 */
@MainActor
@Observable
final class Stremio {

    /** Where the app's next stream goes: Lumen's player, another app (Infuse, VLC…), or Downloads */
    enum Target { case player, otherApp, download }

    @ObservationIgnored let page = Page()
    @ObservationIgnored private let cache = UserDefaults.standard

    var ready = false
    var account: Account?
    var continueWatching: [Continue] = []
    var rows: [Row] = []
    var allRows: [Row] = []
    var billboard: [Title] = []
    var myList: [ListRow] = []
    var forYou: ListRow?
    var airing: [Airing] = []
    var layout = HomeLayout()
    var viewers: [Viewer] = []
    var viewer: Viewer?
    /** Top Picks for You, then Because you watched … (the plugin's recommender) */
    var picks: [ListRow] = []
    /** What this profile said it likes (nil: not asked), and the rows Home shows from it until real picks exist */
    var taste: Taste?
    var tasteRows: [ListRow] = []
    /** The taste screens are open (first time on an empty profile, or Profile → Tune Suggestions) */
    var tuning = false
    /** The taste screens are wanted; Root keeps trying to open them until they are (a sheet can be in the way) */
    var tuningPending = false
    var tuningShown = false
    /** The story, genres, year and length of the Continue Watching titles (Stremio's library keeps none), by id */
    var described: [String: JSON] = [:]
    /** The accounts kept on this device (Keychain), the one in use (nil: none, or browsing without), and swapping to another */
    var accounts: [SavedAccount] = AccountVault.all()
    var currentAccountId: String? { didSet { cache.set(currentAccountId, forKey: "accounts.current") } }
    var swapping = false
    /** Add Account is on: the sign-in screen has Cancel */
    var addingAccount = false
    /** Who's watching? has been answered since the app opened (or there's only one to be) */
    var profileChosen = false
    /** Who's watching? was skipped only because there was one person to be: when the account's other profiles arrive (synced), it's asked after all */
    @ObservationIgnored var profileAutoChosen = false
    @ObservationIgnored var profileWatch: Task<Void, Never>?
    @ObservationIgnored var lastRemembered = Date.distantPast
    @ObservationIgnored var returnTo: String?
    @ObservationIgnored var returnToGuest = false

    var details: Details?
    var search: [Row] = []
    var discover: Discover?
    var library: [Title] = []
    /** Every title in the Library, and every one watched (by id): what a title's long-press menu offers */
    var inLibrary: Set<String> = []
    var watchedIds: Set<String> = []
    /** [inLibrary] and [watchedIds] have been read (till then, what each title came with) */
    var marksKnown = false
    var installedAddons: [Addon] = []
    var remoteAddons: [Addon] = []
    var remoteAddonsLoading = false
    var streamingServer: JSON?
    var profileSettings: JSON = [:]

    /** Browsing without an account (Stremio allows it: no sync) */
    var guest: Bool { didSet { cache.set(guest, forKey: "guest") } }
    /** Settings → Play videos in another app */
    var otherApp: String? { didSet { cache.set(otherApp, forKey: "otherApp") } }

    /** A short message over the screens (the page's toasts, errors) */
    var message: String?
    /** What the player is showing (the page's play payload), while it's open */
    var playback: Playback?

    /** The loading screen over everything while Play finds a stream or a stream is switched (nil: none) */
    var launch: LaunchScreen?
    /** Called when the loading screen is closed by hand (the page that was waiting for a stream stops waiting) */
    @ObservationIgnored var onLaunchCancel: (() -> Void)?
    @ObservationIgnored private var launchTimeout: Task<Void, Never>?
    /** A stream chosen on the loading screen, and All Streams (the page waiting for a stream says what they do) */
    @ObservationIgnored var onLaunchChoose: ((StreamOption) -> Void)?
    @ObservationIgnored var onLaunchMore: (() -> Void)?

    @ObservationIgnored private var board: JSON?
    @ObservationIgnored private var nextTarget = Target.player
    @ObservationIgnored private var extrasTask: Task<Void, Never>?
    @ObservationIgnored private var lastContinue: String?

    /** Where the page waits, out of sight: a screen Stremio has nothing on */
    private static let parked = "#/dxbi"

    init() {
        guest = cache.bool(forKey: "guest")
        otherApp = cache.string(forKey: "otherApp")
        // the last of each, from the launch before
        apply("ctx", remembered("ctx"))
        apply("board", remembered("board"))
        apply("continue_watching_preview", remembered("continue_watching_preview"))
        billboard = (savedList("x.billboard") ?? []).map(Title.init)
        myList = (savedList("x.myList") ?? []).map(ListRow.init)
        forYou = savedList("x.forYou")?.first.map(ListRow.init).flatMap { $0.items.isEmpty ? nil : $0 }
        picks = (savedList("x.picks") ?? []).map(ListRow.init).filter { !$0.items.isEmpty }
        described = (cache.string(forKey: "x.described").flatMap { Json.decode($0) as? JSON } ?? [:]).compactMapValues { $0 as? JSON }
        viewers = (savedList("x.viewers") ?? []).map(Viewer.init)
        viewer = viewers.first { $0.id == cache.string(forKey: "x.viewer") } ?? viewers.first
        currentAccountId = cache.string(forKey: "accounts.current")
        #if DEBUG
        // (for testing the account list without signing in: a made-up account with a made-up session, and its own people)
        if ProcessInfo.processInfo.arguments.contains("-fakeAccount") {
            let profile = Json.encode(["auth": ["key": "fake-test-key", "user": ["_id": "fake1", "email": "fake@example.invalid"]], "settings": [String: Any]()] as JSON)
            let data = Json.encode(["viewers": ["list": [["id": "main", "name": "Fake A", "colour": "#2f80ff"], ["id": "b", "name": "Fake B", "colour": "#ec4899"]], "current": "main"]] as JSON)
            AccountVault.save(SavedAccount(id: "fake1", email: "fake@example.invalid", avatar: nil, profile: profile, data: data))
            accounts = AccountVault.all()
        }
        #endif
        airing = (savedList("x.airing") ?? []).map(Airing.init)
        layout = HomeLayout(cache.string(forKey: "x.layout").flatMap { Json.decode($0) as? JSON })
        rows = layout.arrange(Row.list(board))

        page.onState = { [weak self] field, data in self?.apply(field, data, save: true) }
        page.onReady = { [weak self] in self?.started() }
        page.onGone = { [weak self] in self?.ready = false }
        page.onToast = { [weak self] text in self?.toast(text) }
        page.onPlay = { [weak self] json in self?.handOver(json) }
        page.onControl = { [weak self] c in self?.playback?.control(c) }
        Downloads.shared.stremio = self
        for field in ["ctx", "board", "continue_watching_preview", "meta_details", "search", "discover", "library", "installed_addons", "remote_addons", "streaming_server"] {
            page.watch(field)
        }
    }

    /**
     * The page's words. While the loading screen is up they go on it: a stream that wouldn't play is
     * passed over for the next best (the page's "Trying …") without leaving the loading screen
     */
    private func toast(_ text: String) {
        guard launch != nil else { return show(text) }
        if text.hasPrefix("That stream didn't play") {
            launch?.failed = false
            // (quietly: the next best is just starting; only when none plays does the screen say anything went wrong)
            Log.note("trying another stream: \(text)")
            stepLaunch("Starting", progress: 0.9)
            restartLaunchTimeout()
        } else if text.hasPrefix("This stream couldn't play") {
            failLaunch("Couldn't play a stream", "None of the best few would play here. Try Choose a Stream on the title's page.")
        } else {
            show(text)
        }
    }

    private func restartLaunchTimeout() {
        launchTimeout?.cancel()
        launchTimeout = Task {
            try? await Task.sleep(for: .seconds(45))
            if !Task.isCancelled, launch?.failed == false { failLaunch("Couldn't start a stream", "Try Choose a Stream on the title's page.") }
        }
    }

    func show(_ text: String) {
        message = text
        let shown = text
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            if message == shown { message = nil }
        }
    }

    // MARK: - State from the page

    private func remembered(_ field: String) -> JSON? {
        cache.string(forKey: "state.\(field)").flatMap { Json.decode($0) as? JSON }
    }

    private func savedList(_ key: String) -> [JSON]? {
        cache.string(forKey: key).flatMap { Json.decode($0) as? [Any] }?.compactMap { $0 as? JSON }
    }

    private func apply(_ field: String, _ data: JSON?, save: Bool = false) {
        guard let data else { return }
        if save, ["ctx", "board", "continue_watching_preview"].contains(field) {
            cache.set(Json.encode(data), forKey: "state.\(field)")
        }
        switch field {
        case "ctx":
            account = Account(data)
            profileSettings = data.object("settings") ?? [:]
        case "board":
            board = data
            allRows = Row.list(data)
            rows = layout.arrange(allRows)
        case "continue_watching_preview":
            continueWatching = Continue.list(data)
            // the library changing (watched something, added to the list, another device) changes Lumen's rows
            let sig = Json.encode(data)
            if lastContinue != nil, sig != lastContinue, ready { refreshExtras() }
            lastContinue = sig
        case "meta_details": details = Details(data)
        case "search": search = Row.list(data)
        case "discover": discover = Discover(data)
        case "library": library = data.objects("items").map(Title.init).filter { Title.watchable.contains($0.type) }
        case "installed_addons": installedAddons = data.objects("items").map(Addon.init)
        case "remote_addons":
            remoteAddons = data.objects("items").map(Addon.init)
            remoteAddonsLoading = Load(data) == .loading
        case "streaming_server": streamingServer = data
        default: break
        }
    }

    private func started() {
        ready = true
        Task {
            defer { swapping = false }
            // The page itself waits on an empty screen: its own Home would keep working away out of sight
            await ask("go", ["hash": Self.parked])
            try? await Task.sleep(for: .milliseconds(400))
            await loadHome()
            refreshExtras()
        }
    }

    @discardableResult
    private func ask(_ op: String, _ args: JSON = [:], timeout: Double = 20) async -> Any? {
        do {
            return try await page.call(op, args, timeout: timeout)
        } catch {
            print("[Lumen] \(op) failed: \(error.localizedDescription)")
            return nil
        }
    }

    @discardableResult
    private func dx(_ method: String, _ args: Any...) async -> Any? {
        await ask("dx", ["method": method, "args": args])
    }

    func loadHome() async {
        await ask("load", ["field": "board", "model": "CatalogsWithExtra", "args": ["extra": [Any]()]])
        await ask("loadRange", ["field": "board", "start": 0, "end": 30])
    }

    func refreshExtras() {
        extrasTask?.cancel()
        extrasTask = Task {
            await refreshMarks()
            if let v = await dx("phone.viewers") as? JSON {
                viewers = v.objects("list").map(Viewer.init)
                viewer = viewers.first { $0.id == v.string("current") } ?? viewers.first
                cache.set(Json.encode(v.objects("list")), forKey: "x.viewers")
                cache.set(viewer?.id, forKey: "x.viewer")
                loadTaste()
                // (one person to be: nothing to ask, but the account's other profiles may be on their way from another device)
                if viewers.count < 2 {
                    profileChosen = true
                    profileAutoChosen = true
                    watchForProfiles()
                } else if profileAutoChosen {
                    profileAutoChosen = false
                    profileChosen = false
                }
            }
            await rememberAccount()
            if let l = await dx("phone.layout") as? JSON {
                layout = HomeLayout(l)
                rows = layout.arrange(allRows)
                cache.set(Json.encode(l), forKey: "x.layout")
            }
            if let list = await dx("phone.myList") as? [Any] {
                myList = list.compactMap { $0 as? JSON }.map(ListRow.init)
                cache.set(Json.encode(list), forKey: "x.myList")
            }
            _ = await dx("phone.picks")
            // (and the Top Picks the billboard suggests: picks can come without their story)
            // The billboard's own few first, then the rest: asked for all together, one slow title held up
            // every answer past the wait, and the billboard said no more than "TV Show"
            let lead = continueWatching.prefix(4).map(\.title) + (picks.first?.items.prefix(4) ?? [])
            let rest = continueWatching.dropFirst(4).prefix(8).map(\.title) + (picks.first?.items.dropFirst(4).prefix(4) ?? [])
            for group in [lead, rest] where !group.isEmpty {
                if Task.isCancelled { return }
                guard let o = await ask("dx", ["method": "phone.describe", "args": [group.map { [$0.type, $0.id] }]], timeout: 30) as? JSON else { continue }
                if described.count > 80 { described = [:] }
                described.merge(o.compactMapValues { $0 as? JSON }) { _, new in new }
                cache.set(Json.encode(described), forKey: "x.described")
            }
            if let list = await dx("phone.airing") as? [Any] {
                airing = list.compactMap { $0 as? JSON }.map(Airing.init)
                cache.set(Json.encode(list), forKey: "x.airing")
            }
            // the billboard and Because you watched fetch from the internet: they can take a few seconds
            if let list = await dx("phone.billboard") as? [Any], !list.isEmpty || billboard.isEmpty {
                billboard = list.compactMap { $0 as? JSON }.map(Title.init)
                cache.set(Json.encode(list), forKey: "x.billboard")
            }
            // Top Picks and Because you watched: made on the device, so the first answer can take a few seconds
            // (a stale one is the last answer while a fresh one is made)
            // (the engine said this profile has nothing to recommend from, as opposed to not having answered)
            var picksNoHistory = false
            for _ in 0..<14 {
                if Task.isCancelled { return }
                if let answer = await dx("phone.picks") as? JSON {
                    let rows = answer.objects("rows").map(ListRow.init).filter { !$0.items.isEmpty }
                    // (changed only when there's something new: swapped every time, or for an empty
                    // answer while a fresh one was made, the rows on Home kept vanishing and coming back)
                    if rows != picks && (!rows.isEmpty || !answer.bool("stale")) { picks = rows }
                    cache.set(Json.encode(answer.objects("rows")), forKey: "x.picks")
                    if !answer.bool("stale") { picksNoHistory = rows.isEmpty; break }
                }
                try? await Task.sleep(for: .seconds(1.5))
            }
            if !picks.isEmpty { forYou = nil; return }
            // a profile with nothing watched yet: ask what they like, once
            if picksNoHistory { offerTuning() }
            // nothing watched yet to go on: popular titles in the genres of what was saved last
            for _ in 0..<10 {
                if Task.isCancelled { return }
                if let answer = await dx("phone.forYou") as? JSON {
                    let row = ListRow(answer)
                    forYou = row.items.isEmpty ? nil : row
                    cache.set(Json.encode([answer]), forKey: "x.forYou")
                    return
                }
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
    }

    // MARK: - The loading screen

    func beginLaunch(_ title: Title, episode: String? = nil, text: String, detail: String? = nil, progress: Double = 0.03) {
        Log.note("launch: \(title.name) \(episode ?? "") · \(text)")
        launch = LaunchScreen(title: title, episode: episode, text: text, detail: detail, progress: progress)
        // (sideways already, as the player will be)
        AppDelegate.holdSideways = true
        AppDelegate.turn(sideways: true)
        launchTimeout?.cancel()
        // (the player is slow to open, or never does: the screen doesn't stay for ever)
        launchTimeout = Task {
            try? await Task.sleep(for: .seconds(45))
            if !Task.isCancelled, launch?.failed == false { failLaunch("Couldn't start a stream", "Try Choose a Stream on the title's page.") }
        }
    }

    func stepLaunch(_ text: String, detail: String? = nil, progress: Double) {
        // (not while it's offering streams to choose from: that stays until one is chosen)
        guard var now = launch, !now.failed, now.choices.isEmpty else { return }
        // (changed as a whole: reading it while writing a part of it is what crashed)
        now.text = text
        now.detail = detail
        now.progress = max(progress, now.progress)
        if now.text != launch?.text { Log.note("step: \(text) · \(detail ?? "")") }
        launch = now
    }

    func failLaunch(_ text: String, _ detail: String) {
        guard launch != nil else { return }
        Log.note("failed: \(text) · \(detail)")
        launch?.text = text
        launch?.detail = detail
        launch?.progress = 1
        launch?.failed = true
    }

    func endLaunch() {
        if launch != nil { Log.note("loading screen gone") }
        launchTimeout?.cancel()
        launch = nil
        onLaunchChoose = nil
        onLaunchMore = nil
        AppDelegate.holdSideways = false
        // (nothing played: upright again)
        if playback == nil { AppDelegate.turn(sideways: false) }
    }

    /** Mobile data or Low Data Mode: the loading screen offers [options] instead of starting one itself */
    func offerLaunch(_ options: [StreamOption]) {
        guard var now = launch, !now.failed else { return }
        now.text = "\(NetworkStatus.shared.reason): choose a stream"
        now.detail = "A smaller file uses less data."
        now.choices = options
        launch = now
        // (it waits for a choice: no giving up meanwhile)
        launchTimeout?.cancel()
    }

    func chooseLaunch(_ option: StreamOption) {
        guard var now = launch else { return }
        now.choices = []
        launch = now
        launchTimeout?.cancel()
        launchTimeout = Task {
            try? await Task.sleep(for: .seconds(45))
            if !Task.isCancelled, launch?.failed == false { failLaunch("Couldn't start a stream", "Try Choose a Stream on the title's page.") }
        }
        onLaunchChoose?(option)
    }

    func moreLaunchChoices() {
        let more = onLaunchMore
        endLaunch()
        onLaunchCancel = nil
        more?()
    }

    /** Closed by hand */
    func cancelLaunch() {
        endLaunch()
        // (over the player, before its first picture: the player closes too)
        playback?.finish(ended: false)
        onLaunchCancel?()
        onLaunchCancel = nil
    }

    // MARK: - Details

    private func path(_ resource: String, _ type: String, _ id: String) -> JSON {
        ["resource": resource, "type": type, "id": id, "extra": [Any]()]
    }

    /** A title's details, and the streams for [videoId] (a film's streams are its own; a series' wait for an episode) */
    func open(_ type: String, _ id: String, videoId: String? = nil) {
        let streamsFor = videoId ?? (type != "series" ? id : nil)
        let args: JSON = [
            "metaPath": path("meta", type, id),
            "streamPath": streamsFor.map { path("stream", type, $0) } ?? NSNull(),
            "guessStream": streamsFor == nil && type != "series",
        ]
        Task { await ask("load", ["field": "meta_details", "model": "MetaDetails", "args": args]) }
    }

    func setInLibrary(_ add: Bool) {
        if let id = details?.metaId { mark(id, inLibrary: add) }
        Task {
            await ask("library", ["add": add])
            // (Stremio saves the Library a moment later: asked at once, it still says the old)
            try? await Task.sleep(for: .seconds(1))
            refreshExtras()
        }
    }

    /** Adds a title that isn't open (Home's +, a long-press): Stremio wants the whole title, so it's opened first */
    func setInLibrary(_ title: Title, _ add: Bool) {
        mark(title.id, inLibrary: add)
        if let i = billboard.firstIndex(where: { $0.id == title.id }) { billboard[i].inLibrary = add }
        withOpened(title) { await self.ask("library", ["add": add]) }
    }

    func setWatched(_ watched: Bool) {
        if let id = details?.metaId {
            mark(id, watched: watched)
            if !watched, let i = continueWatching.firstIndex(where: { $0.title.id == id }) { continueWatching[i].progress = 0 }
        }
        Task {
            await ask("watched", ["value": watched])
            try? await Task.sleep(for: .seconds(1))
            refreshExtras()
        }
    }

    /** Marks a title that isn't open as watched or not (a series: every episode) */
    func setWatched(_ title: Title, _ watched: Bool) {
        mark(title.id, watched: watched)
        // (unwatched goes back to the start: Play, not Resume, at once; then what Stremio says)
        if !watched, let i = continueWatching.firstIndex(where: { $0.title.id == title.id }) { continueWatching[i].progress = 0 }
        withOpened(title) {
            await self.ask("watched", ["value": watched])
            try? await Task.sleep(for: .seconds(1))
            self.refreshExtras()
        }
    }

    /** In the Library, or watched: from [inLibrary] and [watchedIds], else what the title came with */
    /** (a title in the Library tab is in it, whatever the marks say: a just-played film is saved only for a while, and couldn't be removed) */
    func isInLibrary(_ title: Title) -> Bool { marksKnown ? inLibrary.contains(title.id) || library.contains { $0.id == title.id } : title.inLibrary }
    /** (Stremio counts a show as watched once one episode has been: one still in Continue Watching isn't) */
    func isWatched(_ title: Title) -> Bool { (marksKnown ? watchedIds.contains(title.id) : title.watched) && !isStarted(title) }
    /** Partly seen (it's in Continue Watching): Mark as Unwatched is offered too, and puts it back to the start */
    func isStarted(_ title: Title) -> Bool { continueWatching.contains { $0.title.id == title.id } }

    func refreshMarks() async {
        guard let marks = await dx("phone.marks") as? JSON else { return }
        inLibrary = Set(marks.strings("library"))
        watchedIds = Set(marks.strings("watched"))
        marksKnown = true
    }

    /** Shown at once; Stremio's own answer follows */
    private func mark(_ id: String, inLibrary add: Bool? = nil, watched: Bool? = nil) {
        if let add { if add { inLibrary.insert(id) } else { inLibrary.remove(id) } }
        if let watched { if watched { watchedIds.insert(id) } else { watchedIds.remove(id) } }
    }

    /**
     * Opens [title] to change it (Stremio changes the title open in details), then opens again what
     * was open before (a title's page whose Related poster was long-pressed keeps its own)
     */
    private func withOpened(_ title: Title, _ change: @escaping () async -> Void) {
        let before = details.flatMap { d in d.metaId.map { (type: d.title?.type ?? "", id: $0, videoId: d.videoId) } }
        open(title.type, title.id)
        Task {
            for _ in 0..<40 where !(details?.metaId == title.id && details?.state == .ready) {
                try? await Task.sleep(for: .milliseconds(150))
            }
            guard details?.metaId == title.id else { return show("Couldn't reach Stremio. Try again.") }
            await change()
            if let before, before.id != title.id, !before.type.isEmpty { open(before.type, before.id, videoId: before.videoId) }
            try? await Task.sleep(for: .seconds(1))
            refreshExtras()
        }
    }

    func removeFromContinue(_ id: String) { Task { await ask("dismiss", ["id": id]) } }

    /** The open title's streams, described by Lumen, with Just press Play's pick */
    func streams() async -> StreamChoices? {
        (await dx("phone.streams") as? JSON).map(StreamChoices.init)
    }

    /**
     * It's buffering: streams lighter than the one playing that fit the speed it's really getting
     * ([mbps], measured by the player; [nowMbps], the playing file's bitrate, 0 if unknown)
     */
    func lighter(mbps: Double, nowMbps: Double) async -> [StreamOption] {
        let list = await dx("phone.lighter", mbps, nowMbps) as? [Any] ?? []
        return Self.once(list.compactMap { $0 as? JSON }.map { StreamOption($0.int("index", -1), $0) }.filter { $0.index >= 0 })
    }

    /** Streams that read the same (quality, addon, size) once: a list of three identical rows is no choice */
    private static func once(_ options: [StreamOption]) -> [StreamOption] {
        var seen = Set<String>()
        return options.filter { seen.insert("\($0.label)|\($0.addon)|\(Int($0.size))|\($0.mbps)").inserted }
    }

    /** Where to start the next player after a stream switch: the same video, at this place */
    @ObservationIgnored private var switching: (videoId: String, seconds: Double, at: Date)?

    /** The place a stream switch left off, for the player that opens next (the same video, soon after) */
    func resumePoint(for videoId: String) -> Double? {
        defer { switching = nil }
        guard let s = switching, s.videoId == videoId, Date().timeIntervalSince(s.at) < 60 else { return nil }
        return s.seconds
    }

    // MARK: - Speed per network

    /** What the player measured on this network: the page remembers it (Settings → Networks) */
    func networkSample(_ mbps: Double) {
        Task { await dx("phone.networkSample", mbps) }
    }

    /** The playing file's real picture width: a stream whose name claimed more is read at its real quality after */
    func realPicture(width: Double) {
        Task { await dx("phone.realPicture", width) }
    }

    func networks() async -> [NetworkInfo] {
        ((await dx("phone.networks") as? [Any]) ?? []).compactMap { $0 as? JSON }.map(NetworkInfo.init)
    }

    func setNetwork(_ id: String, name: String? = nil, cap: Int? = nil, relearn: Bool = false) async {
        var changes: JSON = [:]
        if let name { changes["name"] = name }
        if let cap { changes["cap"] = cap }
        if relearn { changes["relearn"] = true }
        await dx("phone.setNetwork", id, changes)
    }

    func forgetNetwork(_ id: String) async {
        await dx("phone.forgetNetwork", id)
    }

    /** The seek bar's previews: a light web stream of this video (720p or so), when there is one */
    func previewSource(videoId: String) async -> (url: String, headers: [String: String])? {
        guard let o = await dx("phone.previewSource", videoId) as? JSON, let url = o.text("url") else { return nil }
        return (url, (o.object("headers") ?? [:]).compactMapValues { $0 as? String })
    }

    /** The player's Streams button: other streams of what's playing, grouped by what they change */
    func alternatives(nowMbps: Double) async -> [StreamGroup] {
        let o = await dx("phone.alternatives", nowMbps) as? JSON
        return (o?.objects("groups") ?? []).map { g in
            StreamGroup(title: g.string("title"), options: Self.once(g.objects("options").map { StreamOption($0.int("index", -1), $0) }.filter { $0.index >= 0 }))
        }.filter { !$0.options.isEmpty }
    }

    /** From the player to another stream of the same video: it closes (keeping its place) and the other opens there */
    func switchStream(to option: StreamOption) {
        // A web stream (debrid's) opens in the same player, at the same second: nothing closes
        if let p = playback {
            Task {
                if let source = await dx("phone.source", option.index) as? JSON, let url = source.text("url") {
                    let headers = (source.object("headers") ?? [:]).compactMapValues { $0 as? String }
                    p.switchTo(url: url, headers: headers, label: source.string("label"))
                } else {
                    reopen(option)
                }
            }
            return
        }
        play(option)
    }

    /** What's playing, as a title for the loading screen (the open title's own when it's that one: it has the logo) */
    private func playingTitle(_ payload: JSON) -> Title {
        let video = payload.string("videoId")
        let meta = video.wholeMatch(of: /(.+):\d+:\d+/).map { String($0.1) } ?? video
        if details?.metaId == meta, let title = details?.title { return title }
        var t = Title(id: meta, type: meta == video ? "movie" : "series", name: payload.string("title"))
        t.poster = payload.text("poster")
        t.background = payload.text("background")
        return t
    }

    /** A title from the library, with what it didn't keep filled in (for the billboard) */
    func filledIn(_ title: Title) -> Title {
        guard let d = described[title.id] else { return title }
        var t = title
        if t.description == nil { t.description = d.text("description") }
        if t.genres.isEmpty { t.genres = d.strings("genres") }
        if t.year == nil { t.year = d.text("year") }
        if t.runtime == nil { t.runtime = d.text("runtime") }
        // (anime from the Kitsu addon: its own logo and art, which the Library doesn't keep and metahub doesn't have)
        if t.logo == nil { t.logo = d.text("logo") }
        if t.background == nil { t.background = d.text("background") }
        return t
    }

    /** A torrent: the player closes and the other stream opens through Stremio's, where this one was */
    private func reopen(_ option: StreamOption) {
        // (Stremio's own saved place lags behind, and was sometimes the start: the player hands it over)
        if let p = playback {
            switching = (p.payload.string("videoId"), p.position, Date())
            // (the loading screen goes up first, so the page under the player never shows)
            beginLaunch(
                playingTitle(p.payload),
                episode: p.payload.text("subtitle"),
                text: "Switching stream",
                detail: option.label,
                progress: 0.5
            )
        }
        playback?.finish(ended: false)
        Task {
            // (the page has to be back from the player before it can open another)
            try? await Task.sleep(for: .seconds(1.5))
            play(option)
        }
    }

    /** Plays one of [streams] (Lumen keeps the next best ready in case it won't play) */
    func play(_ option: StreamOption, in target: Target = .player) {
        nextTarget = target == .player && otherApp != nil ? .otherApp : target
        Task {
            var chosen = option
            // A stream whose server answers with an error, or with a clip saying so (ElfHosted's "Couldn't fetch
            // this release"), is passed over before it's played: the next best goes in its place
            if target == .player {
                for _ in 0..<4 {
                    guard let why = await whyNotVideo(chosen) else { break }
                    Log.note("passed over before playing: \(why)")
                    _ = await dx("phone.reject", chosen.index, why)
                    if let now = launch { stepLaunch(now.text, detail: now.detail, progress: 0.85) }
                    guard let next = await streams()?.picked else {
                        if launch != nil { failLaunch("Couldn't find a stream that plays", "Try … → Choose a Stream on the title's page.") }
                        return
                    }
                    chosen = next
                }
            }
            let link = await dx("phone.play", chosen.index) as? String ?? chosen.play
            // (for Play or Resume on this film or episode again: straight back to this stream, PlayedLinks)
            if target == .player { PlayedLinks.put(link) }
            await ask("play", ["link": link])
        }
    }

    /**
     * Play went straight to the stream last played of [video] (PlayedLinks): while it's starting the title's page doesn't
     * start its own pick; if it won't play it's forgotten and the page's pick goes instead
     */
    @ObservationIgnored var savedAttempt: String?

    func playSaved(_ link: String, video: String) {
        savedAttempt = video
        Log.note("the stream from last time: \(video)")
        Task { await ask("play", ["link": link]) }
    }

    /**
     * A look at a web stream before it's played: why it isn't a video (the addon's server errored, or sent a clip
     * saying so), or nil when it looks fine or can't be told. Asks for the first bytes only; torrents aren't looked at.
     */
    /** Checks the likely pick while the addons are still answering, so Play doesn't wait for it (and the debrid server has the file ready) */
    func probeAhead(_ option: StreamOption) {
        guard probes[option.play] == nil else { return }
        if probes.count > 20 { probes = [:] }
        probes[option.play] = Task { await self.probe(option) }
    }

    @ObservationIgnored private var probes: [String: Task<String?, Never>] = [:]

    private func whyNotVideo(_ option: StreamOption) async -> String? {
        let check = probes[option.play] ?? Task { await self.probe(option) }
        probes[option.play] = nil
        // (never a long wait: a slow or odd server counts as fine, and the player decides)
        return await withTaskGroup(of: String?.self) { group in
            group.addTask { await check.value }
            group.addTask { try? await Task.sleep(for: .seconds(8)); return nil }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private func probe(_ option: StreamOption) async -> String? {
        guard let p = await dx("phone.probe", option.index) as? [String: Any],
              let address = p["url"] as? String, var url = URL(string: address) else { return nil }
        let headers = p["headers"] as? [String: String] ?? [:]
        let listed = (p["size"] as? Double) ?? 0
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirects(), delegateQueue: nil)
        for _ in 0..<6 {
            var request = URLRequest(url: url, timeoutInterval: 5)
            for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
            request.setValue("bytes=0-2047", forHTTPHeaderField: "Range")
            // (the answer's headers only: a server that ignores the range would send the whole file)
            guard let (bytes, response) = try? await session.bytes(for: request), let http = response as? HTTPURLResponse else { return nil }
            defer { bytes.task.cancel() }
            if (300..<400).contains(http.statusCode) {
                guard let to = http.value(forHTTPHeaderField: "Location"), let next = URL(string: to, relativeTo: url)?.absoluteURL else { return nil }
                url = next
                continue
            }
            if http.statusCode >= 400 { return "The server answered \(http.statusCode)" }
            // (where the link leads, for the player to go straight there: RedirectMemory)
            RedirectMemory.remember(address, url.absoluteString)
            let type = (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
            if type.hasPrefix("text/") || type.contains("json") || type.contains("xml") { return "It isn't a video (\(type))" }
            let name = url.lastPathComponent
            if name.range(of: "(fail|error|unavail|not[_-]?found|denied|expired|blocked)[^/]*\\.(mp4|mkv|webm|ts)$", options: [.regularExpression, .caseInsensitive]) != nil {
                return "The server sent an error video (\(name))"
            }
            var total: Int64?
            if let range = http.value(forHTTPHeaderField: "Content-Range"), let n = range.split(separator: "/").last { total = Int64(n) }
            else if http.statusCode == 200, http.expectedContentLength > 0 { total = http.expectedContentLength }
            if let total, total < 30 * 1024 * 1024, listed <= 0 || listed > Double(total) * 10 { return "It's only \(total / 1024 / 1024) MB: an error clip" }
            return nil
        }
        return nil
    }

    /**
     * The intro, recap and credits times for a video, fetched ahead (the plugin keeps them): the player's start waited
     * for them before it opened
     */
    func prefetchSkips(_ type: String, _ metaId: String, _ videoId: String) {
        guard !skipsAsked.contains(videoId) else { return }
        skipsAsked.insert(videoId)
        Task { _ = await dx("skipSegments", type, metaId, videoId) }
    }
    @ObservationIgnored private var skipsAsked = Set<String>()

    /** Continue Watching: Stremio's own player link for where they left off */
    func play(link: String) {
        nextTarget = otherApp != nil ? .otherApp : .player
        Task { await ask("play", ["link": link]) }
    }

    func reviews(_ type: String, _ id: String) async -> Reviews? {
        (await ask("dx", ["method": "phone.reviews", "args": [type, id]], timeout: 40) as? JSON).map(Reviews.init)
    }

    // MARK: - Search, Discover, Library

    func search(_ query: String) {
        Task {
            await ask("load", ["field": "search", "model": "CatalogsWithExtra", "args": ["extra": [["search", query]]]])
            await ask("loadRange", ["field": "search", "start": 0, "end": 20])
        }
    }

    /** A Discover link (a row's See All: #/discover/<addon>/<type>/<catalog>?genre=…) */
    func discover(link: String) {
        guard let m = link.wholeMatch(of: /#\/discover\/([^\/]+)\/([^\/]+)\/([^\/?]+)(?:\?(.*))?/) else { return }
        let decode = { (s: Substring) in String(s).removingPercentEncoding ?? String(s) }
        var extra: [[String]] = []
        for pair in (m.output.4 ?? "").split(separator: "&") where pair.contains("=") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            extra.append([decode(kv[0]), decode(kv[1])])
        }
        discover(base: decode(m.output.1), type: decode(m.output.2), catalog: decode(m.output.3), extra: extra)
    }

    func discover(base: String, type: String, catalog: String, extra: [[String]] = []) {
        let request: JSON = ["base": base, "path": ["resource": "catalog", "type": type, "id": catalog, "extra": extra] as JSON]
        Task { await ask("load", ["field": "discover", "model": "CatalogWithFilters", "args": ["request": request]]) }
    }

    /** The same catalog with another genre (nil: all) */
    func discoverGenre(_ genre: String?) {
        guard let request = discover?.request, let path = request.object("path") else { return }
        var extra = ((path["extra"] as? [Any]) ?? []).compactMap { $0 as? [String] }.filter { $0.first != "genre" }
        if let genre { extra.append(["genre", genre]) }
        discover(base: request.string("base"), type: path.string("type"), catalog: path.string("id"), extra: extra)
    }

    func discoverMore() {
        Task { await ask("dispatch", ["field": "discover", "action": ["action": "CatalogWithFilters", "args": ["action": "LoadNextPage"]] as JSON]) }
    }

    /** Discover opened for the first time: Stremio's first catalog (Popular movies) */
    func discoverStart() {
        guard discover?.request == nil else { return }
        if let link = allRows.first(where: { $0.type == "movie" })?.discover ?? allRows.first?.discover { discover(link: link) }
    }

    func loadLibrary(_ type: String? = nil) {
        Task { await ask("load", ["field": "library", "model": "LibraryWithFilters", "args": ["request": ["type": type ?? NSNull(), "sort": "lastwatched"] as JSON]]) }
    }

    func libraryMore() {
        Task { await ask("dispatch", ["field": "library", "action": ["action": "LibraryWithFilters", "args": ["action": "LoadNextPage"]] as JSON]) }
    }

    // MARK: - Account

    /** Signs in; nil when it worked, else what went wrong */
    func signIn(email: String, password: String) async -> String? {
        let answer: (String, JSON?)? = await withCheckedContinuation { continuation in
            var done = false
            let finish: ((String, JSON?)?) -> Void = { value in
                guard !done else { return }
                done = true
                self.page.onEvent = nil
                continuation.resume(returning: value)
            }
            page.onEvent = { event, args in
                if event == "UserAuthenticated" || event == "Error" { finish((event, args)) }
            }
            Task {
                await ask("signIn", ["email": email, "password": password])
                try? await Task.sleep(for: .seconds(20))
                finish(nil)
            }
        }
        guard let answer else { return "Stremio didn't answer. Check your connection and try again." }
        if answer.0 == "UserAuthenticated" {
            didSignIn()
            return nil
        }
        return answer.1?.object("error")?.text("message") ?? "That email and password didn't work."
    }

    func signOut() {
        guest = false
        addingAccount = false
        // (the session ends for good: the kept copy would be a dead key)
        if let id = currentAccountId { AccountVault.remove(id) }
        currentAccountId = nil
        accounts = AccountVault.all()
        Task { await ask("signOut") }
    }

    // MARK: - Addons

    func loadAddons() {
        Task { await ask("load", ["field": "installed_addons", "model": "InstalledAddonsWithFilters", "args": ["request": ["type": NSNull()] as JSON]]) }
    }

    /** Stremio's addon lists: "official" or "community" */
    func loadRemoteAddons(_ catalog: String) {
        let request: JSON = ["base": "https://v3-cinemeta.strem.io/manifest.json", "path": ["resource": "addon_catalog", "type": "all", "id": catalog, "extra": [Any]()] as JSON]
        remoteAddonsLoading = true
        Task { await ask("load", ["field": "remote_addons", "model": "CatalogWithFilters", "args": ["request": request]]) }
    }

    /** Each returns nil when it worked, else what went wrong */
    func install(_ addon: Addon) async -> String? { await attempt("addonInstall", ["transportUrl": addon.transportUrl]) }
    func uninstall(_ addon: Addon) async -> String? { await attempt("addonUninstall", ["transportUrl": addon.transportUrl]) }
    func install(link: String) async -> String? { await attempt("addonFromUrl", ["url": link]) }

    private func attempt(_ op: String, _ args: JSON) async -> String? {
        do {
            _ = try await page.call(op, args)
            await loadHome()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    // MARK: - Settings

    func updateSettings(_ changes: JSON) { Task { await ask("updateSettings", ["changes": changes]) } }

    /** Lumen settings → Play Trailers (on unless switched off): a title's page fades its trailer in */
    func playTrailers() async -> Bool {
        await settings().first { $0.key == "playTrailers" }?.value != "false"
    }

    /** Shuffle: [metaId]'s next episodes are random ones (on), until anything else is played (an ordinary Play clears it) */
    func shuffling(_ metaId: String, on: Bool) {
        Task { await dx("phone.shuffle", metaId, on) }
    }

    /** Start from the Beginning: the next player for [videoId] starts at 0 (on), or not (off: an ordinary Play clears it) */
    func startFromBeginning(_ videoId: String?, on: Bool) {
        Task { await ask("fromStart", ["videoId": videoId ?? NSNull(), "on": on]) }
    }

    func settings() async -> [Setting] {
        ((await ask("settings")) as? [Any] ?? []).compactMap { $0 as? JSON }.map(Setting.init)
    }

    func saveSetting(_ key: String, json: String) async {
        await ask("saveSetting", ["key": key, "value": Json.decode(json) ?? NSNull()])
        refreshExtras()
    }

    func saveLayout(_ layout: HomeLayout) {
        self.layout = layout
        rows = layout.arrange(allRows)
        let data: JSON = ["order": layout.order, "hidden": Array(layout.hidden), "names": layout.names]
        Task {
            await dx("homeLayout.save", data)
            refreshExtras()
        }
    }

    /** Who's watching: their own Continue Watching, watched marks and picks follow */
    func chooseViewer(_ id: String) {
        profileChosen = true
        // (the same person as last time: what's on Home is theirs already. Emptied, Continue Watching stayed
        // empty, since the page only sends it again when it changes)
        let same = viewer?.id == id
        if let v = viewers.first(where: { $0.id == id }) { viewer = v }
        Task {
            await dx("viewers.select", id)
            if !same {
                // (Home waits on the page: this person's rows come from the same page)
                continueWatching = []
                picks = []
                forYou = nil
                taste = nil
                tasteRows = []
                // (asked for, in case the page doesn't say: it only sends what it sees change)
                try? await Task.sleep(for: .milliseconds(400))
                if continueWatching.isEmpty, let now = await ask("get", ["field": "continue_watching_preview"]) as? JSON {
                    apply("continue_watching_preview", now, save: true)
                }
            }
            refreshExtras()
        }
    }

    func addViewer(name: String, colour: String) {
        profileAutoChosen = false
        Task {
            await dx("viewers.add", name, colour)
            refreshExtras()
        }
    }

    /** A new device with one person to be: looks for the account's profiles (the plugin pulls them from the sync) for a minute or so */
    func watchForProfiles() {
        guard profileWatch == nil, account?.signedIn == true else { return }
        profileWatch = Task {
            for _ in 0..<12 {
                try? await Task.sleep(for: .seconds(5))
                if Task.isCancelled { break }
                if let v = await dx("phone.viewers") as? JSON, v.objects("list").count > 1 {
                    refreshExtras()
                    break
                }
            }
            profileWatch = nil
        }
    }

    func editViewer(_ id: String, name: String, colour: String) {
        Task {
            await dx("viewers.rename", id, name)
            await dx("viewers.recolour", id, colour)
            refreshExtras()
        }
    }

    func removeViewer(_ id: String) {
        Task {
            await dx("viewers.remove", id)
            refreshExtras()
        }
    }

    /** For Accounts.swift: asking the page (those two are private to this file) */
    func askPublic(_ op: String, _ args: JSON = [:]) async -> Any? { await ask(op, args) }
    func askDx(_ method: String, _ args: Any...) async -> Any? { await ask("dx", ["method": method, "args": args]) }

    /** Everything on show belongs to the account being left: gone while the page starts again with the other */
    func clearScreens() {
        account = nil
        continueWatching = []; rows = []; allRows = []; billboard = []; myList = []; forYou = nil; picks = []; airing = []
        taste = nil; tasteRows = []; tuning = false; tuningPending = false; described = [:]
        library = []; inLibrary = []; watchedIds = []; marksKnown = false; viewers = []; viewer = nil; details = nil
        profileChosen = false
        for key in ["state.ctx", "state.board", "state.continue_watching_preview", "x.billboard", "x.myList", "x.forYou", "x.picks", "x.airing", "x.layout", "x.viewers", "x.viewer", "x.described"] {
            cache.removeObject(forKey: key)
        }
    }

    // MARK: - Watch party (Lumen's own: end-to-end encrypted, everyone streams from their own addons)

    func party() async -> JSON? { await dx("phone.party.state") as? JSON }

    func startParty(name: String) async -> String? {
        await attempt("dx", ["method": "phone.party.start", "args": [name]])
    }

    func joinParty(code: String, name: String) async -> String? {
        await attempt("dx", ["method": "phone.party.join", "args": [code, name]])
    }

    func leaveParty() { Task { await dx("phone.party.leave") } }
    func sayInParty(_ text: String) { Task { await dx("phone.party.say", text) } }

    // MARK: - Playback: the page hands a stream over (dxbi-tv.js startHandoff), the player reports back

    private func handOver(_ json: String) {
        guard let payload = Json.decode(json) as? JSON else { return }
        let target = nextTarget
        nextTarget = .player
        page.js("window.__dxbiIos.active = \(Json.quote(json))")
        if target == .download {
            Downloads.shared.add(payload)
            // nothing plays: the page goes back to where it was
            closed(position: 0, duration: 0, ended: false, error: nil)
            return
        }
        if target == .otherApp {
            if let app = OtherApp.find(otherApp), let url = app.link(payload) {
                UIApplication.shared.open(url)
                watchedElsewhere(payload)
                closed(position: 0, duration: 0, ended: false, error: nil)
                return
            }
            show("No external player found: install Infuse, VLC, Outplayer or SenPlayer. Playing it here.")
        }
        // The player closed a moment ago on this same stream: taken back, with all it had downloaded (HeldPlayback)
        if payload.text("offline") == nil, let kept = HeldPlayback.take(url: payload.string("url")) {
            self.playback = kept
            kept.revive(startMs: payload.double("startMs"))
            PlayerHost.show(kept, stremio: self)
            return
        }
        let playback = Playback(payload: payload, stremio: self)
        self.playback = playback
        Log.note("player: \(payload.text("title") ?? "") \(payload.text("videoId") ?? "")")
        // (from here, not from a screen watching for it: the screens under the player don't update
        // while it covers them, so it never closed)
        PlayerHost.show(playback, stremio: self)
        // (the loading screen stays until the player has covered it)
        // (the loading screen carries on over the player, and goes when the first picture plays: PlayerScreen)
        stepLaunch("Loading the video", detail: launch?.detail, progress: 0.94)
        // (no picture in 40 seconds: as good as failed, so the page moves on to the next best)
        if launch != nil {
            Task {
                try? await Task.sleep(for: .seconds(40))
                // (or it started but the player never came up over the loading screen: it stayed for ever)
                guard self.playback === playback, launch != nil else { return }
                if !playback.ready || !PlayerHost.onScreen {
                    Log.note("no picture in 40 seconds (ready: \(playback.ready), player on screen: \(PlayerHost.onScreen))")
                    playback.finish(ended: false, error: "It didn't start")
                }
            }
        }
    }

    /** The player's external player button: carries on in another app */
    func playExternally(_ playback: Playback) {
        if playback.payload.text("offline") != nil { return playback.show("Downloads play here only") }
        guard let app = OtherApp.find(otherApp), let url = app.link(playback.payload) else {
            show("No external player found: install Infuse, VLC, Outplayer or SenPlayer.")
            return
        }
        watchedElsewhere(playback.payload)
        playback.finish(ended: false)
        UIApplication.shared.open(url)
    }

    /**
     * Played in another app: it keeps no watch progress here, so the episode (or film) counts as
     * watched once it's opened there
     */
    private func watchedElsewhere(_ payload: JSON) {
        guard payload.text("offline") == nil, let video = payload.text("videoId") else { return }
        setWatched(video: video, true)
    }

    /** The cast with their photos (TMDB, with a key in Lumen settings), or nil */
    func cast(_ type: String, _ imdb: String, names: [JSON]) async -> [JSON]? {
        (await ask("dx", ["method": "phone.cast", "args": [type, imdb, names]], timeout: 25) as? [Any])?.compactMap { $0 as? JSON }
    }

    /** Someone's page (TMDB, with a key in Lumen settings): by TMDB's id from the cast list, else by name. nil without a key */
    func person(id: String?, name: String) async -> JSON? {
        await ask("dx", ["method": "phone.person", "args": [id ?? "", name]], timeout: 40) as? JSON
    }

    /** Christmas at Home: the plugin's curated rows (and More Christmas with a TMDB key). The first time takes a few seconds */
    func christmas() async -> (rows: [ListRow], days: Int)? {
        guard let o = await ask("dx", ["method": "phone.christmas", "args": []], timeout: 60) as? JSON else { return nil }
        return (o.objects("rows").map(ListRow.init), o.int("daysToChristmas"))
    }

    /** The other seasons of an anime (the Kitsu addon keeps each season as a show of its own), in order; [] for one season */
    func animeSeasons(_ id: String) async -> [AnimeSeason] {
        let list = await ask("dx", ["method": "phone.animeSeasons", "args": [id]], timeout: 40) as? [Any]
        return (list ?? []).compactMap { $0 as? JSON }.map(AnimeSeason.init)
    }

    /** Titles like this one, for its page's Related row */
    func related(_ type: String, _ id: String, genres: [String]) async -> [Title] {
        let list = await ask("dx", ["method": "phone.related", "args": [type, id, genres]], timeout: 25) as? [Any]
        return (list ?? []).compactMap { $0 as? JSON }.map(Title.init)
    }

    @MainActor
    func rating(for id: String) async -> Int? {
        let r = await dx("phone.rating", id)
        return r.int
    }

    @MainActor
    func allRatings() async -> [String: Int] {
        guard let dict = await dx("phone.ratings") as? [String: Any] else { return [:] }
        var result: [String: Int] = [:]
        for (k, v) in dict {
            if let n = (v as? NSNumber)?.intValue, (1...10).contains(n) { result[k] = n }
        }
        return result
    }

    @MainActor
    /** [title]: what was rated. A title rated from memory isn't in the library, and the suggestions need its name and kind */
    func setRating(_ score: Int?, for id: String, title: Title? = nil) async {
        guard let title, !title.name.isEmpty else {
            _ = await dx("phone.setRating", id, score ?? 0)
            return
        }
        let meta: JSON = ["type": title.type == "movie" ? "movie" : "series", "name": title.name, "year": Int(title.year?.prefix(4) ?? "") ?? 0, "genres": Array(title.genres.prefix(4))]
        _ = await dx("phone.setRating", id, score ?? 0, meta)
    }

    /** The next episode to start, once the player has closed (the title's page starts it: TitleView) */
    var nextEpisode: (metaId: String, videoId: String)?

    /** Search for this (a name tapped on a title's page): the Search tab opens with it */
    var pendingSearch: String?

    func searchFor(_ text: String) { pendingSearch = text }

    /**
     * Report a problem, as Lumen does everywhere: what they typed, with Lumen's version, the phone,
     * settings, addon names and recent errors (no passwords, keys or stream links). The report's code
     * to post in Lumen's Discord, or what went wrong
     */
    func report(_ description: String) async -> Result<String, Page.Failure> {
        do {
            let code = try await page.call("dx", ["method": "report.send", "args": [description]], timeout: 30)
            return .success((code as? String) ?? "")
        } catch {
            return .failure(Page.Failure(message: error.localizedDescription))
        }
    }

    /** A download, from the phone */
    func play(download item: Downloads.Item) {
        guard let payload = Downloads.shared.playback(item) else { return }
        HeldPlayback.release()
        let playback = Playback(payload: payload, stremio: self)
        self.playback = playback
        PlayerHost.show(playback, stremio: self)
    }

    /** One video of the open title watched or not (a film: its own id); [upTo]: and the episodes before it in its season */
    func setWatched(video id: String, _ watched: Bool, upTo: Bool = false) {
        Task { await ask("videoWatched", ["id": id, "value": watched, "upTo": upTo]) }
    }

    /** Your stats (Lumen's Watch stats), or nil when they're switched off */
    func stats() async -> JSON? { await dx("phone.stats") as? JSON }

    func progress(position: Double, duration: Double, paused: Bool) {
        page.js("window.__dxbiTv && window.__dxbiTv.onProgress(\(Int(position * 1000)), \(Int(duration * 1000)), \(paused))")
    }

    func partyTick(position: Double, duration: Double, paused: Bool, buffering: Bool, rate: Double) {
        page.js("window.__dxbiTv && window.__dxbiTv.onPartyTick(\(Int(position * 1000)), \(Int(duration * 1000)), \(paused), \(buffering), \(rate))")
    }

    /** The player closed: the page records where it got to, and goes on to the next episode when asked */
    func closed(position: Double, duration: Double, ended: Bool, error: String?, next: Bool = false) {
        let active = playback?.payload
        // (the stream from last time wouldn't play: forgotten, and the title's page plays its own pick)
        if let video = savedAttempt {
            savedAttempt = nil
            if error != nil {
                PlayedLinks.forget(video)
                Log.note("the stream from last time didn't play: the long way")
            }
        }
        // On to the next episode: the loading screen first (it stays until the next one's picture plays), and
        // the title's page starts it as Play does (stream picked, failed ones passed over). The page's own
        // way (Stremio's player link for it) often found nothing, went back, and opened the last one again
        var goNext = false
        if next, let active, let upNext = active.object("next"), let id = upNext.text("id") {
            goNext = true
            nextEpisode = (metaId: playingTitle(active).id, videoId: id)
            if launch == nil { beginLaunch(playingTitle(active), episode: upNext.text("title"), text: "Starting the next episode", progress: 0.3) }
        }
        // closed before its first picture (it wouldn't play): the loading screen says so, unless it's a stream change
        // (with an error the page tries the next best stream, and says so: toast above)
        if launch != nil, switching == nil {
            if error != nil {
                stepLaunch("Starting", progress: 0.9)
                restartLaunchTimeout()
            } else {
                failLaunch("Couldn't play this stream", "Try Choose a Stream on the title's page.")
            }
        }
        // a download: it keeps its own place (the page didn't hand it over, so there's nothing to tell it)
        if let id = active?.text("offline") {
            Downloads.shared.leftAt(id, ms: position * 1000, ended: ended)
            playback = nil
            PlayerHost.hide()
            return
        }
        let close: JSON = [
            "timeMs": Int(position * 1000),
            "durationMs": Int(duration * 1000),
            "ended": ended,
            "next": next && !goNext,
            "error": error ?? NSNull(),
            "hash": active?.string("hash") ?? "",
            "videoId": active?.string("videoId") ?? "",
        ]
        page.js("window.__dxbiIos.active = ''; window.__dxbiIos.pendingClose = \(Json.quote(Json.encode(close))); window.__dxbiTv && window.__dxbiTv.playerClosed && window.__dxbiTv.playerClosed()")
        playback = nil
        PlayerHost.hide()
    }
}

/**
 * Where a stream's link really leads, once the check before Play has followed it. Debrid links through an addon
 * (StremThru's /playback/…) redirect to the debrid's own server, and the addon asks the debrid afresh on every request
 * (0.5 to 2.7 s, measured on the TV); the player makes several as it starts and seeks, so it's given the real address.
 */
/**
 * The stream last played of each film or episode (Stremio's player link, which carries the stream itself), kept on this
 * device for a few hours: Play or Resume on it again goes straight to that stream (no asking the addons, no choosing, no
 * checking). One that won't play is forgotten (Stremio.closed).
 */
enum PlayedLinks {
    private static let key = "lumen.playedLinks"
    private static let keep: TimeInterval = 6 * 3600

    /** The video a player link is for: its route ends …/type/id/videoId */
    static func video(of link: String) -> String? {
        let path = link.split(separator: "?").first.map(String.init) ?? link
        guard let last = path.split(separator: "/").last else { return nil }
        return String(last).removingPercentEncoding ?? String(last)
    }

    static func put(_ link: String) {
        guard let video = video(of: link) else { return }
        var all = UserDefaults.standard.dictionary(forKey: key) as? [String: [String: Any]] ?? [:]
        let now = Date().timeIntervalSince1970
        all = all.filter { now - (($0.value["at"] as? Double) ?? 0) < keep }
        all[video] = ["link": link, "at": now]
        UserDefaults.standard.set(all, forKey: key)
    }

    static func get(_ video: String) -> String? {
        guard let e = (UserDefaults.standard.dictionary(forKey: key) as? [String: [String: Any]])?[video],
              let link = e["link"] as? String, let at = e["at"] as? Double,
              Date().timeIntervalSince1970 - at < keep else { return nil }
        return link
    }

    static func forget(_ video: String) {
        var all = UserDefaults.standard.dictionary(forKey: key) as? [String: [String: Any]] ?? [:]
        all[video] = nil
        UserDefaults.standard.set(all, forKey: key)
    }
}

enum RedirectMemory {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var known: [String: (to: String, at: Date)] = [:]

    static func remember(_ from: String, _ to: String) {
        guard from != to else { return }
        lock.lock(); defer { lock.unlock() }
        if known.count > 50 { known = [:] }
        known[from] = (to, Date())
    }

    /** Where [from] leads, if it was followed in the last half hour */
    static func target(_ from: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard let hop = known[from], Date().timeIntervalSince(hop.at) < 1800 else { return nil }
        return hop.to
    }
}

/** Answers a redirect with itself, so the probe can look at where it goes */
private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

private extension Optional where Wrapped == Any {
    var int: Int? {
        if let n = self as? NSNumber { return n.intValue }
        return self as? Int
    }
}

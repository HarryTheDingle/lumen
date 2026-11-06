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

    /** Where the app's next stream goes: dx.bi's player, another app (Infuse, VLC…), or Downloads */
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
            stepLaunch("Trying another stream", detail: text.replacingOccurrences(of: "That stream didn't play. ", with: ""), progress: 0.9)
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
            // the library changing (watched something, added to the list, another device) changes dx.bi's rows
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

    private func loadHome() async {
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
                // (one person to be: nothing to ask)
                if viewers.count < 2 { profileChosen = true }
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
            if let o = await dx("phone.describe", continueWatching.prefix(5).map { [$0.title.type, $0.title.id] }) as? JSON {
                described = o.compactMapValues { $0 as? JSON }
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
            for _ in 0..<14 {
                if Task.isCancelled { return }
                if let answer = await dx("phone.picks") as? JSON {
                    let rows = answer.objects("rows").map(ListRow.init).filter { !$0.items.isEmpty }
                    // (changed only when there's something new: swapped every time, or for an empty
                    // answer while a fresh one was made, the rows on Home kept vanishing and coming back)
                    if rows != picks && (!rows.isEmpty || !answer.bool("stale")) { picks = rows }
                    cache.set(Json.encode(answer.objects("rows")), forKey: "x.picks")
                    if !answer.bool("stale") { break }
                }
                try? await Task.sleep(for: .seconds(1.5))
            }
            if !picks.isEmpty { forYou = nil; return }
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
        launch = now
    }

    func failLaunch(_ text: String, _ detail: String) {
        guard launch != nil else { return }
        launch?.text = text
        launch?.detail = detail
        launch?.progress = 1
        launch?.failed = true
    }

    func endLaunch() {
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
        if let id = details?.metaId { mark(id, watched: watched) }
        Task {
            await ask("watched", ["value": watched])
            try? await Task.sleep(for: .seconds(1))
            refreshExtras()
        }
    }

    /** Marks a title that isn't open as watched or not (a series: every episode) */
    func setWatched(_ title: Title, _ watched: Bool) {
        mark(title.id, watched: watched)
        withOpened(title) { await self.ask("watched", ["value": watched]) }
    }

    /** In the Library, or watched: from [inLibrary] and [watchedIds], else what the title came with */
    /** (a title in the Library tab is in it, whatever the marks say: a just-played film is saved only for a while, and couldn't be removed) */
    func isInLibrary(_ title: Title) -> Bool { marksKnown ? inLibrary.contains(title.id) || library.contains { $0.id == title.id } : title.inLibrary }
    func isWatched(_ title: Title) -> Bool { marksKnown ? watchedIds.contains(title.id) : title.watched }
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

    /** The open title's streams, described by dx.bi, with Just press Play's pick */
    func streams() async -> StreamChoices? {
        (await dx("phone.streams") as? JSON).map(StreamChoices.init)
    }

    /**
     * It's buffering: streams lighter than the one playing that fit the speed it's really getting
     * ([mbps], measured by the player; [nowMbps], the playing file's bitrate, 0 if unknown)
     */
    func lighter(mbps: Double, nowMbps: Double) async -> [StreamOption] {
        let list = await dx("phone.lighter", mbps, nowMbps) as? [Any] ?? []
        return list.compactMap { $0 as? JSON }.map { StreamOption($0.int("index", -1), $0) }.filter { $0.index >= 0 }
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
            StreamGroup(title: g.string("title"), options: g.objects("options").map { StreamOption($0.int("index", -1), $0) }.filter { $0.index >= 0 })
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
            let link = await dx("phone.play", option.index) as? String ?? option.play
            await ask("play", ["link": link])
        }
    }

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

    /** dx.bi settings → Play Trailers (on unless switched off): a title's page fades its trailer in */
    func playTrailers() async -> Bool {
        await settings().first { $0.key == "playTrailers" }?.value != "false"
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
        if let v = viewers.first(where: { $0.id == id }) { viewer = v }
        Task {
            await dx("viewers.select", id)
            // (Home waits on the page: this person's rows come from the same page)
            continueWatching = []
            picks = []
            forYou = nil
            refreshExtras()
        }
    }

    func addViewer(name: String, colour: String) {
        Task {
            await dx("viewers.add", name, colour)
            refreshExtras()
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
        library = []; inLibrary = []; watchedIds = []; marksKnown = false; viewers = []; viewer = nil; details = nil
        profileChosen = false
        for key in ["state.ctx", "state.board", "state.continue_watching_preview", "x.billboard", "x.myList", "x.forYou", "x.picks", "x.airing", "x.layout", "x.viewers", "x.viewer"] {
            cache.removeObject(forKey: key)
        }
    }

    // MARK: - Watch party (dx.bi's own: end-to-end encrypted, everyone streams from their own addons)

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
        let playback = Playback(payload: payload, stremio: self)
        self.playback = playback
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
                if self.playback === playback, !playback.ready, launch != nil { playback.finish(ended: false, error: "It didn't start") }
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

    /** The cast with their photos (TMDB, with a key in dx.bi settings), or nil */
    func cast(_ type: String, _ imdb: String, names: [JSON]) async -> [JSON]? {
        (await ask("dx", ["method": "phone.cast", "args": [type, imdb, names]], timeout: 25) as? [Any])?.compactMap { $0 as? JSON }
    }

    /** Someone's page (TMDB, with a key in dx.bi settings): by TMDB's id from the cast list, else by name. nil without a key */
    func person(id: String?, name: String) async -> JSON? {
        await ask("dx", ["method": "phone.person", "args": [id ?? "", name]], timeout: 40) as? JSON
    }

    /** Titles like this one, for its page's Related row */
    func related(_ type: String, _ id: String, genres: [String]) async -> [Title] {
        let list = await ask("dx", ["method": "phone.related", "args": [type, id, genres]], timeout: 25) as? [Any]
        return (list ?? []).compactMap { $0 as? JSON }.map(Title.init)
    }

    /** The next episode to start, once the player has closed (the title's page starts it: TitleView) */
    var nextEpisode: (metaId: String, videoId: String)?

    /** Search for this (a name tapped on a title's page): the Search tab opens with it */
    var pendingSearch: String?

    func searchFor(_ text: String) { pendingSearch = text }

    /**
     * Report a problem, as dx.bi does everywhere: what they typed, with dx.bi's version, the phone,
     * settings, addon names and recent errors (no passwords, keys or stream links). The report's code
     * to post in dx.bi's Discord, or what went wrong
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
        let playback = Playback(payload: payload, stremio: self)
        self.playback = playback
        PlayerHost.show(playback, stremio: self)
    }

    /** One video of the open title watched or not (a film: its own id); [upTo]: and the episodes before it in its season */
    func setWatched(video id: String, _ watched: Bool, upTo: Bool = false) {
        Task { await ask("videoWatched", ["id": id, "value": watched, "upTo": upTo]) }
    }

    /** Your stats (dx.bi's Watch stats), or nil when they're switched off */
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
                stepLaunch("That stream didn't play", detail: "Looking for another…", progress: 0.9)
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

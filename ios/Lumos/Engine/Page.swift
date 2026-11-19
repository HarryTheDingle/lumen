import Foundation
import LumosEngine
import UIKit
import WebKit

/**
 * Lumos's stand-in for Lumen's hidden Stremio page. Lumen's Stremio.swift (kept as it is) asks the page to do
 * things (`call`) and is told what changed (`onState`); here the same requests are answered by the Lumos engine,
 * with the same JSON the page and Lumen's plugin sent, so every Lumen screen works unchanged. No web page loads.
 */
@MainActor
final class Page: NSObject {
    static let origin = "https://web.stremio.com"
    static let home = URL(string: "https://web.stremio.com/")!

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /** (Lumen hosted its page in the view tree; Lumos has none, so this stays empty and unloaded) */
    let webView = WKWebView(frame: .zero)

    var onReady: (() -> Void)?
    var onGone: (() -> Void)?
    var onState: ((String, JSON?) -> Void)?
    var onEvent: ((String, JSON?) -> Void)?
    var onPlay: ((String) -> Void)?
    var onControl: ((JSON) -> Void)?
    var onToast: ((String) -> Void)?
    var onServersFound: (() -> Void)?

    /** The Lumos engine: addons, TorBox, sync */
    let core = Lumos(syncServer: Page.syncServer)
    private var watching = Set<String>()
    private var details: JSON?
    private var streamsPoll: Task<Void, Never>?
    private var lastBoard: JSON?
    /** What's playing (from the play link), for saving its place */
    private var playing: JSON?

    static let syncServer: String = {
        #if DEBUG
        if let local = ProcessInfo.processInfo.environment["LUMOS_SYNC"], !local.isEmpty { return local }
        #endif
        return "https://lumos-sync.hedigital.workers.dev"
    }()
    static let cinemeta = "https://v3-cinemeta.strem.io/manifest.json"

    static var utcOffset: Int { TimeZone.current.secondsFromGMT() / 60 }
    static var model: String {
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) { $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) } }
    }
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    static let tmdbKey: String = (Bundle.main.object(forInfoDictionaryKey: "LumenTmdbKey") as? String ?? "").trimmingCharacters(in: .whitespaces)

    private let stateFile = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("lumos-state.json")

    override init() {
        super.init()
        if let saved = try? String(contentsOf: stateFile, encoding: .utf8) { core.restore(saved: saved) }
        if let token = Keychain.get("token"), let account = Keychain.get("account") { core.useSession(token: token, account: account) }
        core.useStremio(authKey: Keychain.get("stremio"))
        configure()
        Task { @MainActor in
            self.onReady?()
            await self.syncNow()
        }
    }

    // MARK: - Lumos's own: sign-in, addons, sync

    var signedIn: Bool { core.signedIn }

    var addons: [String] {
        get { UserDefaults.standard.stringArray(forKey: "addons") ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: "addons"); configure() }
    }
    var torBoxKey: String {
        get { Keychain.get("torbox") ?? "" }
        set { Keychain.set("torbox", newValue); configure() }
    }

    func configure() {
        let list = [Self.cinemeta] + addons.filter { !$0.isEmpty && !$0.contains("v3-cinemeta") }
        core.configure(addonUrls: list, torBoxKey: torBoxKey.isEmpty ? nil : torBoxKey)
    }

    func signedIn(_ session: Session) {
        Keychain.set("token", session.token)
        Keychain.set("account", session.account)
        core.useSession(token: session.token, account: session.account)
        Task { await syncNow(); await refreshAll() }
    }

    func keepStremio(_ key: String?) {
        Keychain.set("stremio", key)
        core.useStremio(authKey: key)
    }

    func syncNow() async {
        if core.keepsStremio, let key = Keychain.get("stremio") {
            _ = try? await core.pullFromStremio(now: Self.now, key: key)
        }
        _ = try? await core.syncNow()
        save()
        tell("continue_watching_preview", parse(core.phone.continueWatching()))
    }

    func save() {
        try? FileManager.default.createDirectory(at: stateFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? core.state().write(to: stateFile, atomically: true, encoding: .utf8)
    }

    /** Home and Continue Watching again (after signing in, a profile change, new addons) */
    func refreshAll() async {
        tell("ctx", ctx)
        tell("continue_watching_preview", parse(core.phone.continueWatching()))
        if let board = try? await core.phone.board() { lastBoard = parse(board); tell("board", lastBoard) }
    }

    private var ctx: JSON { ["signedIn": core.signedIn, "user": ["email": "Lumos"] as JSON, "settings": [String: Any]()] }

    // MARK: - The page's interface

    func js(_ script: String) {}
    func send(_ op: String, _ args: JSON = [:], id: Int = 0) {}
    func watch(_ field: String) { watching.insert(field) }

    func call(_ op: String, _ args: JSON = [:], timeout: Double = 20) async throws -> Any? {
        switch op {
        case "load": return await load(args)
        case "get":
            switch args.string("field") {
            case "continue_watching_preview": return parse(core.phone.continueWatching())
            case "board": return lastBoard
            default: return nil
            }
        case "library":
            guard let meta = details?.object("meta") else { return nil }
            core.setInMyList(id: meta.string("id"), type: meta.string("type"), name: meta.string("name"), poster: meta.text("poster"), on: args.bool("add"), now: Self.now)
            await changed()
        case "watched":
            guard let id = details?.object("meta")?.text("id") else { return nil }
            core.setWatched(id: id, on: args.bool("value"), now: Self.now)
            await changed()
        case "videoWatched":
            core.setWatched(id: args.string("id"), on: args.bool("value"), now: Self.now)
            await changed()
        case "dismiss":
            core.forget(id: args.string("id"), now: Self.now)
            await changed()
        case "fromStart":
            fromStart = args.bool("on") ? args.text("videoId") : nil
        case "play":
            play(link: args.string("link"))
        case "dx":
            return await dx(args.string("method"), (args["args"] as? [Any]) ?? [])
        case "settings": return [Any]()
        default: break
        }
        return nil
    }

    private var fromStart: String?

    private func load(_ args: JSON) async -> Any? {
        let a = args.object("args") ?? [:]
        switch args.string("field") {
        case "board":
            tell("ctx", ctx)
            tell("continue_watching_preview", parse(core.phone.continueWatching()))
            if let board = try? await core.phone.board() { lastBoard = parse(board); tell("board", lastBoard) }
        case "meta_details":
            let meta = a.object("metaPath")
            guard let type = meta?.text("type"), let id = meta?.text("id") else { return nil }
            details = parse((try? await core.phone.open(type: type, id: id)) ?? "{}")
            tell("meta_details", details)
            if let stream = a.object("streamPath"), let video = stream.text("id") {
                core.phone.loadStreams(type: type, videoId: video)
                followStreams()
            }
        case "search":
            let extra = (a["extra"] as? [Any]) ?? []
            let query = extra.compactMap { $0 as? [Any] }.first { ($0.first as? String) == "search" }?.last as? String ?? ""
            if let found = try? await core.phone.search(query: query) { tell("search", parse(found)) }
        case "library":
            tell("library", ["items": myListTitles()])
        case "installed_addons":
            tell("installed_addons", ["items": addons.map { ["transportUrl": $0, "id": $0, "name": URL(string: $0)?.host() ?? $0, "installed": true] as JSON }])
        case "discover":
            tell("discover", ["state": "Err", "items": [Any]()])
        default: break
        }
        return nil
    }

    /** The title's details again as each addon answers (Lumen's title page waits on them to pick a stream) */
    private func followStreams() {
        streamsPoll?.cancel()
        streamsPoll = Task { @MainActor in
            for _ in 0..<80 {
                try? await Task.sleep(for: .milliseconds(350))
                if Task.isCancelled { return }
                details = parse(core.phone.details())
                tell("meta_details", details)
                let groups = details?.objects("streams") ?? []
                if !groups.isEmpty, groups.allSatisfy({ $0.string("state") != "Loading" }) { return }
            }
        }
    }

    private func dx(_ method: String, _ args: [Any]) async -> Any? {
        switch method {
        case "phone.viewers": return parse(core.phone.viewers())
        case "viewers.select":
            core.chooseProfile(id: args.first as? String ?? "")
            save()
            tell("continue_watching_preview", parse(core.phone.continueWatching()))
        case "phone.marks":
            return ["library": myListTitles().map { $0.string("id") }, "watched": Array(core.watchedIds())] as JSON
        case "phone.myList":
            let items = myListTitles()
            return items.isEmpty ? [Any]() : [["name": "My List", "items": items] as JSON]
        case "phone.picks": return ["rows": [Any](), "stale": false] as JSON
        case "phone.billboard":
            // (Lumen's billboard came from its picks: until they're ported, the first row's titles with art)
            let first = lastBoard?.objects("catalogs").first { !$0.objects("items").isEmpty }
            return Array((first?.objects("items") ?? []).prefix(8))
        case "phone.airing", "phone.related", "phone.networks", "phone.animeSeasons": return [Any]()
        case "phone.describe", "phone.ratings": return JSON()
        case "phone.streams": return parse(core.phone.streams())
        case "phone.play":
            guard let index = args.first as? Int ?? (args.first as? NSNumber)?.intValue else { return nil }
            return await link(index)
        case "phone.source":
            guard let index = number(args, 0).map(Int.init),
                  let s = try? await core.phone.source(index: Int32(index)) else { return nil }
            return parse(s)
        case "phone.alternatives":
            return parse(core.phone.alternatives(nowMbps: number(args, 0) ?? 0))
        case "phone.lighter":
            return Json.decode(core.phone.lighter(mbps: number(args, 0) ?? 0, nowMbps: number(args, 1) ?? 0))
        case "phone.previewSource":
            return (args.first as? String).flatMap { core.phone.previewSource(videoId: $0) }.flatMap(parse)
        case "phone.probe":
            return number(args, 0).flatMap { core.phone.probe(index: Int32($0)) }.flatMap(parse)
        case "phone.reject":
            return number(args, 0).map { core.phone.reject(index: Int32($0)) }
        case "skipSegments":
            guard args.count >= 3, let type = args[0] as? String, let meta = args[1] as? String, let video = args[2] as? String else { return [Any]() }
            return Json.decode((try? await core.phone.skipSegments(type: type, metaId: meta, videoId: video)) ?? "[]")
        default: return nil
        }
        return nil
    }

    private func number(_ args: [Any], _ i: Int) -> Double? {
        guard args.count > i else { return nil }
        return (args[i] as? NSNumber)?.doubleValue ?? (args[i] as? Double) ?? (args[i] as? Int).map(Double.init)
    }

    // MARK: - Playing

    /** The subtitle language a video starts in (Lumen took Stremio's, English unless changed); it shows only when the audio isn't in it */
    static let subtitleLanguage = "eng"

    /** A link that carries the stream itself (Lumen's PlayedLinks keeps it to play the same stream again) */
    private func link(_ index: Int) async -> String? {
        guard let s = try? await core.phone.source(index: Int32(index)), var source = parse(s) else {
            onToast?("This stream couldn't play: TorBox doesn't have it ready.")
            return nil
        }
        let meta = details?.object("meta")
        source["videoId"] = details?.object("selected")?.object("streamPath")?.text("id") ?? meta?.text("id") ?? ""
        source["metaId"] = meta?.text("id") ?? ""
        source["type"] = meta?.text("type") ?? "movie"
        source["name"] = meta?.text("name") ?? ""
        source["poster"] = meta?.text("poster") ?? NSNull()
        source["background"] = meta?.text("background") ?? NSNull()
        return "lumos:" + Data(Json.encode(source).utf8).base64EncodedString()
    }

    private func play(link: String) {
        Task { await start(link: link) }
    }

    private func start(link: String) async {
        guard link.hasPrefix("lumos:"), let data = Data(base64Encoded: String(link.dropFirst(6))),
              let source = Json.decode(String(decoding: data, as: UTF8.self)) as? JSON else {
            onToast?("This stream couldn't play.")
            return
        }
        let video = source.string("videoId")
        let metaId = source.string("metaId")
        let videos = (details?.object("meta")?.text("id") == metaId ? details?.object("meta")?.objects("videos") : nil) ?? []
        let now = videos.firstIndex { $0.string("id") == video }
        let episode = now.map { videos[$0] }
        let next = now.flatMap { i in videos.dropFirst(i + 1).first { ($0["season"] as? Int ?? 0) > 0 } }
        var start = 0.0
        if fromStart != video, let p = core.resumeOf(id: metaId), p.video == video { start = p.position * 1000 }
        fromStart = nil
        var payload: JSON = [
            "url": source.string("url"),
            "headers": source.object("headers") ?? [:],
            "title": source.string("name"),
            "videoId": video,
            "hash": source.text("play") ?? metaId,
            "startMs": start,
        ]
        if let e = episode { payload["subtitle"] = "S\(e["season"] ?? 0) E\(e["episode"] ?? 0)" + (e.string("title").isEmpty ? "" : " · \(e.string("title"))") }
        if let p = source.text("poster") { payload["poster"] = p }
        if let b = source.text("background") { payload["background"] = b }
        if let n = next {
            payload["next"] = ["id": n.string("id"), "title": "S\(n["season"] ?? 0) · E\(n["episode"] ?? 0) · \(n.string("title"))", "thumbnail": n.string("thumbnail")] as JSON
        }
        // Skip Intro and the credits (fetched ahead while the title's page is up: 1.5 s at most here), and subtitles (2 s)
        let type = source.string("type")
        let phone = core.phone
        async let skips = Self.within(1.5) { (try? await phone.skipSegments(type: type, metaId: metaId, videoId: video)) ?? "[]" }
        async let subtitles = Self.within(2.5) { (try? await phone.subtitles(type: type, videoId: video, language: Self.subtitleLanguage)) ?? "[]" }
        payload["skips"] = Json.decode(await skips ?? "[]") ?? [Any]()
        payload["subtitles"] = Json.decode(await subtitles ?? "[]") ?? [Any]()
        payload["subtitleLanguage"] = Self.subtitleLanguage
        payload["autoSkip"] = "off"
        payload["upNext"] = "10"
        playing = ["metaId": metaId, "type": source.string("type"), "name": source.string("name"), "poster": source.text("poster") ?? NSNull(), "videoId": video]
        onPlay?(Json.encode(payload))
    }

    /** [work]'s answer, or nil if it takes longer than [seconds] */
    private static func within(_ seconds: Double, _ work: @escaping @Sendable () async -> String) async -> String? {
        await withTaskGroup(of: String?.self) { group in
            group.addTask { await work() }
            group.addTask { try? await Task.sleep(for: .seconds(seconds)); return nil }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    /** The player's place, every few seconds */
    func progress(position: Double, duration: Double) {
        guard let p = playing, duration > 0, position > 5 else { return }
        core.saveProgress(id: p.string("metaId"), type: p.string("type"), video: p.string("videoId"), name: p.string("name"),
                          poster: p.text("poster"), position: position, duration: duration, now: Self.now)
    }

    /** The player closed: its place is kept, synced, and told to Stremio (while Lumen's in use) */
    func closed(position: Double, duration: Double) {
        guard let p = playing else { return }
        playing = nil
        progress(position: position, duration: duration)
        save()
        Task {
            await syncNow()
            if core.keepsStremio, duration > 0, position > 5 {
                _ = try? await core.tellStremio(id: p.string("metaId"), type: p.string("type"), video: p.string("videoId"), name: p.string("name"),
                                                poster: p.text("poster"), position: position, duration: duration, now: Self.now)
            }
        }
    }

    // MARK: -

    private func myListTitles() -> [JSON] {
        (parse(core.phone.myList())?.objects("items") ?? [])
    }

    private func changed() async {
        save()
        if let d = details?.object("meta"), let type = d.text("type"), let id = d.text("id") {
            details = parse((try? await core.phone.open(type: type, id: id)) ?? "{}")
            tell("meta_details", details)
        }
        tell("continue_watching_preview", parse(core.phone.continueWatching()))
        Task { _ = try? await core.syncNow() }
    }

    private func tell(_ field: String, _ data: JSON?) {
        guard let data else { return }
        onState?(field, data)
    }

    private func parse(_ text: String) -> JSON? { Json.decode(text) as? JSON }

    static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/** Small secrets (the sync key, the TorBox key, the Stremio session) in the Keychain, this device only */
enum Keychain {
    private static let service = "com.harrythedingle.lumos"

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: key, kSecReturnData as String: true]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ key: String, _ value: String?) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        SecItemDelete(q as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

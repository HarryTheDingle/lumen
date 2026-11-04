import Foundation
import Observation
import SwiftUI
import UserNotifications

/**
 * Discover's rows, from the internet directly (not through Stremio's page): what's on each streaming
 * service (the free Streaming Catalogs addon, for this country), Cinemeta's popular and genre catalogs,
 * and the films out on digital this year (dvdsreleasedates.com, which links each to IMDb). Kept for a
 * while on the device so Discover opens at once.
 */
@MainActor
@Observable
final class DiscoverFeed {
    static let shared = DiscoverFeed()

    /** A streaming service: its catalog's code at the addon, and its colours for Discover's look */
    struct Platform: Identifiable, Hashable {
        var id: String
        var name: String
        var colour: Color
        /** The second colour of its gradient */
        var deep: Color
        var types: [String] = ["series", "movie"]
    }

    /** UK services first (Lumen's at home there): the addon has each one for the country asked */
    static let platforms: [Platform] = [
        Platform(id: "nfx", name: "Netflix", colour: Color(red: 0.898, green: 0.035, blue: 0.078), deep: Color(red: 0.25, green: 0.0, blue: 0.02)),
        Platform(id: "amp", name: "Prime Video", colour: Color(red: 0.0, green: 0.66, blue: 0.88), deep: Color(red: 0.02, green: 0.13, blue: 0.24)),
        Platform(id: "dnp", name: "Disney+", colour: Color(red: 0.13, green: 0.36, blue: 0.95), deep: Color(red: 0.03, green: 0.07, blue: 0.27)),
        Platform(id: "atp", name: "Apple TV+", colour: Color(white: 0.85), deep: Color(white: 0.12)),
        Platform(id: "hbm", name: "HBO Max", colour: Color(red: 0.45, green: 0.25, blue: 0.95), deep: Color(red: 0.1, green: 0.04, blue: 0.25)),
        Platform(id: "pmp", name: "Paramount+", colour: Color(red: 0.0, green: 0.39, blue: 1.0), deep: Color(red: 0.0, green: 0.09, blue: 0.26)),
        Platform(id: "sgo", name: "Sky", colour: Color(red: 0.0, green: 0.55, blue: 0.85), deep: Color(red: 0.2, green: 0.05, blue: 0.3)),
        Platform(id: "bbc", name: "BBC iPlayer", colour: Color(red: 1.0, green: 0.3, blue: 0.6), deep: Color(red: 0.25, green: 0.03, blue: 0.15)),
        Platform(id: "itv", name: "ITVX", colour: Color(red: 0.87, green: 0.92, blue: 0.32), deep: Color(red: 0.12, green: 0.15, blue: 0.04)),
        Platform(id: "al4", name: "Channel 4", colour: Color(red: 0.2, green: 0.8, blue: 0.75), deep: Color(red: 0.03, green: 0.15, blue: 0.15)),
    ]

    /** A film out (or coming) on digital, with its date */
    struct Release: Identifiable, Hashable, Codable {
        var id: String
        var name: String
        var date: Date
        var title: Title {
            var t = Title(id: id, type: "movie", name: name)
            t.poster = "https://images.metahub.space/poster/medium/\(id)/img"
            return t
        }
    }

    private static let addon = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
    private static let cinemeta = "https://v3-cinemeta.strem.io"
    private static var country: String { Locale.current.region?.identifier ?? "GB" }
    /** The addon's settings, in its address: the services, no poster key, this country */
    private static var config: String {
        Data("\(platforms.map(\.id).joined(separator: ","))::\(country)".utf8).base64EncodedString()
    }

    private var memory: [String: (at: Date, items: [Item])] = [:]

    /** A catalog's title, with when it came out (for "New on…") */
    struct Item: Codable, Hashable {
        var json: Data
        var released: Date?
        var title: Title { (try? JSONSerialization.jsonObject(with: json) as? JSON).map(Title.init) ?? Title(id: "", type: "", name: "") }
        var genres: [String] { title.genres }
    }

    // MARK: - Catalogs

    /** What's on a service: popular first */
    func platform(_ p: Platform, _ type: String) async -> [Item] {
        await catalog("\(Self.addon)/\(Self.config)/catalog/\(type)/\(p.id).json", keep: 6 * 3600)
    }

    /** Cinemeta's popular titles, of one genre or all */
    func popular(_ type: String, genre: String? = nil) async -> [Item] {
        let extra = genre.map { "/genre=\($0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? $0)" } ?? ""
        return await catalog("\(Self.cinemeta)/catalog/\(type)/top\(extra).json", keep: 6 * 3600)
    }

    private func catalog(_ url: String, keep: TimeInterval) async -> [Item] {
        if let hit = memory[url], Date().timeIntervalSince(hit.at) < keep { return hit.items }
        if let saved = Self.load([Item].self, url, keep: keep) {
            memory[url] = (Date(), saved)
            return saved
        }
        guard let u = URL(string: url), let (data, _) = try? await URLSession.shared.data(from: u),
              let o = try? JSONSerialization.jsonObject(with: data) as? JSON else { return memory[url]?.items ?? [] }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let items: [Item] = o.objects("metas").compactMap { meta in
            guard Title.watchable.contains(meta.string("type")), let json = try? JSONSerialization.data(withJSONObject: meta) else { return nil }
            return Item(json: json, released: meta.text("released").flatMap { iso.date(from: $0) })
        }
        memory[url] = (Date(), items)
        Self.save(items, url)
        return items
    }

    // MARK: - Digital releases

    /**
     * A month of films out on digital (in date order). Read from the site's month page: each film sits
     * under its date and links to IMDb, so there's no guessing which film it is.
     */
    func digital(year: Int, month: Int) async -> [Release] {
        let key = "digital-\(year)-\(month)"
        let now = Calendar.current.dateComponents([.year, .month], from: Date())
        // (a past month doesn't change: kept a week; this one and later ones a day)
        let keep: TimeInterval = (year, month) < (now.year ?? 0, now.month ?? 0) ? 7 * 86400 : 86400
        if let saved = Self.load([Release].self, key, keep: keep) { return saved }
        let english = DateFormatter()
        english.locale = Locale(identifier: "en_US_POSIX")
        let name = english.monthSymbols[month - 1].lowercased()
        guard let url = URL(string: "https://www.dvdsreleasedates.com/digital-releases/\(year)/\(month)/digital-hd-releases-\(name)-\(year)") else { return [] }
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (Macintosh; Lumen)", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: request) else { return [] }
        let releases = Self.parseDigital(String(decoding: data, as: UTF8.self))
        if !releases.isEmpty { Self.save(releases, key) }
        return releases
    }

    /** The site's page: dates as headings ("Tuesday November 3, 2026"), then each film with its IMDb link */
    static func parseDigital(_ html: String) -> [Release] {
        let dateFormat = DateFormatter()
        dateFormat.locale = Locale(identifier: "en_US_POSIX")
        dateFormat.dateFormat = "EEEE MMMM d, yyyy"
        var out: [Release] = []
        var seen = Set<String>()
        var date: Date?
        let token = /class='reldate[^']*'>(?:<a[^>]*><\/a>)?([A-Za-z]+ [A-Za-z]+ \d{1,2}, \d{4})|<a style='color:#000;' href='\/movies\/\d+\/[^']*'>([^<]+)<\/a>.{0,400}?title\/(tt\d+)\//
        for m in html.matches(of: token.dotMatchesNewlines()) {
            if let d = m.output.1 {
                date = dateFormat.date(from: String(d))
            } else if let name = m.output.2, let id = m.output.3, let date, !seen.contains(String(id)) {
                seen.insert(String(id))
                out.append(Release(id: String(id), name: String(name).unescaped, date: date))
            }
        }
        return out
    }

    /** This year's releases so far (and the next few weeks'), newest first */
    func digitalYear() async -> [Release] {
        let now = Calendar.current.dateComponents([.year, .month], from: Date())
        guard let year = now.year, let month = now.month else { return [] }
        var all: [Release] = []
        // (the coming month too: its films are "Coming to Digital")
        await withTaskGroup(of: [Release].self) { group in
            for m in 1...min(month + 1, 12) { group.addTask { await self.digital(year: year, month: m) } }
            for await list in group { all += list }
        }
        var seen = Set<String>()
        return all.sorted { $0.date > $1.date }.filter { seen.insert($0.id).inserted }
    }

    // MARK: - Alerts

    /**
     * Films in the library coming out on digital: a notification on the day (10 am). Only the library's,
     * as asked; each one once.
     */
    func scheduleAlerts(library: [Title], releases: [Release]) async {
        let mine = Set(library.filter { $0.type == "movie" }.map(\.id))
        let today = Calendar.current.startOfDay(for: Date())
        let coming = releases.filter { mine.contains($0.id) && $0.date >= today }
        guard !coming.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        guard (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        let pending = Set(await center.pendingNotificationRequests().map(\.identifier))
        for r in coming where !pending.contains("digital-\(r.id)") {
            let content = UNMutableNotificationContent()
            content.title = "\(r.name) is out on digital"
            content.body = "It's in your Library: see if your addons have it."
            var when = Calendar.current.dateComponents([.year, .month, .day], from: r.date)
            when.hour = 10
            let request = UNNotificationRequest(identifier: "digital-\(r.id)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: when, repeats: false))
            try? await center.add(request)
        }
    }

    // MARK: - Kept on the device (Caches: the system may clear it, and it's fetched again)

    private static let folder: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Discover", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static func file(_ key: String) -> URL {
        folder.appendingPathComponent(String(key.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : "_" }).suffix(120) + ".json")
    }

    private static func save<T: Encodable>(_ value: T, _ key: String) {
        try? JSONEncoder().encode(value).write(to: file(key), options: .atomic)
    }

    private static func load<T: Decodable>(_ type: T.Type, _ key: String, keep: TimeInterval) -> T? {
        let url = file(key)
        guard let at = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate,
              Date().timeIntervalSince(at) < keep, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}

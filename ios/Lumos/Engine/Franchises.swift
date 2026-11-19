import Foundation
import Observation

/**
 * Discover's franchises and filmmakers. The app carries a list of its own (Franchises.json, made by
 * tools/franchises-build.mjs: every title in it was found at Cinemeta under its name and year), so both
 * sections are there without a TMDB key. With a key in Lumen settings the plugin adds to them: every film
 * of a franchise's collections, a filmmaker's whole work, and the franchises and filmmakers that the
 * Library and what's been watched lead to.
 */

/** A film or series of a franchise or a filmmaker, as the list has it */
struct ListedTitle: Hashable, Decodable {
    var id: String
    var name: String
    var year: Int

    func title(_ type: String) -> Title {
        var t = Title(id: id, type: type, name: name)
        t.year = String(year)
        return t
    }
}

/** A film franchise: its films in the order they came out, and the series that go with them */
struct Franchise: Identifiable, Hashable, Decodable {
    /** The list's own name for it ("bond"), or "tmdb:" and TMDB's collection id for one found there */
    var id: String
    var name: String
    /** The film whose backdrop is its card's picture */
    var art: String = ""
    /** Films whose TMDB collections fill it in with a key (none: the list is all of it, as Marvel's is) */
    var seeds: [String] = []
    var films: [ListedTitle] = []
    var series: [ListedTitle] = []
    /** One found at TMDB: its own picture, and the films of the Library that led to it */
    var backdrop: String?
    var led: [String]?

    private enum CodingKeys: String, CodingKey { case id, name, art, seeds, films, series }

    var picture: URL? { URL(string: backdrop ?? "https://images.metahub.space/background/medium/\(art)/img") }
    /** TMDB's collection id, for one found there */
    var collection: String? { id.hasPrefix("tmdb:") ? String(id.dropFirst(5)) : nil }
}

/** A director or writer: TMDB's person id where it's known, and some of their films for when there's no key */
struct Filmmaker: Identifiable, Hashable, Decodable {
    var id: String
    /** As the tile names them, and as TMDB and Wikipedia do (the Coen Brothers are Joel Coen there) */
    var name: String
    var person: String = ""
    var tmdb: String = ""
    var wiki: String = ""
    var role: String
    var films: [ListedTitle] = []
    var series: [ListedTitle] = []
    /** One found at TMDB: their photo */
    var photo: String?

    private enum CodingKeys: String, CodingKey { case id, name, person, tmdb, wiki, role, films, series }
}

/** The app's own list (Franchises.json) */
enum Listed {
    private struct File: Decodable {
        var franchises: [Franchise]
        var filmmakers: [Filmmaker]
    }

    private static let file: File = {
        guard let url = Bundle.main.url(forResource: "Franchises", withExtension: "json"), let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(File.self, from: data) else { return File(franchises: [], filmmakers: []) }
        return file
    }()

    static var franchises: [Franchise] { file.franchises }
    static var filmmakers: [Filmmaker] { file.filmmakers }

    static func franchise(_ id: String) -> Franchise? { franchises.first { $0.id == id } }
    /** By TMDB's id, else by either of their names */
    static func filmmaker(id: String, name: String) -> Filmmaker? {
        filmmakers.first { !id.isEmpty && $0.tmdb == id } ?? filmmakers.first { $0.name == name || $0.person == name }
    }
}

/**
 * What Discover's two sections show, in order: the franchises and filmmakers closest to what this profile
 * has in its Library or has watched first, then the rest of the list as it's written. Worked out when
 * Discover opens (not as marks change: a row that reshuffled under a finger would be worse than one a
 * little behind).
 */
@MainActor
@Observable
final class DiscoverLinks {
    static let shared = DiscoverLinks()

    private(set) var franchises: [Franchise] = Listed.franchises
    private(set) var filmmakers: [Filmmaker] = Listed.filmmakers
    /** A franchise that's been started: the next film of it to watch */
    struct Next: Identifiable, Hashable {
        var franchise: Franchise
        var title: Title
        var id: String { title.id }
    }
    private(set) var next: [Next] = []
    /** Filmmakers' photos by their id (TMDB's with a key, else Wikipedia's), kept from one launch to the next */
    private(set) var faces: [String: String] = (UserDefaults.standard.dictionary(forKey: "lumen.filmmaker.faces") as? [String: String]) ?? [:]

    /** What TMDB found for this profile (nil: not asked, or no key) */
    @ObservationIgnored private var found: (franchises: [Franchise], filmmakers: [Filmmaker], counts: [String: Int])?
    @ObservationIgnored private var askedFor: String?
    @ObservationIgnored private var askedFaces = false

    /** The films this profile has a mark on: in the Library, being watched, or watched */
    private func mine(_ stremio: Stremio) -> Set<String> {
        stremio.watchedIds.union(stremio.inLibrary).union(stremio.library.map(\.id)).union(stremio.continueWatching.map(\.title.id))
    }

    /** How many of a franchise's films are watched (not the one being watched now) */
    func watched(_ f: Franchise, _ stremio: Stremio) -> Int {
        f.films.filter { stremio.isWatched($0.title("movie")) }.count
    }

    /** Puts both sections in order for this profile, then asks TMDB (with a key) what else its films lead to */
    func refresh(_ stremio: Stremio) async {
        arrange(stremio)
        async let photos: Void = loadFaces(stremio)
        let ids = filmsToAsk(stremio)
        let key = (stremio.viewer?.id ?? "") + ":" + ids.joined(separator: ",")
        if !ids.isEmpty, askedFor != key {
            askedFor = key
            if let links = await stremio.connections(ids) {
                found = links
                arrange(stremio)
            } else {
                // (no key, or the page isn't up yet: asked again the next time Discover opens)
                askedFor = nil
            }
        }
        await photos
    }

    /** Up to forty films to ask TMDB about: the Library's first (newest first), then what's been watched */
    private func filmsToAsk(_ stremio: Stremio) -> [String] {
        var ids: [String] = [], seen = Set<String>()
        let lists = [stremio.library.filter { $0.type == "movie" }.map(\.id), stremio.continueWatching.filter { $0.title.type == "movie" }.map(\.title.id),
                     stremio.watchedIds.sorted { $0.count != $1.count ? $0.count > $1.count : $0 > $1 }]
        for id in lists.joined() where id.hasPrefix("tt") && seen.insert(id).inserted { ids.append(id) }
        return Array(ids.prefix(40))
    }

    private func arrange(_ stremio: Stremio) {
        let mine = mine(stremio)
        func score(_ titles: [ListedTitle]) -> Int { titles.filter { mine.contains($0.id) }.count }
        /** The most of this profile's films first; the rest stay as they were */
        func ordered<T>(_ list: [T], _ score: (T) -> Int) -> [T] {
            list.enumerated().map { ($0.offset, score($0.element), $0.element) }
                .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0 < $1.0 }.map(\.2)
        }

        // franchises: the list's, and before them the ones TMDB found that the list doesn't have
        let listed = Listed.franchises
        let listedFilms = Set(listed.flatMap { $0.films.map(\.id) })
        let extra = (found?.franchises ?? []).filter { f in !(f.led ?? []).contains { listedFilms.contains($0) } }
        franchises = ordered(extra + listed) { $0.led?.count ?? score($0.films) }

        // filmmakers: the list's, moved up by what TMDB counted for them, and the ones only TMDB found
        let counts = found?.counts ?? [:]
        let known = Listed.filmmakers
        let others = (found?.filmmakers ?? []).filter { p in !known.contains { $0.tmdb == p.id || $0.person == p.name || $0.name == p.name } }
        filmmakers = ordered(others + known) { max(score($0.films), counts[$0.tmdb.isEmpty ? $0.id : $0.tmdb] ?? 0, counts["name:" + $0.person] ?? 0) }

        // started franchises: the film after the last one watched (else the first one not watched)
        var upNext: [Next] = []
        for f in franchises where !f.films.isEmpty {
            let seen = f.films.map { stremio.isWatched($0.title("movie")) }
            guard seen.contains(true), seen.contains(false) else { continue }
            let last = seen.lastIndex(of: true) ?? 0
            let i = seen[last...].firstIndex(of: false) ?? seen.firstIndex(of: false) ?? 0
            let film = f.films[i]
            // (one that isn't out yet isn't next to watch)
            if film.year > Calendar.current.component(.year, from: Date()) { continue }
            if !upNext.contains(where: { $0.id == film.id }) { upNext.append(Next(franchise: f, title: film.title("movie"))) }
        }
        next = Array(upNext.prefix(15))
    }

    /** Photos for the list's filmmakers: asked once each time the app runs, and again while any are missing */
    private func loadFaces(_ stremio: Stremio) async {
        guard !askedFaces else { return }
        askedFaces = true
        let people = Listed.filmmakers
        guard let photos = await stremio.faces(people) else {
            askedFaces = false
            return
        }
        var all = faces
        for (id, photo) in photos { all[id] = photo }
        if all != faces {
            faces = all
            UserDefaults.standard.set(all, forKey: "lumen.filmmaker.faces")
        }
        if people.contains(where: { all[$0.id] == nil }) { askedFaces = false }
    }
}

// MARK: - The plugin's side (TMDB, with the key from Lumen settings)

extension Stremio {
    /** A franchise's films from TMDB, oldest first: its collection by id, or the collections its [seeds] are in. nil without a key */
    func franchise(collection: String?, seeds: [String]) async -> (name: String, overview: String?, backdrop: String?, films: [Title])? {
        guard let o = await askDx("phone.franchise", collection ?? "", seeds) as? JSON else { return nil }
        return (o.string("name"), o.text("overview"), o.text("backdrop"), o.objects("films").map(Title.init))
    }

    /** A director's or writer's page (TMDB): as [person], with what they directed and wrote in rows of their own. nil without a key */
    func filmmaker(id: String?, name: String) async -> JSON? {
        await askDx("phone.filmmaker", id ?? "", name) as? JSON
    }

    /** The franchises and filmmakers these films lead to (TMDB), and how many of the films each filmmaker made. nil without a key */
    func connections(_ ids: [String]) async -> (franchises: [Franchise], filmmakers: [Filmmaker], counts: [String: Int])? {
        guard let o = await askDx("phone.connections", ids) as? JSON else { return nil }
        let franchises = o.objects("franchises").map { f -> Franchise in
            var out = Franchise(id: "tmdb:" + f.string("id"), name: f.string("name"))
            out.backdrop = f.text("backdrop")
            out.led = f.strings("films")
            return out
        }
        var counts: [String: Int] = [:]
        let filmmakers = o.objects("filmmakers").map { p -> Filmmaker in
            let jobs = p.strings("jobs")
            let role = jobs.contains("Director") ? (jobs.contains("Writer") ? "Writer & Director" : "Director") : "Writer"
            var out = Filmmaker(id: p.string("id"), name: p.string("name"), role: role)
            out.person = out.name
            out.tmdb = out.id
            out.photo = p.text("photo")
            counts[out.id] = p.strings("films").count
            counts["name:" + out.name] = p.strings("films").count
            return out
        }
        return (franchises, filmmakers, counts)
    }

    /** Photos for these filmmakers, by their id: TMDB's with a key, else Wikipedia's. nil when the plugin couldn't be asked */
    func faces(_ people: [Filmmaker]) async -> [String: String]? {
        let list: [JSON] = people.map { ["key": $0.id, "id": $0.tmdb, "name": $0.person.isEmpty ? $0.name : $0.person, "wiki": $0.wiki] }
        guard let o = await askDx("phone.faces", list) as? JSON else { return nil }
        return o.compactMapValues { $0 as? String }
    }
}

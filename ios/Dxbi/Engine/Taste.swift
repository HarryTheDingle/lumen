import Foundation

/**
 * What a profile likes, told once when it first opens (TasteSetup) so Home has suggestions before there's any
 * watching to go on: the genres they chose, and a few titles they've enjoyed. Kept on this device, one record
 * for each person (profile) of each account. Once real recommendations exist (after some watching) they take
 * Home's place; the chosen titles only stand in until then.
 */
struct Taste: Codable, Equatable {
    /** A title picked as liked: enough to draw it and to find more like it */
    struct Seed: Codable, Equatable, Hashable, Identifiable {
        var id: String
        var type: String
        var name: String
        var poster: String?
        var genres: [String]

        init(_ t: Title) {
            id = t.id
            type = t.type
            name = t.name
            poster = t.poster
            genres = t.genres
        }

        var title: Title {
            var t = Title(id: id, type: type, name: name)
            t.poster = poster
            t.genres = genres
            return t
        }
    }

    /** Names of Genre.all */
    var genres: [String] = []
    var titles: [Seed] = []
    /** What they'd rather be suggested: "movie" or "series" (nil: a mix). The plugin's picks lean that way */
    var prefer: String?
    /** They closed it without choosing: not asked again (it's still in Profile → Tune Suggestions) */
    var skipped = false

    var isEmpty: Bool { genres.isEmpty && titles.isEmpty }

    private static func key(_ account: String?, _ viewer: String?) -> String { "taste.\(account ?? "guest").\(viewer ?? "main")" }

    static func load(account: String?, viewer: String?) -> Taste? {
        UserDefaults.standard.data(forKey: key(account, viewer)).flatMap { try? JSONDecoder().decode(Taste.self, from: $0) }
    }

    static func save(_ taste: Taste, account: String?, viewer: String?) {
        UserDefaults.standard.set(try? JSONEncoder().encode(taste), forKey: key(account, viewer))
    }

    static func forget(account: String?, viewer: String?) {
        UserDefaults.standard.removeObject(forKey: key(account, viewer))
    }
}

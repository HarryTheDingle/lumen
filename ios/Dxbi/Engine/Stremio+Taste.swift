import Foundation

/** A profile's taste (Taste.swift): read when the profile is known, offered once, and turned into Home's stand-in rows */
extension Stremio {
    private var tasteAccount: String? { currentAccountId }

    /**
     * This device's copy first (so Home has it at once), then the account's: the plugin keeps it with the profiles
     * and syncs it to the account's other devices. A copy only this device has goes up to the account.
     */
    func loadTaste() {
        let id = viewer?.id
        taste = Taste.load(account: tasteAccount, viewer: id)
        if let t = taste, !t.isEmpty { Task { tasteRows = await Self.tasteRows(for: t) } } else { tasteRows = [] }
        guard let id else { return }
        Task {
            if let o = await askDx("phone.taste", id) as? JSON, let data = try? JSONSerialization.data(withJSONObject: o), let remote = try? JSONDecoder().decode(Taste.self, from: data) {
                guard viewer?.id == id else { return }
                if remote != taste {
                    taste = remote
                    Taste.save(remote, account: tasteAccount, viewer: id)
                    tasteRows = remote.isEmpty ? [] : await Self.tasteRows(for: remote)
                }
            } else if let local = taste {
                _ = await askDx("phone.setTaste", id, Self.object(local))
            }
        }
    }

    private static func object(_ t: Taste) -> JSON {
        ((try? JSONEncoder().encode(t)).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? JSON) ?? [:]
    }

    /** Asked once, on a profile with nothing watched: not again after Skip, or once it has chosen */
    func offerTuning() {
        // (not over the sign-in screen: the page answers for nobody there too)
        guard account?.signedIn == true || guest else { return }
        guard profileChosen, !tuningPending, taste == nil else { return }
        tuningPending = true
    }

    func finishTaste(_ t: Taste) {
        taste = t
        Taste.save(t, account: tasteAccount, viewer: viewer?.id)
        if let id = viewer?.id { Task { _ = await askDx("phone.setTaste", id, Self.object(t)) } }
        tuningPending = false
        tuning = false
        Task { tasteRows = t.isEmpty ? [] : await Self.tasteRows(for: t) }
    }

    func resetTaste() {
        Taste.forget(account: tasteAccount, viewer: viewer?.id)
        taste = nil
        tasteRows = []
    }

    /**
     * Picked for You: popular films and series in the chosen genres, taking turns; then Because you liked …
     * for up to three chosen titles, popular in that title's own genres (Cinemeta has no "similar to"), with
     * the titles chosen left out.
     */
    static func tasteRows(for t: Taste) async -> [ListRow] {
        let feed = DiscoverFeed.shared
        let chosen = Set(t.titles.map(\.id))
        var rows: [ListRow] = []

        let genres = t.genres.compactMap { name in Genre.all.first { $0.name == name } }.filter { !$0.anime }
        if !genres.isEmpty {
            var lists: [[Title]] = []
            for g in genres.prefix(4) {
                for key in [g.key] + (g.also.map { [$0] } ?? []) {
                    async let movies = feed.popular("movie", genre: key)
                    async let series = feed.popular("series", genre: key)
                    let (m, s) = await (movies, series)
                    lists.append(m.map(\.title))
                    lists.append(s.map(\.title))
                }
            }
            var seen = Set<String>(chosen)
            var out: [Title] = []
            for i in 0..<(lists.map(\.count).max() ?? 0) {
                for l in lists where i < l.count && seen.insert(l[i].id).inserted { out.append(l[i]) }
            }
            if out.count >= 4 { rows.append(ListRow(name: "Picked for You", items: Array(out.prefix(30)))) }
        }

        for seed in t.titles.prefix(3) {
            guard let genre = seed.genres.first else { continue }
            let type = seed.type == "movie" ? "movie" : "series"
            let list = await feed.popular(type, genre: genre).map(\.title)
            let more = list.filter { !chosen.contains($0.id) }.prefix(25)
            if more.count >= 4 { rows.append(ListRow(name: "Because you liked \(seed.name)", items: Array(more))) }
        }
        return rows
    }
}

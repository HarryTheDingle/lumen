import SwiftUI

/**
 * Genres in Discover, as Apple TV's Search page does them: tall tiles with a tinted still of a well-known
 * title and the name at the bottom left; and Christmas at Home, a festive page of its own (red and green,
 * snow, a countdown) that leads the tiles from mid-November to early January.
 */

/** The festive season, and how long to Christmas */
enum ChristmasSeason {
    /** Mid-November to early January: the festive card and the first tile */
    static var inSeason: Bool {
        let c = Calendar.current.dateComponents([.month, .day], from: Date())
        guard let m = c.month, let d = c.day else { return false }
        return (m == 11 && d >= 15) || m == 12 || (m == 1 && d <= 6)
    }

    static var daysToChristmas: Int {
        let cal = Calendar.current
        let now = Date()
        let c = cal.dateComponents([.year, .month, .day], from: now)
        guard let y = c.year else { return 0 }
        let past = (c.month == 1 && (c.day ?? 1) <= 6)
        var day = cal.date(from: DateComponents(year: past ? y - 1 : y, month: 12, day: 25)) ?? now
        if cal.startOfDay(for: day) < cal.startOfDay(for: now) { day = cal.date(from: DateComponents(year: y + 1, month: 12, day: 25)) ?? now }
        return max(cal.dateComponents([.day], from: cal.startOfDay(for: now), to: cal.startOfDay(for: day)).day ?? 0, 0)
    }

    static var countdown: String {
        let n = daysToChristmas
        return n == 0 ? "It's Christmas Day!" : n == 1 ? "1 day to Christmas" : "\(n) days to Christmas"
    }
}

/** Gentle snow: slow flakes down the view, a few dozen, drawn together */
struct SnowView: View {
    var flakes = 42
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 24)) { t in
            Canvas { ctx, size in
                let time = t.date.timeIntervalSinceReferenceDate
                for i in 0..<flakes {
                    // (each flake: its own place, size and speed, fixed by its number)
                    let seed = Double(i) * 12.9898
                    let rx = abs(sin(seed) * 43758.5453).truncatingRemainder(dividingBy: 1)
                    let ry = abs(sin(seed * 1.7) * 24634.6345).truncatingRemainder(dividingBy: 1)
                    let speed = 14 + 22 * ry
                    let y = (ry * size.height + time * speed).truncatingRemainder(dividingBy: size.height + 12) - 6
                    let x = rx * size.width + sin(time * 0.5 + seed) * 8
                    let r = 1.1 + 1.8 * ry
                    ctx.fill(Path(ellipseIn: CGRect(x: x, y: y, width: r * 2, height: r * 2)), with: .color(.white.opacity(0.35 + 0.5 * ry)))
                }
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: - The tiles

private struct Genre: Identifiable {
    var id: String { name }
    var name: String
    /** The Cinemeta genre (and the type its tile's still comes from) */
    var key: String
    /** A second genre shown with it (Thriller & Horror is one page) */
    var also: String? = nil
    var type: String
    var colour: Color
    /** Anime has no Cinemeta genre: its page comes from the Kitsu catalogs */
    var anime = false

    /** The tile's picture (graphics/genres: a photo in the genre's colour) */
    var asset: String {
        let files = ["Sci-Fi": "scifi", "Thriller & Horror": "thrillerhorror"]
        return "genre-" + (files[name] ?? name.lowercased())
    }

    static let all: [Genre] = [
        Genre(name: "Action", key: "Action", type: "movie", colour: Color(red: 0.88, green: 0.42, blue: 0.12)),
        Genre(name: "Anime", key: "Animation", type: "series", colour: Color(red: 0.9, green: 0.3, blue: 0.55), anime: true),
        Genre(name: "Comedy", key: "Comedy", type: "movie", colour: Color(red: 0.2, green: 0.78, blue: 0.68)),
        Genre(name: "Crime", key: "Crime", type: "series", colour: Color(red: 0.42, green: 0.26, blue: 0.7)),
        Genre(name: "Drama", key: "Drama", type: "series", colour: Color(red: 0.16, green: 0.4, blue: 0.7)),
        Genre(name: "Documentary", key: "Documentary", type: "movie", colour: Color(red: 0.16, green: 0.5, blue: 0.85)),
        Genre(name: "Animation", key: "Animation", type: "movie", colour: Color(red: 0.3, green: 0.72, blue: 0.6)),
        Genre(name: "Family", key: "Family", type: "movie", colour: Color(red: 0.85, green: 0.35, blue: 0.5)),
        Genre(name: "Sci-Fi", key: "Sci-Fi", type: "movie", colour: Color(red: 0.25, green: 0.5, blue: 0.8)),
        Genre(name: "Romance", key: "Romance", type: "movie", colour: Color(red: 0.85, green: 0.3, blue: 0.4)),
        Genre(name: "Thriller & Horror", key: "Thriller", also: "Horror", type: "movie", colour: Color(red: 0.5, green: 0.3, blue: 0.35)),
    ]
}

/** Discover's Genres section: Christmas first in the season (else last), then the rest. Anime only with its addon */
struct GenresSection: View {
    @Environment(Stremio.self) private var stremio

    /** The Kitsu addon is installed: without it Stremio has no way to play an anime title */
    private var hasKitsu: Bool {
        stremio.installedAddons.contains { $0.addonId.lowercased().contains("kitsu") || $0.transportUrl.lowercased().contains("kitsu") }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Genres").font(.title2.bold()).padding(.horizontal, Dx.gutter)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: Dx.pad ? 150 : 100, maximum: 200), spacing: 10)], spacing: 10) {
                if ChristmasSeason.inSeason { christmas }
                ForEach(Genre.all.filter { !$0.anime || hasKitsu }) { g in
                    NavigationLink(value: Route.genre(g.name)) { tile(g) }.buttonStyle(.plain)
                }
                if !ChristmasSeason.inSeason { christmas }
            }
            .padding(.horizontal, Dx.gutter)
        }
        // (the installed addons: asked for here, as nothing else has by now)
        .task { stremio.loadAddons() }
    }

    private var christmas: some View {
        NavigationLink(value: Route.christmas) {
            // (a fixed portrait shape, with everything laid over it: a picture's own size can't stretch the tile)
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay {
                    ZStack(alignment: .bottomLeading) {
                        Image("genre-christmas").resizable().scaledToFill().frame(maxWidth: .infinity, maxHeight: .infinity)
                        SnowView(flakes: 16)
                        Text("Christmas").font(.callout.weight(.bold)).padding(10).shadow(color: .black.opacity(0.5), radius: 4)
                    }
                }
                .clipShape(.rect(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.4), lineWidth: 1))
        }
    }

    private func tile(_ g: Genre) -> some View {
        Color.clear
            .aspectRatio(2 / 3, contentMode: .fit)
            .overlay {
                ZStack(alignment: .bottomLeading) {
                    Image(g.asset).resizable().scaledToFill().frame(maxWidth: .infinity, maxHeight: .infinity)
                    Text(g.name).font(.callout.weight(.bold)).padding(10).shadow(color: .black.opacity(0.5), radius: 4)
                }
            }
            .clipShape(.rect(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.12), lineWidth: 1))
    }
}

/** On For You in the season: a wide festive card that opens Christmas at Home */
struct FestiveCard: View {
    var body: some View {
        NavigationLink(value: Route.christmas) {
            ZStack(alignment: .bottomLeading) {
                LinearGradient(colors: [Color(red: 0.77, green: 0.17, blue: 0.2), Color(red: 0.49, green: 0.1, blue: 0.13), Color(red: 0.05, green: 0.29, blue: 0.18)], startPoint: .topTrailing, endPoint: .bottomLeading)
                SnowView(flakes: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Christmas at Home").font(.title2.weight(.heavy))
                    Text("\(ChristmasSeason.countdown) · films, series and specials").font(.footnote).foregroundStyle(.white.opacity(0.85))
                }
                .padding(16)
                Text("🎄").font(.system(size: 40)).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(14)
            }
            .frame(height: 118)
            .clipShape(.rect(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, Dx.gutter)
    }
}

// MARK: - A genre's page

/** A genre: popular and top rated, films and series in rows of their own (no filter to press) */
struct GenreView: View {
    var name: String
    @State private var rows: [(String, [Title])] = []
    @State private var loaded = false

    private var genre: Genre? { Genre.all.first { $0.name == name } }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                Text(name).font(.largeTitle.bold()).padding(.horizontal, Dx.gutter)
                if !loaded { ProgressView().frame(maxWidth: .infinity).padding(40) }
                ForEach(rows, id: \.0) { title, items in GenreShelf(title: title, items: items) }
                if loaded && rows.isEmpty {
                    ContentUnavailableView("Nothing to show", systemImage: "wifi.exclamationmark", description: Text("Couldn't reach the catalogs."))
                }
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background { backdrop.ignoresSafeArea() }
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var backdrop: some View {
        let top = genre?.colour ?? Dx.accent
        return LinearGradient(stops: [.init(color: top.opacity(0.4), location: 0), .init(color: top.opacity(0.1), location: 0.3), .init(color: .black, location: 0.6)], startPoint: .top, endPoint: .bottom)
    }

    private func load() async {
        let feed = DiscoverFeed.shared
        let lists: [(String, [DiscoverFeed.Item])]
        if genre?.anime == true {
            // (anime: the Kitsu catalogs, which is where Lumen's anime comes from)
            async let trending = feed.anime("kitsu-anime-trending")
            async let airing = feed.anime("kitsu-anime-airing")
            async let popular = feed.anime("kitsu-anime-popular")
            async let rated = feed.anime("kitsu-anime-rating")
            // (each show once, as its first season, with its other seasons on its page; what's airing now keeps its sequels)
            func firsts(_ items: [DiscoverFeed.Item]) -> [DiscoverFeed.Item] { items.filter { !DiscoverFeed.isSequel($0.title.name) } }
            lists = [("Trending", firsts(await trending)), ("Airing Now", await airing), ("Popular", firsts(await popular)), ("Top Rated", firsts(await rated))]
        } else {
            let keys = [genre?.key ?? name] + [genre?.also].compactMap { $0 }
            // (one genre or two: the lists of each, taking turns, each title once)
            func merged(_ fetch: @escaping (String) async -> [DiscoverFeed.Item]) async -> [DiscoverFeed.Item] {
                var all: [[DiscoverFeed.Item]] = []
                for k in keys { all.append(await fetch(k)) }
                var seen = Set<String>(), out: [DiscoverFeed.Item] = []
                for i in 0..<(all.map(\.count).max() ?? 0) {
                    for list in all where i < list.count {
                        let item = list[i]
                        if seen.insert(item.title.id).inserted { out.append(item) }
                    }
                }
                return out
            }
            async let popularFilms = merged { await feed.popular("movie", genre: $0) }
            async let popularSeries = merged { await feed.popular("series", genre: $0) }
            async let ratedFilms = merged { await feed.rated("movie", genre: $0) }
            async let ratedSeries = merged { await feed.rated("series", genre: $0) }
            lists = [("Popular Films", await popularFilms), ("Popular Series", await popularSeries), ("Top Rated Films", await ratedFilms), ("Top Rated Series", await ratedSeries)]
        }
        rows = lists.compactMap { title, items in items.count >= 4 ? (title, items.prefix(25).map(\.title)) : nil }
        loaded = true
    }
}

/** A row of posters */
private struct GenreShelf: View {
    var title: String
    var items: [Title]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title2.bold()).padding(.horizontal, Dx.gutter)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(items, id: \.id) { t in
                        NavigationLink(value: Route.title(t.ref)) { PosterCard(title: t, width: Dx.poster) }
                            .buttonStyle(.plain)
                            .titleMenu(t)
                    }
                }
                .padding(.horizontal, Dx.gutter)
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
        }
    }
}

// MARK: - Christmas at Home

/** The festive page: red and green, snow, how long to Christmas, then the rows (the plugin picks them) */
struct ChristmasView: View {
    @Environment(Stremio.self) private var stremio
    @State private var rows: [ListRow] = []
    @State private var loaded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                ZStack(alignment: .bottomLeading) {
                    SnowView(flakes: 46)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Christmas at Home").font(.system(size: 34, weight: .heavy))
                        Text(ChristmasSeason.countdown).font(.subheadline).foregroundStyle(.white.opacity(0.85))
                    }
                    .padding(.horizontal, Dx.gutter)
                    .padding(.bottom, 8)
                }
                .frame(height: 210)
                if !loaded { ProgressView().frame(maxWidth: .infinity).padding(30) }
                ForEach(rows, id: \.name) { row in GenreShelf(title: row.name, items: row.items) }
                if loaded && rows.isEmpty {
                    ContentUnavailableView("Nothing to show", systemImage: "wifi.exclamationmark", description: Text("Couldn't reach the catalogs."))
                }
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .ignoresSafeArea(edges: .top)
        .background {
            ZStack {
                LinearGradient(stops: [.init(color: Color(red: 0.56, green: 0.12, blue: 0.15), location: 0), .init(color: Color(red: 0.23, green: 0.07, blue: 0.09), location: 0.32), .init(color: .black, location: 0.6)], startPoint: .top, endPoint: .bottom)
                // (a faint green at the foot)
                LinearGradient(colors: [.clear, Color(red: 0.06, green: 0.35, blue: 0.22).opacity(0.3)], startPoint: .center, endPoint: .bottom)
            }
            .ignoresSafeArea()
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // (the first time it looks up some fifty titles, which can take a few seconds: after that it's kept)
            if let c = await stremio.christmas() { rows = c.rows }
            loaded = true
        }
    }
}

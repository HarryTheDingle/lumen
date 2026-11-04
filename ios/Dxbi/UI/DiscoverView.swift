import SwiftUI

/**
 * Discover, by streaming service: For You (what's new on digital, from the Library first, then popular
 * and genre rows), or one service's own page, in its colours (new, popular series and films, its genres).
 * The full catalog browser is at the bottom.
 */
struct DiscoverView: View {
    @Environment(Stremio.self) private var stremio
    @AppStorage("lumen.discover.platform") private var selected = ""
    @State private var rows: [Row] = []
    @State private var loadedFor: String?

    /** A row of Discover: posters, with a date under each for releases */
    struct Row: Identifiable {
        var id: String
        var title: String
        var items: [Title]
        var dates: [String: Date] = [:]
    }

    private var platform: DiscoverFeed.Platform? { DiscoverFeed.platforms.first { $0.id == selected } }
    private var feed: DiscoverFeed { DiscoverFeed.shared }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 30) {
                tabs
                if let platform { wordmark(platform) }
                if loadedFor != selected {
                    ProgressView().frame(maxWidth: .infinity).padding(60)
                } else if rows.isEmpty {
                    ContentUnavailableView("Nothing to show", systemImage: "wifi.exclamationmark", description: Text("Couldn't reach the catalogs. Pull down to try again."))
                }
                ForEach(rows) { row in shelf(row) }
                NavigationLink { CatalogBrowserView() } label: {
                    Label("Browse All Catalogs", systemImage: "square.grid.2x2")
                        .font(.headline)
                        .padding(.horizontal, 18).padding(.vertical, 12)
                }
                .buttonStyle(.glass)
                .padding(.horizontal, Dx.gutter)
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background { backdrop.ignoresSafeArea() }
        .animation(.easeInOut(duration: 0.45), value: selected)
        .navigationTitle("Discover")
        .toolbarTitleDisplayMode(.inline)
        .task(id: selected) { await load() }
        .refreshable { await load() }
    }

    // MARK: - The services

    private var tabs: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                chip("For You", id: "", colour: Dx.accent)
                ForEach(DiscoverFeed.platforms) { p in chip(p.name, id: p.id, colour: p.colour) }
            }
            .padding(.horizontal, Dx.gutter)
        }
        .scrollIndicators(.hidden)
        .padding(.top, 8)
    }

    private func chip(_ name: String, id: String, colour: Color) -> some View {
        let on = selected == id
        return Button { selected = id } label: {
            Text(name)
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 16).padding(.vertical, 9)
                .foregroundStyle(on ? (colour.isLight ? Color.black : .white) : .white)
                .background(on ? colour : Color.white.opacity(0.1), in: .capsule)
        }
        .buttonStyle(.plain)
    }

    /** The service's name, large, in its colour (its page's heading) */
    private func wordmark(_ p: DiscoverFeed.Platform) -> some View {
        Text(p.name)
            .font(.system(size: 46, weight: .black))
            .tracking(-0.5)
            .foregroundStyle(p.colour)
            .shadow(color: p.colour.opacity(0.45), radius: 18)
            .padding(.horizontal, Dx.gutter)
            .transition(.opacity)
    }

    /** The page's colours: the service's, fading to black (For You: Lumen's) */
    private var backdrop: some View {
        let top = platform?.colour ?? Dx.accent
        let deep = platform?.deep ?? Color(red: 0.02, green: 0.1, blue: 0.12)
        return LinearGradient(stops: [
            .init(color: top.opacity(platform == nil ? 0.18 : 0.42), location: 0),
            .init(color: deep, location: 0.28),
            .init(color: .black, location: 0.62),
        ], startPoint: .top, endPoint: .bottom)
    }

    // MARK: - Rows

    private func shelf(_ row: Row) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(row.title).font(.title2.bold()).padding(.horizontal, Dx.gutter)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(row.items, id: \.id) { t in
                        NavigationLink(value: Route.title(t.ref)) {
                            VStack(alignment: .leading, spacing: 6) {
                                PosterCard(title: t, width: Dx.poster)
                                if let d = row.dates[t.id] {
                                    Text(d, format: .dateTime.day().month(.abbreviated))
                                        .font(.footnote.weight(.medium))
                                        .foregroundStyle(d > Date() ? Dx.accent : Dx.muted)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .zoomSource(t.id)
                    }
                }
                .padding(.horizontal, Dx.gutter)
            }
            .scrollIndicators(.hidden)
        }
    }

    // MARK: - Loading

    private func load() async {
        let wanted = selected
        let built: [Row]
        if let p = platform { built = await platformRows(p) } else { built = await forYouRows() }
        guard wanted == selected else { return }
        rows = built
        loadedFor = wanted
    }

    private func forYouRows() async -> [Row] {
        if stremio.library.isEmpty { stremio.loadLibrary() }
        async let releases = feed.digitalYear()
        async let films = feed.popular("movie")
        async let series = feed.popular("series")
        let genres: [(String, String, String)] = [
            ("movie", "Action", "Action Films"), ("series", "Crime", "Crime Series"), ("movie", "Comedy", "Comedy Films"),
            ("movie", "Thriller", "Thrillers"), ("series", "Comedy", "Comedy Series"), ("movie", "Sci-Fi", "Sci-Fi Films"),
            ("series", "Drama", "Drama Series"), ("movie", "Horror", "Horror Films"), ("movie", "Animation", "Animated Films"),
            ("movie", "Documentary", "Documentaries"),
        ]
        var genreRows: [Row] = []
        await withTaskGroup(of: (Int, [DiscoverFeed.Item]).self) { group in
            for (i, g) in genres.enumerated() { group.addTask { (i, await DiscoverFeed.shared.popular(g.0, genre: g.1)) } }
            var found: [(Int, [DiscoverFeed.Item])] = []
            for await r in group { found.append(r) }
            for (i, items) in found.sorted(by: { $0.0 < $1.0 }) where items.count >= 4 {
                genreRows.append(Row(id: "genre-\(i)", title: genres[i].2, items: items.prefix(25).map(\.title)))
            }
        }

        let all = await releases
        let today = Calendar.current.startOfDay(for: Date())
        let out = all.filter { $0.date <= Date() }
        let recent = out.filter { $0.date >= today.addingTimeInterval(-42 * 86400) }
        let coming = all.filter { $0.date > Date() }.sorted { $0.date < $1.date }
        let library = Set(stremio.library.filter { $0.type == "movie" }.map(\.id))
        let mine = out.filter { library.contains($0.id) && $0.date >= today.addingTimeInterval(-60 * 86400) }
        let dates = Dictionary(all.map { ($0.id, $0.date) }, uniquingKeysWith: { a, _ in a })
        Task { await feed.scheduleAlerts(library: stremio.library, releases: all) }

        var rows: [Row] = []
        if !mine.isEmpty { rows.append(Row(id: "mine", title: "From Your Library: Out on Digital", items: mine.map(\.title), dates: dates)) }
        if !recent.isEmpty { rows.append(Row(id: "digital", title: "New on Digital", items: recent.prefix(30).map(\.title), dates: dates)) }
        if !coming.isEmpty { rows.append(Row(id: "coming", title: "Coming to Digital", items: coming.prefix(25).map(\.title), dates: dates)) }
        let f = await films, s = await series
        if !f.isEmpty { rows.append(Row(id: "films", title: "Popular Films", items: f.prefix(25).map(\.title))) }
        if !s.isEmpty { rows.append(Row(id: "series", title: "Popular Series", items: s.prefix(25).map(\.title))) }
        rows += genreRows
        if out.count > recent.count {
            rows.append(Row(id: "year", title: "Out on Digital This Year", items: out.prefix(60).map(\.title), dates: dates))
        }
        return rows
    }

    private func platformRows(_ p: DiscoverFeed.Platform) async -> [Row] {
        async let seriesList = feed.platform(p, "series")
        async let filmList = feed.platform(p, "movie")
        let series = await seriesList, films = await filmList
        let all = series + films
        var rows: [Row] = []
        // new on the service: came out in the last four months, newest first
        let since = Date().addingTimeInterval(-120 * 86400)
        let new = all.filter { ($0.released ?? .distantPast) >= since && ($0.released ?? .distantFuture) <= Date() }
            .sorted { ($0.released ?? .distantPast) > ($1.released ?? .distantPast) }
        if new.count >= 3 { rows.append(Row(id: "new", title: "New on \(p.name)", items: new.prefix(25).map(\.title))) }
        if !series.isEmpty { rows.append(Row(id: "series", title: "Popular Series", items: series.prefix(30).map(\.title))) }
        if !films.isEmpty { rows.append(Row(id: "films", title: "Popular Films", items: films.prefix(30).map(\.title))) }
        // its main genres, as rows
        var counts: [String: Int] = [:]
        for item in all { for g in item.genres { counts[g, default: 0] += 1 } }
        for genre in counts.sorted(by: { $0.value > $1.value }).prefix(6).map(\.key) {
            let items = all.filter { $0.genres.contains(genre) }
            if items.count >= 5 { rows.append(Row(id: "genre-\(genre)", title: genre, items: items.prefix(25).map(\.title))) }
        }
        return rows
    }
}

private extension Color {
    /** Light enough that text on it should be dark */
    var isLight: Bool {
        let c = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        return 0.299 * r + 0.587 * g + 0.114 * b > 0.6
    }
}

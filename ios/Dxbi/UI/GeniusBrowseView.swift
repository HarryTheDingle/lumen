import SwiftUI

// MARK: - Moods

/** One of Apple TV's Genius Browse moods: a Cinemeta genre, the other genres a title must also have, and its colours */
struct Mood: Identifiable, Hashable {
    var name: String
    var genre: String
    var with: Set<String>
    var top: Color
    var bottom: Color
    var shows = true
    var films = true
    var id: String { name }

    private static func c(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    static let all: [Mood] = [
        Mood(name: "Secrets, Lies & Spies", genre: "Thriller", with: ["Action", "Crime", "Mystery"], top: c(0x8E2DE2), bottom: c(0x3A1C71)),
        Mood(name: "Mind-Bending Mysteries", genre: "Mystery", with: ["Sci-Fi", "Thriller", "Fantasy"], top: c(0x4A3AFF), bottom: c(0x1B1464)),
        Mood(name: "Charming Romcoms", genre: "Romance", with: ["Comedy"], top: c(0x13C2B3), bottom: c(0x0B5E6B)),
        Mood(name: "Good for a Night In", genre: "Comedy", with: ["Drama", "Romance"], top: c(0x2BD96B), bottom: c(0x0B6B3A)),
        Mood(name: "Gripping Crime", genre: "Crime", with: ["Thriller", "Action"], top: c(0x3A6BFF), bottom: c(0x0F1F66)),
        Mood(name: "Epic Adventures", genre: "Adventure", with: ["Fantasy", "Action"], top: c(0x1FA2FF), bottom: c(0x0B3B66)),
        Mood(name: "Into the Future", genre: "Sci-Fi", with: ["Drama", "Action", "Thriller"], top: c(0x6A5CFF), bottom: c(0x16124A)),
        Mood(name: "Things That Go Bump", genre: "Horror", with: ["Mystery", "Thriller", "Fantasy"], top: c(0xE2335E), bottom: c(0x4A0F24)),
        Mood(name: "Family Movie Night", genre: "Family", with: ["Comedy", "Adventure", "Animation"], top: c(0xFFA62B), bottom: c(0x8A3B0B), shows: false),
        Mood(name: "True Stories", genre: "Biography", with: ["Drama", "History", "Sport"], top: c(0xB8A15A), bottom: c(0x3D3415)),
        Mood(name: "Laugh-Out-Loud Shows", genre: "Comedy", with: [], top: c(0xFFD43B), bottom: c(0x8A6A0B), films: false),
        Mood(name: "War & Heroes", genre: "War", with: ["Drama", "History", "Action"], top: c(0x7A8B5A), bottom: c(0x232B14)),
    ]
}

enum GeniusKind: String, CaseIterable {
    case both = "TV Shows & Movies", shows = "TV Shows", films = "Movies"
}

let geniusGenres = ["Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
                    "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "War"]

/** A mood's titles: its genre's popular and best rated, shows and films in turn, those also of one of its other genres */
@MainActor func moodTitles(_ mood: Mood, kind: GeniusKind, genre: String?) async -> [Title] {
    let feed = DiscoverFeed.shared
    let types = ["series"].filter { _ in mood.shows && kind != .films } + ["movie"].filter { _ in mood.films && kind != .shows }
    var lists: [[Title]] = []
    for type in types {
        async let pop = feed.popular(type, genre: mood.genre)
        async let top = feed.rated(type, genre: mood.genre)
        let (p, t) = await (pop, top)
        var seen = Set<String>()
        var all = (p + t).map(\.title).filter { seen.insert($0.id).inserted }
        if !mood.with.isEmpty { all = all.filter { $0.genres.contains { mood.with.contains($0) } } }
        if let genre { all = all.filter { $0.genres.contains(genre) } }
        lists.append(all)
    }
    // (a chosen genre can leave few: top it up from that genre's own catalogs, still of the mood's genre)
    if let genre, lists.reduce(0, { $0 + $1.count }) < 8 {
        for (i, type) in types.enumerated() {
            let extra = (await feed.popular(type, genre: genre) + feed.rated(type, genre: genre)).map(\.title)
            lists[i] += extra.filter { $0.genres.contains(mood.genre) }
        }
    }
    var out: [Title] = []
    for i in 0..<(lists.map(\.count).max() ?? 0) { for l in lists where i < l.count { out.append(l[i]) } }
    var seen = Set<String>()
    return Array(out.filter { seen.insert($0.id).inserted }.prefix(30))
}

// MARK: - Home's row

/** Home's "Genius Browse": tall mood tiles that open the mood's own page */
struct GeniusShelf: View {
    var body: some View {
        Shelf(title: "Genius Browse") {
            ForEach(Mood.all) { mood in
                NavigationLink(value: Route.genius(mood.name)) { MoodTile(mood: mood) }
                    .buttonStyle(LiftStyle())
            }
        }
    }
}

private struct MoodTile: View {
    var mood: Mood
    var body: some View {
        let w: CGFloat = Dx.pad ? 160 : Dx.poster
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [mood.top, mood.bottom], startPoint: .top, endPoint: .bottom)
            Text(mood.name)
                .font(.system(size: max(14, w * 0.11), weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.leading)
                .padding(10)
        }
        .frame(width: w, height: w * 1.5)
        .clipShape(.rect(cornerRadius: min(18, w * 0.12)))
        .hoverEffect(.lift)
    }
}

/** Presses sink a little; on a pointer the tile lifts (see MoodTile) */
private struct LiftStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

// MARK: - The chain

/** The chain's rows: each "More like" the chosen title of the one above, endless */
@Observable @MainActor
final class ChainModel {
    struct Row: Identifiable {
        let id = UUID()
        var title: String
        var items: [Title]
        var selected: String
        var chosen: Title? { items.first { $0.id == selected } }
    }

    var rows: [Row] = []
    private var rebuild: Task<Void, Never>?
    private var grow: Task<Void, Never>?
    private var rebuilding = false
    private var growing = false
    private var exhausted = false
    private var gen = 0

    func reset(title: String, items: [Title], stremio: Stremio) {
        rebuild?.cancel(); grow?.cancel()
        rebuilding = false; growing = false; exhausted = false
        guard let first = items.first else { rows = []; return }
        rows = [Row(title: title, items: items, selected: first.id)]
        more(stremio: stremio)
    }

    /** Chooses a title in a row: after a pause (a pointer sweeping past isn't a choice), every row below is rebuilt from it */
    func select(_ rowID: UUID, _ title: Title, stremio: Stremio, immediately: Bool = false) {
        guard let i = rows.firstIndex(where: { $0.id == rowID }), rows[i].selected != title.id else { return }
        rows[i].selected = title.id
        grow?.cancel(); growing = false; exhausted = false
        rebuild?.cancel()
        gen += 1
        let mine = gen
        rebuilding = true
        let seen = Set(rows[...i].flatMap { $0.items.map(\.id) })
        rebuild = Task {
            if !immediately { try? await Task.sleep(for: .milliseconds(300)) }
            if Task.isCancelled { return }
            let items = await Self.next(from: title, seen: seen, stremio: stremio)
            if Task.isCancelled { return }
            // (the old rows stay until these are ready)
            guard let j = rows.firstIndex(where: { $0.id == rowID }), rows[j].selected == title.id else { return }
            var kept = Array(rows[...j])
            if let f = items.first { kept.append(Row(title: "More like \(title.name)", items: items, selected: f.id)) }
            else { exhausted = true }
            rows = kept
            if gen == mine { rebuilding = false }
        }
    }

    /** Adds the next "More like <the last row's chosen title>" row, one at a time */
    func more(stremio: Stremio) {
        guard !rebuilding, !growing, !exhausted, let last = rows.last, let t = last.chosen else { return }
        growing = true
        let seen = Set(rows.flatMap { $0.items.map(\.id) })
        grow = Task {
            let items = await Self.next(from: t, seen: seen, stremio: stremio)
            if Task.isCancelled { return }
            growing = false
            guard rows.last?.id == last.id else { return }
            if let f = items.first { rows.append(Row(title: "More like \(t.name)", items: items, selected: f.id)) } else { exhausted = true }
        }
    }

    /** Related titles not shown yet; a few repeats are kept when too few are new */
    private static func next(from t: Title, seen: Set<String>, stremio: Stremio) async -> [Title] {
        var ids = Set<String>()
        let all = await stremio.related(t.type, t.id, genres: t.genres).filter { $0.id != t.id && ids.insert($0.id).inserted }
        let fresh = all.filter { !seen.contains($0.id) }
        return fresh.count >= 6 ? fresh : fresh + all.filter { seen.contains($0.id) }
    }
}

/** A vertical list of full poster rows; choosing a title in one rebuilds those below. Lives inside a ScrollView */
struct ChainView<Header: View>: View {
    @Environment(Stremio.self) private var stremio
    @State private var model = ChainModel()
    var title: String
    var firstRow: [Title]
    @ViewBuilder var header: Header

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 18) {
            header
            ForEach(model.rows) { row in
                ChainRowView(row: row, model: model)
            }
            // (near the bottom: the next row; asked again whenever a row lands, so it keeps going while this is in view)
            if !model.rows.isEmpty {
                Color.clear.frame(height: 1)
                    .task(id: model.rows.count) { model.more(stremio: stremio) }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.rows.map(\.id))
        .task(id: firstRow.map(\.id).joined(separator: ",") + title) {
            model.reset(title: title, items: firstRow, stremio: stremio)
        }
    }
}

extension ChainView where Header == EmptyView {
    init(title: String, firstRow: [Title]) { self.init(title: title, firstRow: firstRow) { EmptyView() } }
}

private struct ChainRowView: View {
    @Environment(Stremio.self) private var stremio
    var row: ChainModel.Row
    var model: ChainModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionHeader(title: row.title).padding(.horizontal, Dx.gutter)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 14) {
                    ForEach(row.items) { t in poster(t) }
                }
                .padding(.horizontal, Dx.gutter)
                .padding(.vertical, 12)
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            caption
        }
    }

    @ViewBuilder
    private func poster(_ t: Title) -> some View {
        let on = t.id == row.selected
        let card = PosterCard(title: t, width: Dx.pad ? 160 : Dx.poster)
            .scaleEffect(on ? 1.08 : 1)
            .animation(.spring(duration: 0.3), value: on)
        Group {
            if on {
                // the chosen one opens its page
                NavigationLink(value: Route.title(t.ref)) { card.zoomSource(t.id) }
            } else {
                Button { model.select(row.id, t, stremio: stremio, immediately: true) } label: { card }
            }
        }
        .buttonStyle(.plain)
        .titleMenu(t)
        // iPad pointer: resting on a poster chooses it
        .onHover { if $0 { model.select(row.id, t, stremio: stremio) } }
    }

    private var caption: some View {
        let t = row.chosen
        let line = [t.map { typeName($0.type) }, t?.genres.first].compactMap { $0 }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 2) {
            Text(t?.name ?? " ").font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(line.isEmpty ? " " : line).font(.footnote).foregroundStyle(Dx.muted).lineLimit(1)
        }
        .padding(.horizontal, Dx.gutter)
    }
}

// MARK: - The page

/** A mood's page: kind and genre to choose from, then the chain */
struct GeniusBrowseView: View {
    var moodName: String
    @State private var kind = GeniusKind.both
    @State private var genre: String?
    @State private var titles: [Title] = []
    @State private var loading = true

    private var mood: Mood { Mood.all.first { $0.name == moodName } ?? Mood.all[0] }

    var body: some View {
        ScrollView {
            ChainView(title: mood.name, firstRow: titles) {
                VStack(spacing: 14) {
                    Text("Genius Browse").font(.headline)
                    HStack(spacing: 10) {
                        pill(kind.rawValue) {
                            ForEach(GeniusKind.allCases, id: \.self) { k in
                                Button { kind = k } label: { if k == kind { Label(k.rawValue, systemImage: "checkmark") } else { Text(k.rawValue) } }
                            }
                        }
                        pill(genre ?? "All Genres") {
                            ForEach([nil] + geniusGenres.map(Optional.some), id: \.self) { g in
                                Button { genre = g } label: {
                                    if g == genre { Label(g ?? "All Genres", systemImage: "checkmark") } else { Text(g ?? "All Genres") }
                                }
                            }
                        }
                    }
                    if loading { ProgressView().padding(.top, 30) }
                    else if titles.isEmpty { Text("Nothing matched, try another genre").foregroundStyle(Dx.muted).padding(.top, 30) }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
            }
            .padding(.bottom, 30)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(kind.rawValue)|\(genre ?? "")") {
            loading = true
            let r = await moodTitles(mood, kind: kind, genre: genre)
            if Task.isCancelled { return }
            titles = r
            loading = false
        }
    }

    private func pill<C: View>(_ text: String, @ViewBuilder items: () -> C) -> some View {
        Menu(content: items) {
            HStack(spacing: 4) {
                Text(text)
                Image(systemName: "chevron.down").font(.caption.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14).padding(.vertical, 8)
            .glassEffect(.regular.interactive(), in: .capsule)
        }
        .tint(.white)
    }
}

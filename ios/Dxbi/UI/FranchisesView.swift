import SwiftUI

/**
 * Franchises and filmmakers in Discover: a row of wide cards (a still from the franchise, darkened, with
 * its name), the next film of each franchise that's been started, and a row of directors and writers by
 * their faces. A franchise opens its page: its films in the order they came out, then its series. A
 * filmmaker opens their page (PersonView). What this profile has watched leads both rows.
 */

/** Discover's Franchises section, and under it Complete the Collection when a franchise has been started */
struct FranchisesSection: View {
    @Environment(Stremio.self) private var stremio
    private var links: DiscoverLinks { DiscoverLinks.shared }
    private var width: CGFloat { Dx.pad ? 300 : 232 }

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Franchises").font(.title2.bold()).padding(.horizontal, Dx.gutter)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 14) {
                        ForEach(links.franchises) { f in
                            NavigationLink(value: Route.franchise(id: f.id, name: f.name)) { card(f) }.buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, Dx.gutter)
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
            }
            if !links.next.isEmpty {
                Shelf(title: "Complete the Collection") {
                    ForEach(links.next) { item in
                        NavigationLink(value: Route.title(item.title.ref)) {
                            VStack(alignment: .leading, spacing: 6) {
                                PosterCard(title: item.title, width: Dx.poster).zoomSource(item.title.id)
                                Text(item.franchise.name).font(.caption).foregroundStyle(Dx.muted).lineLimit(1)
                                    .frame(width: Dx.poster, alignment: .leading)
                            }
                        }
                        .buttonStyle(.plain)
                        .titleMenu(item.title)
                    }
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: links.next)
        // (again once the watched marks are in: they decide the order, and what's next)
        .task(id: stremio.marksKnown) { await links.refresh(stremio) }
    }

    /** A still from the franchise, darkening to the foot, with its name and how far through it this profile is */
    private func card(_ f: Franchise) -> some View {
        ZStack(alignment: .bottomLeading) {
            WebImage(url: f.picture) { $0.resizable().scaledToFill() } placeholder: { _ in Dx.raised }
                .frame(width: width, height: width * 9 / 16)
            LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: .black.opacity(0.82), location: 1)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 2) {
                Text(f.name).font(.headline.weight(.bold)).lineLimit(2).multilineTextAlignment(.leading)
                if let note = note(f) { Text(note).font(.caption).foregroundStyle(.white.opacity(0.75)) }
            }
            .shadow(color: .black.opacity(0.5), radius: 4)
            .padding(12)
        }
        .frame(width: width, height: width * 9 / 16)
        .clipShape(.rect(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.12), lineWidth: 1))
        .contentShape(.rect(cornerRadius: 14))
    }

    private func note(_ f: Franchise) -> String? {
        guard !f.films.isEmpty else { return nil }
        let seen = links.watched(f, stremio)
        if seen == 0 { return "\(f.films.count) films" }
        return seen == f.films.count ? "All \(seen) watched" : "\(seen) of \(f.films.count) watched"
    }
}

/** Discover's Directors & Writers section: faces in a row, each opening their page */
struct FilmmakersSection: View {
    @Environment(Stremio.self) private var stremio
    private var links: DiscoverLinks { DiscoverLinks.shared }
    private var size: CGFloat { Dx.pad ? 124 : 92 }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Directors & Writers").font(.title2.bold()).padding(.horizontal, Dx.gutter)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: Dx.pad ? 18 : 12) {
                    ForEach(links.filmmakers) { p in
                        let photo = links.faces[p.id] ?? p.photo
                        NavigationLink(value: Route.filmmaker(id: p.tmdb, name: p.name, photo: photo)) { face(p, photo: photo) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, Dx.gutter)
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
        }
        .task(id: stremio.marksKnown) { await links.refresh(stremio) }
    }

    /** Their photo in a circle (their initials until it's in, or when there's none), their name and what they do */
    private func face(_ p: Filmmaker, photo: String?) -> some View {
        VStack(spacing: 6) {
            ZStack {
                Circle().fill(Color(white: 0.3))
                Text(Self.initials(p.name)).font(.system(size: size * 0.36, weight: .semibold))
                if let photo, let url = URL(string: photo) {
                    WebImage(url: url) { $0.resizable().scaledToFill() } placeholder: { _ in Color.clear }
                }
            }
            .frame(width: size, height: size)
            .clipShape(.circle)
            // (room for a name on two lines and the role, whoever is in view: the row keeps one height)
            Text("\n\n").font(.footnote).hidden()
                .frame(maxWidth: .infinity)
                .overlay(alignment: .top) {
                    VStack(spacing: 2) {
                        Text(p.name).font(.footnote.weight(.medium)).lineLimit(2).multilineTextAlignment(.center)
                        Text(p.role).font(.caption).foregroundStyle(Dx.muted).lineLimit(1)
                    }
                }
        }
        .frame(width: size + 16)
        .contentShape(.rect)
    }

    static func initials(_ name: String) -> String {
        name.split(separator: " ").filter { $0.lowercased() != "the" }.compactMap(\.first).prefix(2).map(String.init).joined()
    }
}

// MARK: - A franchise's page

/**
 * A franchise: its picture and name, how many of its films are watched, then every film in the order
 * they came out and the series that go with them. The app's own list shows at once; with a TMDB key the
 * films of its collections join it (a new one the list doesn't have yet), and a franchise found at TMDB
 * is all from there.
 */
struct FranchiseView: View {
    @Environment(Stremio.self) private var stremio
    var id: String
    var name: String

    @State private var films: [Title] = []
    @State private var series: [Title] = []
    @State private var overview: String?
    @State private var backdrop: URL?
    @State private var loaded = false

    private var listed: Franchise? { Listed.franchise(id) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                header
                if !films.isEmpty {
                    PosterGrid(items: films).padding(.horizontal, Dx.gutter - 16)
                } else if loaded {
                    ContentUnavailableView("Nothing to show", systemImage: "film.stack", description: Text("Couldn't find the films of \(name) right now."))
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(40)
                }
                if !series.isEmpty {
                    Shelf(title: "Series") {
                        ForEach(series) { t in
                            NavigationLink(value: Route.title(t.ref)) { PosterCard(title: t, width: Dx.poster).zoomSource(t.id) }
                                .buttonStyle(.plain)
                                .titleMenu(t)
                        }
                    }
                }
            }
            .padding(.bottom, 60)
        }
        .scrollIndicators(.hidden)
        .ignoresSafeArea(edges: .top)
        .background(Color.black.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) { await load() }
    }

    /** The franchise's still across the top, fading into the page, with its name over the foot of it */
    private var header: some View {
        let height: CGFloat = Dx.pad ? 420 : 280
        // (a fixed shape with everything laid over it: a picture's own size can't push the words off the page)
        return Color.black
            .frame(height: height)
            .frame(maxWidth: .infinity)
            .overlay { WebImage(url: backdrop) { $0.resizable().scaledToFill() } placeholder: { _ in Color.black } }
            .clipped()
            .overlay {
                LinearGradient(stops: [.init(color: .black.opacity(0.35), location: 0), .init(color: .clear, location: 0.3), .init(color: .black.opacity(0.7), location: 0.78), .init(color: .black, location: 1)], startPoint: .top, endPoint: .bottom)
            }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(name).font(Dx.pad ? .system(size: 44, weight: .bold) : .largeTitle.bold()).shadow(color: .black.opacity(0.5), radius: 6)
                    if let facts { Text(facts).font(.subheadline).foregroundStyle(.white.opacity(0.75)) }
                    if let overview, !overview.isEmpty {
                        Text(overview).font(.callout).lineLimit(3).frame(maxWidth: Dx.readable, alignment: .leading).padding(.top, 2)
                    }
                }
                .padding(.horizontal, Dx.gutter)
            }
    }

    private var facts: String? {
        guard !films.isEmpty else { return nil }
        let seen = films.filter { stremio.isWatched($0) }.count
        let count = films.count == 1 ? "1 film" : "\(films.count) films"
        return seen == 0 ? count : seen == films.count ? "\(count) · all watched" : "\(count) · \(seen) watched"
    }

    private func load() async {
        // the list's own films at once
        if let f = listed {
            films = f.films.map { $0.title("movie") }
            series = f.series.map { $0.title("series") }
            backdrop = URL(string: "https://images.metahub.space/background/large/\(f.art)/img")
            loaded = true
            if f.seeds.isEmpty { return }
        }
        // TMDB's, with a key: for a franchise found there all of it, for the list's any film it doesn't have
        let found = await stremio.franchise(collection: listed == nil && id.hasPrefix("tmdb:") ? String(id.dropFirst(5)) : nil, seeds: listed?.seeds ?? [])
        if let found {
            if listed == nil {
                films = found.films
                overview = found.overview
                backdrop = found.backdrop.flatMap(URL.init) ?? found.films.first?.backdrop
            } else {
                let have = Set(films.map(\.id))
                let more = found.films.filter { !have.contains($0.id) }
                if !more.isEmpty {
                    // (in with the others by year; the list's own order stays as it is)
                    let all = (films + more).enumerated().sorted { a, b in
                        let ya = Int(a.element.year ?? "") ?? 0, yb = Int(b.element.year ?? "") ?? 0
                        return ya != yb ? ya < yb : a.offset < b.offset
                    }.map(\.element)
                    withAnimation(.easeInOut(duration: 0.3)) { films = all }
                }
            }
        }
        loaded = true
    }
}

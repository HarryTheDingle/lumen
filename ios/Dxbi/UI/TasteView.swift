import SwiftUI

/**
 * Tune Suggestions: the first time a profile opens with nothing watched (and from Profile whenever), two quick
 * choices so Home has suggestions on day one: the genres they like, then a few titles they've enjoyed. Both can
 * be skipped. What they pick stands in for Top Picks until there's real watching to go on.
 */
struct TasteSetup: View {
    @Environment(Stremio.self) private var stremio
    @State private var step = 0
    @State private var genres: [String] = []
    @State private var picked: [Taste.Seed] = []
    @State private var candidates: [Title] = []
    @State private var loading = false

    private var name: String { stremio.viewer?.name ?? "" }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 6) {
                Text(step == 0 ? "\(stepNote)1 of 2" : "\(stepNote)2 of 2").font(.footnote).foregroundStyle(Dx.faint)
                Text(step == 0 ? "What do you like to watch?" : "Pick a few you've enjoyed").font(.largeTitle.bold())
                Text(step == 0 ? "Choose any. You can change this later in Profile." : "Three or more is plenty.")
                    .font(.subheadline).foregroundStyle(Dx.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Dx.gutter)
            .padding(.top, 36)
            .padding(.bottom, 14)

            ScrollView {
                if step == 0 { genreGrid } else { titleGrid }
            }
            .scrollIndicators(.hidden)

            HStack {
                Button(step == 0 ? "Skip" : "Back") {
                    if step == 0 { finish(skipped: true) } else { step = 0 }
                }
                .foregroundStyle(Dx.muted)
                Spacer()
                Button(step == 0 ? "Next" : "Start Watching") {
                    if step == 0 { step = 1; loadCandidates() } else { finish(skipped: false) }
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(.black)
                .controlSize(.large)
                .disabled(step == 0 && genres.isEmpty)
            }
            .padding(.horizontal, Dx.gutter)
            .padding(.vertical, 16)
        }
        .screenBackground()
        .interactiveDismissDisabled()
        .onDisappear { stremio.tuningShown = false }
        .onAppear {
            stremio.tuningShown = true
            // (opened again from Profile: what was chosen before)
            if let t = stremio.taste { genres = t.genres; picked = t.titles }
        }
    }

    private var stepNote: String { name.isEmpty ? "" : "\(name) · " }

    // MARK: - Genres

    private var genreGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: Dx.pad ? 150 : 100, maximum: 200), spacing: 10)], spacing: 10) {
            ForEach(Genre.all.filter { !$0.anime }) { g in
                let on = genres.contains(g.name)
                Button { toggle(&genres, g.name) } label: {
                    Color.clear
                        .aspectRatio(2 / 3, contentMode: .fit)
                        .overlay {
                            ZStack(alignment: .bottomLeading) {
                                Image(g.asset).resizable().scaledToFill().frame(maxWidth: .infinity, maxHeight: .infinity)
                                Text(g.name).font(.callout.weight(.bold)).padding(10).shadow(color: .black.opacity(0.5), radius: 4)
                            }
                        }
                        .clipShape(.rect(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(on ? .white : .white.opacity(0.12), lineWidth: on ? 3 : 1))
                        .overlay(alignment: .topTrailing) { if on { tick } }
                        .opacity(on || genres.isEmpty ? 1 : 0.6)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(.horizontal, Dx.gutter)
        .padding(.bottom, 12)
    }

    // MARK: - Titles

    private var titleGrid: some View {
        Group {
            if loading && candidates.isEmpty {
                ProgressView().tint(.white).frame(maxWidth: .infinity).padding(.top, 60)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: Dx.pad ? 140 : 100, maximum: 190), spacing: 10)], spacing: 10) {
                    ForEach(candidates) { t in
                        let on = picked.contains { $0.id == t.id }
                        Button { toggleTitle(t) } label: {
                            Art(url: t.art)
                                .aspectRatio(2 / 3, contentMode: .fit)
                                .clipShape(.rect(cornerRadius: 12))
                                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(on ? .white : .clear, lineWidth: 3))
                                .overlay(alignment: .topTrailing) { if on { tick } }
                                .opacity(on || picked.isEmpty ? 1 : 0.6)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(t.name)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(.horizontal, Dx.gutter)
                .padding(.bottom, 12)
            }
        }
    }

    private var tick: some View {
        Image(systemName: "checkmark.circle.fill")
            .font(.title3)
            .symbolRenderingMode(.palette)
            .foregroundStyle(.black, .white)
            .padding(8)
    }

    private func toggle(_ list: inout [String], _ value: String) {
        if let i = list.firstIndex(of: value) { list.remove(at: i) } else { list.append(value) }
    }

    private func toggleTitle(_ t: Title) {
        if let i = picked.firstIndex(where: { $0.id == t.id }) { picked.remove(at: i) } else { picked.append(Taste.Seed(t)) }
    }

    /** Films and series in the chosen genres, mixed (all popular ones if none were chosen) */
    private func loadCandidates() {
        loading = true
        Task {
            let feed = DiscoverFeed.shared
            var lists: [[Title]] = []
            let chosen = genres.compactMap { n in Genre.all.first { $0.name == n } }
            if chosen.isEmpty {
                lists = [await feed.popular("movie").map(\.title), await feed.popular("series").map(\.title)]
            } else {
                for g in chosen.prefix(4) {
                    async let m = feed.popular("movie", genre: g.key)
                    async let s = feed.popular("series", genre: g.key)
                    let (movies, series) = await (m, s)
                    lists.append(movies.prefix(10).map(\.title))
                    lists.append(series.prefix(10).map(\.title))
                }
            }
            var seen = Set<String>()
            var out: [Title] = []
            for i in 0..<(lists.map(\.count).max() ?? 0) {
                for l in lists where i < l.count && l[i].art != nil && !l[i].genres.isEmpty && seen.insert(l[i].id).inserted { out.append(l[i]) }
            }
            candidates = Array(out.prefix(24))
            loading = false
        }
    }

    private func finish(skipped: Bool) {
        var t = Taste(genres: genres, titles: picked)
        t.skipped = skipped && genres.isEmpty && picked.isEmpty
        stremio.finishTaste(t)
    }
}

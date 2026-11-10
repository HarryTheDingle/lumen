import SwiftUI

/**
 * QuickRateView: Rate Movies & Series interface in Lumen Settings and Profile
 * to rapidly train personalized recommendations.
 */
struct QuickRateView: View {
    @Environment(Stremio.self) private var stremio
    @Environment(\.dismiss) private var dismiss

    enum Tab: String, CaseIterable, Identifiable {
        case discover = "Discover"
        case search = "Search"
        case rated = "Rated"
        var id: String { rawValue }
    }

    @State private var selectedTab: Tab = .discover
    @State private var ratings: [String: Int] = [:]
    @State private var discoverTitles: [Title] = []
    @State private var searchResults: [Title] = []
    @State private var searchQuery: String = ""
    @State private var isSearching = false
    @State private var isLoadingDiscover = false
    @State private var titleCache: [String: Title] = [:]
    @State private var ratingTarget: Title?
    @State private var targetScore: Int?

    private var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: Dx.pad ? 150 : 105, maximum: 180), spacing: 14)]
    }

    private var averageRating: Double? {
        guard !ratings.isEmpty else { return nil }
        let total = ratings.values.reduce(0, +)
        return Double(total) / Double(ratings.count)
    }

    private var ratedList: [RatedItem] {
        ratings.map { (id, score) in
            let title = resolvedTitle(for: id)
            return RatedItem(id: id, title: title, score: score)
        }
        .sorted { a, b in
            if a.score != b.score {
                return a.score > b.score
            }
            return a.title.name < b.title.name
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("Category", selection: $selectedTab) {
                Text("Discover").tag(Tab.discover)
                Text("Search").tag(Tab.search)
                Text(ratings.isEmpty ? "Rated" : "Rated (\(ratings.count))").tag(Tab.rated)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, Dx.gutter)
            .padding(.top, 8)
            .padding(.bottom, 12)

            ScrollView {
                switch selectedTab {
                case .discover:
                    discoverSection
                case .search:
                    searchSection
                case .rated:
                    ratedSection
                }
            }
            .scrollIndicators(.hidden)
        }
        .screenBackground()
        .navigationTitle("Rate Movies & Series")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $ratingTarget) { target in
            RatingSheet(
                titleName: target.name.isEmpty ? "Title" : target.name,
                userRating: $targetScore
            ) { newScore in
                if let newScore {
                    ratings[target.id] = newScore
                } else {
                    ratings.removeValue(forKey: target.id)
                }
                titleCache[target.id] = target
                await stremio.setRating(newScore, for: target.id, title: target)
                let fresh = await stremio.allRatings()
                await MainActor.run {
                    ratings = fresh
                }
            }
        }
        .task {
            ratings = await stremio.allRatings()
            await loadDiscover()
            await loadMissingTitles()
        }
        .onChange(of: selectedTab) { _, tab in
            if tab == .discover && discoverTitles.isEmpty {
                Task { await loadDiscover() }
            } else if tab == .rated {
                Task { await loadMissingTitles() }
            }
        }
    }

    // MARK: - Discover

    private var discoverSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Popular to Rate")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("Rate titles you've watched to fine-tune Top Picks and personalized suggestions.")
                    .font(.subheadline)
                    .foregroundStyle(Dx.muted)
            }
            .padding(.horizontal, Dx.gutter)

            let unrated = discoverTitles.filter { ratings[$0.id] == nil }

            if isLoadingDiscover && unrated.isEmpty {
                ProgressView().tint(.white).frame(maxWidth: .infinity).padding(.top, 60)
            } else if unrated.isEmpty {
                VStack(spacing: 14) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.yellow)
                    Text("All Caught Up!")
                        .font(.title3.bold())
                    Text("You've rated all current popular titles. Use Search to find more movies and series to rate.")
                        .font(.subheadline)
                        .foregroundStyle(Dx.muted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Button("Search Titles") {
                        selectedTab = .search
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(.black)
                    .padding(.top, 4)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 50)
            } else {
                LazyVGrid(columns: gridColumns, spacing: 16) {
                    ForEach(unrated) { title in
                        TitleRateCard(title: title, rating: ratings[title.id]) {
                            openRating(for: title)
                        }
                    }
                }
                .padding(.horizontal, Dx.gutter)
                .padding(.bottom, 24)
            }
        }
    }

    // MARK: - Search

    private var searchSection: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(Dx.muted)
                TextField("Search movies & series to rate…", text: $searchQuery)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                if !searchQuery.isEmpty {
                    Button {
                        searchQuery = ""
                        searchResults = []
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(Dx.muted)
                    }
                }
            }
            .padding(10)
            .background(Dx.raised)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, Dx.gutter)

            if isSearching {
                ProgressView().tint(.white).frame(maxWidth: .infinity).padding(.top, 40)
            } else if !searchQuery.trimmingCharacters(in: .whitespaces).isEmpty && searchResults.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "film")
                        .font(.system(size: 40))
                        .foregroundStyle(Dx.muted)
                    Text("No results for \"\(searchQuery)\"")
                        .font(.headline)
                    Text("Check the spelling or try searching for another title.")
                        .font(.subheadline)
                        .foregroundStyle(Dx.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 50)
            } else if searchResults.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 36))
                        .foregroundStyle(Dx.muted)
                    Text("Find Titles to Rate")
                        .font(.headline)
                    Text("Type any movie or TV series name to find and rate it.")
                        .font(.subheadline)
                        .foregroundStyle(Dx.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 60)
            } else {
                LazyVGrid(columns: gridColumns, spacing: 16) {
                    ForEach(searchResults) { title in
                        TitleRateCard(title: title, rating: ratings[title.id]) {
                            openRating(for: title)
                        }
                    }
                }
                .padding(.horizontal, Dx.gutter)
                .padding(.bottom, 24)
            }
        }
        .task(id: searchQuery) {
            let q = searchQuery.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else {
                searchResults = []
                isSearching = false
                return
            }
            isSearching = true
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            let results = await DiscoverFeed.shared.search(q).map(\.title)
            if !Task.isCancelled {
                searchResults = results
                isSearching = false
                for t in results { titleCache[t.id] = t }
            }
        }
    }

    // MARK: - Rated

    private var ratedSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            if ratings.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "star.slash")
                        .font(.system(size: 48))
                        .foregroundStyle(Dx.muted)
                    Text("No Ratings Yet")
                        .font(.title3.bold())
                    Text("Rate titles in Discover or Search to tune your recommendations.")
                        .font(.subheadline)
                        .foregroundStyle(Dx.muted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Button("Discover Titles to Rate") {
                        selectedTab = .discover
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(.black)
                    .padding(.top, 6)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 60)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text("\(ratings.count) Titles Rated")
                            .font(.headline)
                            .foregroundStyle(.white)
                        Spacer()
                        if let avg = averageRating {
                            Text(String(format: "Avg ★ %.1f", avg))
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Dx.muted)
                        }
                    }
                    Text("Sorted highest to lowest. Tap any title to adjust or remove.")
                        .font(.subheadline)
                        .foregroundStyle(Dx.muted)
                }
                .padding(.horizontal, Dx.gutter)

                LazyVGrid(columns: gridColumns, spacing: 16) {
                    ForEach(ratedList) { item in
                        TitleRateCard(title: item.title, rating: item.score) {
                            openRating(for: item.title)
                        }
                    }
                }
                .padding(.horizontal, Dx.gutter)
                .padding(.bottom, 24)
            }
        }
    }

    // MARK: - Helpers

    private func openRating(for title: Title) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        titleCache[title.id] = title
        targetScore = ratings[title.id]
        ratingTarget = title
    }

    private func resolvedTitle(for id: String) -> Title {
        if let hit = titleCache[id], !hit.name.isEmpty { return hit }
        if let lib = stremio.library.first(where: { $0.id == id }) {
            titleCache[id] = lib
            return lib
        }
        if let disc = discoverTitles.first(where: { $0.id == id }) {
            titleCache[id] = disc
            return disc
        }
        if let sr = searchResults.first(where: { $0.id == id }) {
            titleCache[id] = sr
            return sr
        }
        return Title(id: id, type: "movie", name: "")
    }

    private func loadDiscover() async {
        guard discoverTitles.isEmpty else { return }
        isLoadingDiscover = true
        async let movies = DiscoverFeed.shared.popular("movie")
        async let series = DiscoverFeed.shared.popular("series")
        let (m, s) = await (movies, series)
        var combined: [Title] = []
        var seen = Set<String>()
        let maxCount = max(m.count, s.count)
        for i in 0..<maxCount {
            if i < m.count, seen.insert(m[i].title.id).inserted {
                combined.append(m[i].title)
                titleCache[m[i].title.id] = m[i].title
            }
            if i < s.count, seen.insert(s[i].title.id).inserted {
                combined.append(s[i].title)
                titleCache[s[i].title.id] = s[i].title
            }
        }
        discoverTitles = combined
        isLoadingDiscover = false
    }

    private func loadMissingTitles() async {
        let libraryIds = Set(stremio.library.map(\.id))
        let missingIds = ratings.keys.filter { id in
            let existing = titleCache[id]
            return (existing == nil || existing?.name.isEmpty == true) && !libraryIds.contains(id)
        }
        for id in missingIds {
            if let found = await DiscoverFeed.shared.title(for: id) {
                await MainActor.run {
                    titleCache[id] = found
                }
            }
        }
    }
}

struct RatedItem: Identifiable {
    let id: String
    let title: Title
    let score: Int
}

struct TitleRateCard: View {
    let title: Title
    let rating: Int?
    let onTap: () -> Void

    private var corner: CGFloat { 12 }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .topTrailing) {
                    Color.clear
                        .aspectRatio(2 / 3, contentMode: .fit)
                        .overlay {
                            Art(url: title.art)
                                .aspectRatio(2 / 3, contentMode: .fill)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: corner))
                        .overlay(
                            RoundedRectangle(cornerRadius: corner)
                                .strokeBorder(LinearGradient(colors: [.white.opacity(0.3), .white.opacity(0.08)], startPoint: .top, endPoint: .bottom), lineWidth: 1)
                        )

                    // Rating badge or rate prompt
                    if let rating {
                        HStack(spacing: 3) {
                            Image(systemName: "star.fill")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(Color.yellow)
                            Text("\(rating)")
                                .font(.system(size: 11, weight: .heavy))
                                .foregroundStyle(.white)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(.ultraThinMaterial)
                        .background(Color.black.opacity(0.6))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(Color.yellow.opacity(0.4), lineWidth: 1))
                        .padding(6)
                    } else {
                        HStack(spacing: 3) {
                            Image(systemName: "star")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(Color.yellow)
                            Text("Rate")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(.white)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(.ultraThinMaterial)
                        .background(Color.black.opacity(0.5))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.2), lineWidth: 0.5))
                        .padding(6)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title.name.isEmpty ? title.id : title.name)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)

                    if let year = title.year, !year.isEmpty {
                        Text(year)
                            .font(.caption2)
                            .foregroundStyle(Dx.muted)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }
}

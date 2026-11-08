import SwiftUI

/**
 * Home, the Apple TV way: a tall billboard to swipe through (logo, what it is, Play and +), then
 * Continue Watching, Lumen's own rows and the addons' catalogs in the viewer's order.
 */
struct HomeView: View {
    @Environment(Stremio.self) private var stremio
    @State private var account = false
    @State private var party = false
    @State private var kind: String?
    @State private var pastBillboard = false

    var body: some View {
        GeometryReader { g in
            let width = g.size.width
            let wide = width > g.size.height
            ScrollView {
                // (not lazy: rows arriving above, Top Picks among them, left a lazy column with blank gaps
                // where rows should be, coming and going. Each row's own posters are still lazy)
                VStack(alignment: .leading, spacing: 30) {
                    if !billboardTitles.isEmpty {
                        Billboard(titles: billboardTitles, playLabels: playLabels, height: wide ? g.size.height * 0.92 : min(g.size.height * 0.74, width * 1.55))
                    } else {
                        Color.clear.frame(height: 90)
                    }
                    kinds
                    if !stremio.continueWatching.isEmpty && kind == nil {
                        Shelf(title: "Continue Watching") {
                            ForEach(stremio.continueWatching) { item in
                                ContinueTile(item: item, width: min(max(width * 0.68, 260), 360))
                            }
                        }
                    }
                    ForEach(Array(stremio.myList.enumerated()), id: \.offset) { _, row in
                        if kind == nil, !row.items.isEmpty { titleRow(row.name, row.items, see: .library) }
                    }
                    if kind == nil {
                        ForEach(stremio.picks.prefix(1), id: \.name) { row in titleRow(row.name, row.items) }
                        if stremio.picks.isEmpty, let forYou = stremio.forYou { titleRow(forYou.name, forYou.items) }
                    }
                    if kind == nil, !stremio.airing.isEmpty {
                        Shelf(title: "New Episodes This Week") {
                            ForEach(stremio.airing) { item in
                                NavigationLink(value: Route.title(TitleRef(type: "series", id: item.id, name: item.name))) {
                                    AiringCard(item: item, width: min(max(width * 0.7, 260), 380))
                                }
                                .buttonStyle(.plain)
                                .titleMenu(Title(id: item.id, type: "series", name: item.name))
                            }
                        }
                    }
                    // Because you watched …: on what this profile actually watched
                    if kind == nil {
                        ForEach(stremio.picks.dropFirst(), id: \.name) { row in titleRow(row.name, row.items) }
                    }
                    let rows = stremio.rows.filter { kind == nil || $0.type == kind }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        if row.state == .ready && !row.items.isEmpty {
                            // Netflix's Top 10: the first row is numbered
                            titleRow(row.heading, row.items, rank: i == 0, see: row.discover.map { .browse(link: $0, name: row.heading) })
                        } else if row.state == .loading {
                            loadingRow(row.heading)
                        }
                    }
                    if stremio.rows.isEmpty && stremio.ready {
                        ContentUnavailableView("Nothing to show yet", systemImage: "puzzlepiece.extension", description: Text("Install addons to fill Home: tap your picture, then Addons."))
                    }
                }
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
            // the billboard shows through the top bar, as on Apple TV; once it's scrolled away, rows
            // fade out under the title instead of running into it
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y > min(g.size.height * 0.74, width * 1.55) - 140 } action: { _, past in
                pastBillboard = past
            }
            .scrollEdgeEffectHidden(!pastBillboard, for: .top)
            .ignoresSafeArea(edges: .top)
        }
        .screenBackground()
        .navigationTitle("Home")
        .toolbarTitleDisplayMode(.inlineLarge)
        .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { party = true } label: { Image(systemName: "person.2.fill") }
                    .accessibilityLabel("Watch party")
            }
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
            // The picture is the button, a perfect circle (not inside the bar's glass capsule)
            ToolbarItem(placement: .topBarTrailing) {
                Button { account = true } label: {
                    Group {
                        if stremio.viewers.count > 1, let v = stremio.viewer { ProfileAvatar(name: v.name, colour: v.colour, size: 44) }
                        else { Avatar(email: stremio.account?.email, size: 44) }
                    }
                    .glassEffect(.regular.interactive(), in: .circle)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Profile and settings")
            }
            .sharedBackgroundVisibility(.hidden)
        }
        .sheet(isPresented: $account) { AccountView() }
        .sheet(isPresented: $party) { PartyView() }
        .refreshable { stremio.refreshExtras() }
    }

    /** Netflix's Movies · Series chips */
    /**
     * The billboard leads with what they're watching: the show watched last first, then the rest of
     * Continue Watching (five at most). Suggestions only when nothing's in progress.
     */
    /**
     * What they're watching, taking turns with suggestions (Top Picks for You, then what's popular) that they
     * aren't watching and haven't put in their Library: four of each at most
     */
    private var billboardTitles: [Title] {
        let watching = stremio.continueWatching.prefix(4).map { stremio.filledIn($0.title) }
        var taken = Set(stremio.continueWatching.map(\.title.id))
        for row in stremio.myList { for t in row.items { taken.insert(t.id) } }
        var suggested: [Title] = []
        for t in (stremio.picks.first?.items ?? []) + stremio.billboard where suggested.count < 4 {
            guard !taken.contains(t.id), !stremio.isInLibrary(t), t.backdrop != nil else { continue }
            taken.insert(t.id)
            suggested.append(stremio.filledIn(t))
        }
        var out: [Title] = []
        for i in 0..<max(watching.count, suggested.count) {
            if i < watching.count { out.append(watching[i]) }
            if i < suggested.count { out.append(suggested[i]) }
        }
        return out
    }

    /** Play on the billboard, for what's in progress: "Resume S2 E5", "Play S3 E1" (a new episode) */
    private var playLabels: [String: String] {
        var labels: [String: String] = [:]
        for item in stremio.continueWatching.prefix(5) {
            let verb = item.progress > 0.02 ? "Resume" : "Play"
            if let v = item.videoId, let m = v.wholeMatch(of: /.+:(\d+):(\d+)/) {
                labels[item.id] = "\(verb) S\(m.1) E\(m.2)"
            } else {
                labels[item.id] = verb
            }
        }
        return labels
    }

    private var kinds: some View {
        let types = ["movie", "series", "anime"].filter { t in stremio.rows.contains { $0.type == t } }
        return ScrollView(.horizontal) {
            HStack(spacing: 10) {
                ForEach(types.prefix(4), id: \.self) { t in
                    let on = kind == t
                    Button {
                        withAnimation(.snappy) { kind = on ? nil : t }
                    } label: {
                        HStack(spacing: 6) {
                            Text(t == "movie" ? "Movies" : t == "series" ? "TV Shows" : typeName(t))
                            if on { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 6)
                    }
                    .buttonStyle(.glass)
                    .tint(on ? .white : nil)
                }
            }
            .padding(.horizontal, Dx.gutter)
        }
        .scrollIndicators(.hidden)
        .opacity(types.count > 1 ? 1 : 0)
        .frame(height: types.count > 1 ? nil : 0)
    }

    private func titleRow(_ name: String, _ items: [Title], rank: Bool = false, see: Route? = nil) -> some View {
        Shelf(title: name, see: see) {
            ForEach(Array(items.prefix(rank ? 10 : 30).enumerated()), id: \.element.id) { i, title in
                NavigationLink(value: Route.title(title.ref)) {
                    PosterCard(title: title, width: Dx.poster, rank: rank ? i + 1 : nil)
                        .zoomSource(title.id)
                }
                .buttonStyle(.plain)
                .titleMenu(title)
            }
        }
    }

    private func loadingRow(_ name: String) -> some View {
        Shelf(title: name) {
            ForEach(0..<4, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 14).fill(Dx.raised).frame(width: Dx.poster, height: Dx.poster * 1.5)
            }
        }
        .redacted(reason: .placeholder)
    }
}

/** A titled row of cards that scrolls sideways, snapping to each */
struct Shelf<Content: View>: View {
    var title: String
    var see: Route?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let see {
                NavigationLink(value: see) {
                    HStack(spacing: 4) {
                        Text(title).font(.title2.bold()).lineLimit(1)
                        Image(systemName: "chevron.right").font(.title3.weight(.bold)).foregroundStyle(Dx.muted)
                    }
                }
                .buttonStyle(.plain)
                .padding(.horizontal, Dx.gutter)
            } else {
                SectionHeader(title: title)
            }
            ScrollView(.horizontal) {
                LazyHStack(spacing: 14) { content }
                    .scrollTargetLayout()
                    .padding(.horizontal, Dx.gutter)
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
            // (a held card grows as its menu opens: past the row, not cut off at its top and bottom)
            .scrollClipDisabled()
        }
    }
}

/**
 * Long-press on a title (right-click on the Mac), the same everywhere: Details, Play, the Library,
 * watched or not, the trailer. [resume]: it's in Continue Watching (Resume, and Remove from the row)
 */
struct TitleMenu: View {
    @Environment(Stremio.self) private var stremio
    var title: Title
    var resume = false

    var body: some View {
        let inList = stremio.isInLibrary(title)
        let watched = stremio.isWatched(title)
        let started = resume || stremio.isStarted(title)
        NavigationLink(value: Route.title(title.ref)) { Label("Details", systemImage: "info.circle") }
        NavigationLink(value: Route.title(TitleRef(type: title.type, id: title.id, name: title.name, play: true))) {
            Label(resume ? "Resume" : "Play", systemImage: "play.fill")
        }
        if started {
            NavigationLink(value: Route.title(TitleRef(type: title.type, id: title.id, name: title.name, fromStart: true))) {
                Label("Start from the Beginning", systemImage: "arrow.counterclockwise")
            }
        }
        if title.type == "series" {
            NavigationLink(value: Route.title(TitleRef(type: title.type, id: title.id, name: title.name, shuffle: true))) {
                Label("Shuffle", systemImage: "shuffle")
            }
        }
        Divider()
        Button {
            stremio.setInLibrary(title, !inList)
            stremio.show(inList ? "Removed from your Library" : "Added to your Library")
        } label: {
            Label(inList ? "Remove from Library" : "Add to Library", systemImage: inList ? "minus.circle" : "plus.circle")
        }
        if !watched {
            Button {
                stremio.setWatched(title, true)
                stremio.show("Marked as watched")
            } label: { Label("Mark as Watched", systemImage: "eye") }
        }
        if watched || started {
            Button {
                stremio.setWatched(title, false)
                stremio.show("Marked as unwatched")
            } label: { Label("Mark as Unwatched", systemImage: "eye.slash") }
        }
        if resume {
            Button(role: .destructive) { stremio.removeFromContinue(title.id) } label: {
                Label("Remove from Continue Watching", systemImage: "xmark.circle")
            }
        }
        if let trailer = title.trailer, let url = URL(string: "https://www.youtube.com/watch?v=\(trailer)") {
            Divider()
            Link(destination: url) { Label("Trailer", systemImage: "film") }
        }
        if title.id.hasPrefix("tt"), let url = URL(string: "https://www.imdb.com/title/\(title.id)/") {
            ShareLink(item: url, subject: Text(title.name)) { Label("Share", systemImage: "square.and.arrow.up") }
        }
    }
}

extension View {
    /** A title's long-press menu (see TitleMenu) */
    func titleMenu(_ title: Title, resume: Bool = false) -> some View {
        contextMenu { TitleMenu(title: title, resume: resume) }
    }
}

/** Continue Watching: plays where they left off; ⋯ for the rest */
private struct ContinueTile: View {
    @Environment(Stremio.self) private var stremio
    var item: Continue
    var width: CGFloat

    var body: some View {
        // (it opens the title: Resume there starts it, rather than playing straight from Home)
        NavigationLink(value: Route.title(item.title.ref)) {
            ContinueCard(item: item, width: width)
                .zoomSource(item.title.id)
        }
        .buttonStyle(.plain)
        .titleMenu(item.title, resume: true)
    }
}

/**
 * The tall billboard at the top of Home: one title at a time, as Apple TV's. A swipe moves the picture with
 * the finger (drawn a little larger, so its edge never shows) while it fades into the next (or the one
 * before); letting go carries it on the same way to the end, never back. It turns by itself too. The dots
 * follow along: the current one is a bar that fills as its time runs out, and shrinks back to a dot as the
 * next one grows into the bar. Tapping it opens the title; Play plays.
 */
private struct Billboard: View {
    @Environment(Stremio.self) private var stremio
    var titles: [Title]
    /** Play's words for a title in progress ("Resume S2 E5") */
    var playLabels: [String: String] = [:]
    var height: CGFloat
    @State private var index = 0
    /** How far into the change to the next (or the one before) it is, 0 to 1: the finger's, then the finish */
    @State private var fade: CGFloat = 0
    /** Which way: -1 to the next (a swipe left), 1 to the one before */
    @State private var dir: CGFloat = -1
    @State private var dragging = false
    /** Carrying a change on to its end: no new swipe or turn till it's done */
    @State private var finishing = false
    @State private var opened: TitleRef?
    /** + and ✓ pressed here: shown at once, with the symbol's own change (the library catching up redrew it, unanimated) */
    @State private var inList: [String: Bool] = [:]
    /** When this title came up: the dot's bar fills from here */
    @State private var shownAt = Date()

    /** How long each title stays */
    private static let stay: TimeInterval = 8
    /** How much larger the pictures are drawn than the billboard: the room they have to move sideways */
    private static let zoom: CGFloat = 1.12

    private var current: Title { titles[min(index, titles.count - 1)] }
    private func wrap(_ i: Int) -> Int { (i + titles.count) % titles.count }
    /** The one it's changing to */
    private var toward: Int { wrap(dir < 0 ? index + 1 : index - 1) }

    var body: some View {
        GeometryReader { g in
            // (the pictures are drawn a little larger than the billboard, so they can move this far without
            // their edge showing: this one goes out that way, the next comes in from the other side)
            let bleed = g.size.width * (Self.zoom - 1) / 2
            ZStack(alignment: .bottom) {
                Color.black
                // (the one before, this one and the next only: the rest aren't drawn)
                ZStack {
                    ForEach(shown, id: \.self) { i in
                        picture(titles[i])
                            .scaleEffect(Self.zoom)
                            .offset(x: i == index ? dir * bleed * fade : i == toward ? -dir * bleed * (1 - fade) : 0)
                            .opacity(i == index ? 1 - fade : i == toward ? fade : 0)
                    }
                }
                // (Home scrolled up: the art goes at half its speed, as Apple TV's)
                .visualEffect { content, proxy in
                    let y = proxy.frame(in: .scrollView(axis: .vertical)).minY
                    return content.offset(y: y < 0 ? -y * 0.5 : 0)
                }
                ForEach(shown, id: \.self) { i in
                    // (the words go out in the first half of the change, the next ones come in over the second)
                    info(titles[i])
                        .opacity(i == index ? max(1 - fade * 2, 0) : i == toward ? max(fade * 2 - 1, 0) : 0)
                        .allowsHitTesting(i == index && fade < 0.5)
                }
                dots.padding(.bottom, 4)
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 20)
                    .onChanged { v in
                        guard titles.count > 1, !finishing else { return }
                        guard abs(v.translation.width) > abs(v.translation.height) || dragging else { return }
                        dragging = true
                        if v.translation.width != 0 { dir = v.translation.width < 0 ? -1 : 1 }
                        // (a little under half the width is all the way)
                        fade = min(abs(v.translation.width) / (g.size.width * 0.45), 1)
                    }
                    .onEnded { _ in
                        guard dragging else { return }
                        dragging = false
                        // (once it's moving, it goes: letting go carries it on to the end)
                        finish(duration: 0.3)
                    }
            )
        }
        .frame(height: height)
        .clipped()
        .contentShape(.rect)
        // a tap opens the title; a swipe only changes the slide (and Home still scrolls up and down)
        .onTapGesture { opened = current.ref }
        .titleMenu(current)
        .navigationDestination(item: $opened) { TitleView(ref: $0) }
        // turns by itself (not while a finger's on it); any change starts the wait again
        .task(id: "\(index)-\(titles.count)-\(dragging)") {
            if !dragging { shownAt = Date() }
            try? await Task.sleep(for: .seconds(Self.stay))
            guard !Task.isCancelled, !dragging, !finishing, titles.count > 1 else { return }
            dir = -1
            finish(duration: 0.6)
        }
    }

    /** Carries the change on to its end, then the one it changed to is the current one */
    private func finish(duration: Double) {
        let to = toward
        finishing = true
        // (a quick spring, as Apple TV's: it's there almost at once and settles)
        withAnimation(.snappy(duration: duration)) { fade = 1 } completion: {
            var quiet = Transaction()
            quiet.disablesAnimations = true
            withTransaction(quiet) {
                index = to
                fade = 0
                shownAt = Date()
            }
            finishing = false
        }
    }

    private var shown: [Int] {
        guard titles.count > 1 else { return titles.indices.map { $0 } }
        return Array(Set([wrap(index - 1), index, wrap(index + 1)])).sorted()
    }

    private func picture(_ title: Title) -> some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .overlay { Art(url: title.backdrop) }
            .clipped()
            .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.35), location: 0), .init(color: .clear, location: 0.25), .init(color: .clear, location: 0.45), .init(color: .black, location: 0.92)], startPoint: .top, endPoint: .bottom))
    }

    private func info(_ title: Title) -> some View {
        TitleInfo(title: title, status: status(title), more: .constant(false)) {
            EmptyView()
        } buttons: {
            HStack(spacing: 14) {
                NavigationLink(value: Route.title(TitleRef(type: title.type, id: title.id, name: title.name, play: true))) {
                    Label(playLabels[title.id] ?? "Play", systemImage: "play.fill").font(.headline).frame(minWidth: 150).padding(.horizontal, 10).frame(height: 50)
                }
                .buttonStyle(.glassProminent)
                .tint(.white)
                .foregroundStyle(.black)
                // (what the Library says, as everywhere else: the title's own mark isn't kept for what's being watched)
                let saved = inList[title.id] ?? stremio.isInLibrary(title)
                GlassCircle(icon: saved ? "checkmark" : "plus", label: saved ? "In your Library" : "Add to Library") {
                    withAnimation(.snappy) { inList[title.id] = !saved }
                    stremio.setInLibrary(title, !saved)
                    stremio.show(saved ? "Removed from your Library" : "Added to your Library")
                }
            }
        }
        .frame(maxWidth: 520, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.bottom, 34)
    }

    /** What's new, from what Stremio knows: an episode coming this week or out now, or new episodes waiting (else nothing) */
    private func status(_ title: Title) -> String? {
        if title.type != "movie", let a = stremio.airing.first(where: { $0.id == title.id }) {
            return a.out ? "New Episode Out Now" : "S\(a.season), E\(a.episode) · \(a.whenText)"
        }
        if let n = stremio.continueWatching.first(where: { $0.id == title.id })?.newEpisodes, n > 0 {
            return n == 1 ? "New Episode Out Now" : "\(n) New Episodes Out Now"
        }
        return nil
    }

    /**
     * A dot a title. The current one is a bar that fills as its time runs out; through a change it shrinks back
     * to a dot as the one it's changing to grows into the bar, in step with the picture (with the finger, on a
     * swipe). Only the fill runs on the clock: the widths inside it waited for the change to end.
     */
    private var dots: some View {
        HStack(spacing: 8) {
            ForEach(titles.indices, id: \.self) { i in
                let grown: CGFloat = i == index ? 1 - fade : i == toward ? fade : 0
                Capsule().fill(.white.opacity(0.4))
                    .overlay(alignment: .leading) { if i == index && fade == 0 { fill } }
                    .frame(width: 7 + 23 * grown, height: 7)
            }
        }
    }

    /** The current bar's fill: starting as a dot, the size of the others, and growing along the bar */
    private var fill: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: dragging || finishing)) { t in
            let since = t.date.timeIntervalSince(shownAt)
            let done = min(max(since / Self.stay, 0), 1)
            // (fading in over its first moment, rather than appearing at once)
            Capsule().fill(.white).frame(width: 7 + 23 * done).opacity(min(max(since / 0.4, 0), 1))
        }
    }
}

/** The signed-in person: their Stremio picture, or their initial */
struct Avatar: View {
    var email: String?
    var size: CGFloat = 30
    var body: some View {
        Text(String((email ?? "?").prefix(1)).uppercased())
            .font(.system(size: size * 0.45, weight: .semibold))
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [Color(red: 0.35, green: 0.6, blue: 0.75), Color(red: 0.2, green: 0.4, blue: 0.55)], startPoint: .top, endPoint: .bottom), in: .circle)
            .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: 1))
    }
}

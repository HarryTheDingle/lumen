import SwiftUI

/**
 * A film or show, the Apple TV way: its art fills the top, darkened (no colour) under the logo, the
 * facts and Play on the left, who's in it on the right; then the seasons and episodes, the trailer,
 * related titles, the cast and About. The art stays put as the page scrolls up over it, fading away.
 */
struct TitleView: View {
    @Environment(Stremio.self) private var stremio
    @Environment(\.dismiss) private var dismiss
    var ref: TitleRef

    @State private var choices: StreamChoices?
    /** Play was pressed for this video: dx.bi's pick plays as soon as it's sure */
    @State private var waitingFor: String?
    @State private var streamsOpen = false
    @State private var season: Int?
    @State private var related: [Title] = []
    @State private var reviews: Reviews?
    @State private var reviewsOpen = false
    @State private var moreText = false
    @State private var autoPlayed = false
    /** The cast with photos (TMDB, with a key in dx.bi settings), else nil */
    @State private var cast: [JSON]?

    private var details: Details? { stremio.details?.metaId == ref.id ? stremio.details : nil }
    private var title: Title { details?.title ?? Title(id: ref.id, type: ref.type, name: ref.name) }
    private var current: StreamChoices? {
        guard let c = choices, let v = details?.videoId, c.videoId == v else { return nil }
        return c
    }

    var body: some View {
        GeometryReader { g in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header(size: g.size)
                    VStack(alignment: .leading, spacing: 34) {
                        if ref.type == "series" { episodes(width: g.size.width) }
                        trailers(width: g.size.width)
                        relatedRow
                        extras(width: g.size.width)
                        about
                    }
                    .padding(.vertical, 26)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.black)
                }
            }
            .scrollIndicators(.hidden)
            .ignoresSafeArea(edges: .top)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let link = shareLink { ShareLink(item: link) { Image(systemName: "square.and.arrow.up") } }
            }
        }
        .sheet(isPresented: $streamsOpen) {
            StreamsSheet(choices: current, heading: title.name, subheading: episodeLine) { option, target in
                play(option, in: target)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $reviewsOpen) { ReviewsView(title: title, reviews: reviews) }
        .task(id: ref.id) {
            if stremio.details?.metaId != ref.id { stremio.open(ref.type, ref.id) }
            reviews = await stremio.reviews(ref.type, ref.id)
        }
        .task(id: "\(ref.id)-\(details?.state == .ready)") {
            guard let d = details, d.state == .ready else { return }
            let names: [JSON] = d.directors.prefix(3).map { ["name": $0, "role": "Director"] } + d.cast.prefix(13).map { ["name": $0, "role": ""] }
            cast = await stremio.cast(ref.type, imdbId ?? "", names: names)
        }
        .task(id: "\(ref.id)-related-\(details?.state == .ready)") {
            guard let d = details, d.state == .ready, related.isEmpty else { return }
            related = await stremio.related(ref.type, ref.id, genres: d.title?.genres ?? [])
        }
        // a series: the episode they're on, ready before Play is pressed
        .onChange(of: details?.state == .ready, initial: true) {
            guard let d = details, d.state == .ready, ref.type == "series" else { return }
            if season == nil { season = nextVideo(d)?.season ?? d.seasons.first }
            if d.videoId == nil, let v = nextVideo(d) { stremio.open(ref.type, ref.id, videoId: v.id) }
            if ref.play && !autoPlayed { autoPlayed = true; start(nextVideo(d)?.id) }
        }
        .onChange(of: details?.state == .ready && ref.type != "series", initial: true) { _, ready in
            if ready && ref.play && !autoPlayed { autoPlayed = true; start(nil) }
        }
        // The episode picked shows in its season (back from the player, the page picks the next one
        // when the last was finished: dxbi-engine.js afterPlaying)
        .onChange(of: details?.videoId) { _, id in
            guard let d = details, let v = d.videos.first(where: { $0.id == id }), let s = v.season else { return }
            season = s
        }
        // dx.bi's reading of the streams, again as more addons answer
        .task(id: details?.streamsKey) {
            guard details?.videoId != nil else { return }
            choices = await stremio.streams()
            justPlayIfSure()
        }
        .task(id: waitingFor) {
            guard waitingFor != nil else { return }
            try? await Task.sleep(for: .seconds(6))
            if let c = current, c.justPlay, !NetworkStatus.shared.saving, waitingFor == c.videoId, let pick = c.picked { play(pick) }
        }
    }

    // MARK: - Playing

    /** What Play starts: the episode they're in the middle of, else the first one that's out */
    private func nextVideo(_ d: Details) -> Video? {
        guard ref.type == "series" else { return nil }
        // (the one picked first: the page moves it on to the next episode after one is finished)
        return d.videos.first { $0.id == d.videoId } ?? d.videos.first { $0.id == d.resumeVideoId } ?? d.videos.first { ($0.season ?? 0) > 0 && !$0.upcoming } ?? d.videos.first
    }

    private func start(_ videoId: String?) {
        let target = videoId ?? details?.videoId ?? ref.id
        if ref.type == "series", let videoId, videoId != details?.videoId {
            choices = nil
            stremio.open(ref.type, ref.id, videoId: videoId)
        }
        waitingFor = target
        streamsOpen = true
        justPlayIfSure()
    }

    /** Just press Play: the stream chosen last time plays at once, anything else once every addon has answered */
    private func justPlayIfSure() {
        guard let c = current, c.justPlay, waitingFor == c.videoId, let pick = c.picked else { return }
        // On mobile data or Low Data Mode the stream list stays open to choose from (the best is often the biggest)
        guard !NetworkStatus.shared.saving else { return }
        if c.sameAsLastTime || c.settled { play(pick) }
    }

    private func play(_ option: StreamOption, in target: Stremio.Target = .player) {
        waitingFor = nil
        streamsOpen = false
        stremio.play(option, in: target)
    }

    private var playLabel: String {
        guard let d = details else { return "Play" }
        if ref.type == "series", let v = nextVideo(d), let s = v.season, let e = v.episode {
            let resuming = v.id == d.resumeVideoId && d.resumeMs > 0
            return "\(resuming ? "Resume" : "Play") S\(s), E\(e)"
        }
        return d.resumeMs > 0 && d.resumeVideoId == ref.id ? "Resume" : "Play"
    }

    private var episodeLine: String? {
        guard let d = details, let v = d.videos.first(where: { $0.id == d.videoId }), let s = v.season, let e = v.episode else { return nil }
        return "S\(s), E\(e) · \(v.title)"
    }

    /** The title's IMDb id (tt…): Stremio's own id for most titles. (Its "imdb" link is the IMDb rating.) */
    private var imdbId: String? {
        ref.id.wholeMatch(of: /tt\d+/) != nil ? ref.id : nil
    }

    private var shareLink: URL? {
        imdbId.flatMap { URL(string: "https://www.imdb.com/title/\($0)/") }
    }

    // MARK: - The top: art, logo, Play

    private func header(size: CGSize) -> some View {
        let width = size.width
        let wide = width > 600
        // Apple TV's: the art fills the top (most of the screen on an iPad); the details sit on it, low
        let artHeight = wide ? max(min(size.height * 0.8, width * 0.75), 520) : max(width * 1.35, 520)
        return ZStack(alignment: .bottom) {
            Color.clear.frame(width: width, height: artHeight)
            HStack(alignment: .bottom, spacing: 30) {
                info(wide: wide)
                    .frame(maxWidth: wide ? 520 : .infinity, alignment: .leading)
                Spacer(minLength: 0)
                if wide { credits.frame(maxWidth: 340, alignment: .leading) }
            }
            .padding(.horizontal, wide ? 44 : Dx.gutter)
            .padding(.bottom, wide ? 50 : 26)
        }
        .frame(width: width)
        .background(alignment: .top) {
            // the page scrolls up over the art, which stays where it is and fades as it's covered (and
            // grows when the page is pulled down). Darkened, no colour: low and on the left, under the text
            Art(url: title.backdrop)
                .frame(width: width, height: artHeight)
                .clipped()
                .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.35), location: 0), .init(color: .clear, location: 0.22), .init(color: .clear, location: 0.42), .init(color: .black.opacity(0.72), location: 0.8), .init(color: .black, location: 1)], startPoint: .top, endPoint: .bottom))
                .overlay(wide ? LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .leading, endPoint: UnitPoint(x: 0.55, y: 0.5)) : nil)
                .visualEffect { content, proxy in
                    let y = proxy.frame(in: .scrollView(axis: .vertical)).minY
                    let covered = min(max(-y / (artHeight * 0.8), 0), 1)
                    return content
                        .scaleEffect(y > 0 ? 1 + y / artHeight : 1, anchor: .bottom)
                        .offset(y: y < 0 ? -y : 0)
                        .opacity(1 - covered * 0.9)
                }
        }
    }

    /** On the art's left: the logo, what it is, the story, the year and length, Play */
    private func info(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            TitleLogo(title: title, maxHeight: wide ? 120 : 100, textSize: 38, alignment: .leading)
                .frame(maxWidth: wide ? 380 : 300, alignment: .leading)
                .padding(.bottom, 6)
            Text(([typeName(title.type)] + title.genres.prefix(3)).joined(separator: " · "))
                .font(.subheadline.weight(.medium))
            if let d = title.description {
                Button { withAnimation(.snappy) { moreText.toggle() } } label: {
                    Text(d)
                        .lineLimit(moreText ? nil : 2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .overlay(alignment: .bottomTrailing) {
                            if !moreText {
                                Text("MORE")
                                    .font(.caption.weight(.bold))
                                    .padding(.horizontal, 8).padding(.vertical, 3)
                                    .background(.ultraThinMaterial, in: .capsule)
                                    .background(.black.opacity(0.4), in: .capsule)
                            }
                        }
                }
                .buttonStyle(.plain)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.88))
            }
            facts
            buttons.padding(.top, 6)
            if !wide { credits.padding(.top, 4) }
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.35), radius: 8)
    }

    /** On the art's right: who's in it, and who made it */
    @ViewBuilder
    private var credits: some View {
        if let d = details, !(d.cast.isEmpty && d.directors.isEmpty) {
            VStack(alignment: .leading, spacing: 4) {
                if !d.cast.isEmpty { Text("Starring ").foregroundStyle(Dx.muted) + Text(d.cast.prefix(3).joined(separator: ", ")) }
                if !d.directors.isEmpty { Text(d.directors.count > 1 ? "Directors " : "Director ").foregroundStyle(Dx.muted) + Text(d.directors.prefix(2).joined(separator: ", ")) }
            }
            .font(.subheadline)
            .lineLimit(3)
        }
    }

    private var buttons: some View {
        let inList = title.inLibrary
        return HStack(spacing: 12) {
            PrimaryPill(label: playLabel) { start(details.flatMap(nextVideo)?.id) }
                .disabled(details?.state != .ready)
            GlassCircle(icon: inList ? "checkmark" : "plus", label: inList ? "Remove from Library" : "Add to Library") {
                stremio.setInLibrary(!inList)
                stremio.show(inList ? "Removed from your Library" : "Added to your Library")
            }
            Menu {
                Button { stremio.setWatched(!title.watched) } label: {
                    Label(title.watched ? "Mark as Unwatched" : "Mark as Watched", systemImage: title.watched ? "eye.slash" : "eye")
                }
                if let trailer = title.trailer, let url = URL(string: "https://www.youtube.com/watch?v=\(trailer)") {
                    Link(destination: url) { Label("Trailer", systemImage: "film") }
                }
                Button { reviewsOpen = true } label: { Label("Reviews", systemImage: "star") }
                if details?.videoId != nil {
                    Button { streamsOpen = true } label: { Label("Choose a Stream", systemImage: "list.bullet") }
                }
                if let pick = current?.picked {
                    Button { play(pick, in: .otherApp) } label: { Label("Play with External Player", systemImage: "arrow.up.forward.app") }
                    let sizes = StreamOption.downloadSizes(current?.items ?? [])
                    if sizes.isEmpty {
                        Button { play(pick, in: .download) } label: { Label("Download", systemImage: "arrow.down.circle") }
                    } else {
                        Menu {
                            ForEach(sizes, id: \.option.index) { size in
                                Button { play(size.option, in: .download) } label: {
                                    Text(size.name)
                                    Text([size.option.label, ByteCountFormatter.string(fromByteCount: Int64(size.option.size), countStyle: .file)].joined(separator: " · "))
                                }
                            }
                        } label: { Label("Download", systemImage: "arrow.down.circle") }
                    }
                }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 20, weight: .semibold)).frame(width: 36, height: 36)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
        }
        .frame(maxWidth: 400)
    }

    /** "2026 · 47 min", then the scores (they open the reviews) */
    private var facts: some View {
        HStack(spacing: 10) {
            Text([title.year, title.runtime].compactMap { $0 }.joined(separator: " · "))
            if title.watched { Badge(text: "WATCHED") }
            if let scores = reviews?.scores, !scores.isEmpty {
                Button { reviewsOpen = true } label: {
                    HStack(spacing: 10) {
                        ForEach(scores.prefix(3)) { s in
                            (Text(s.value).fontWeight(.semibold) + Text(" \(s.name)").foregroundStyle(Dx.muted))
                        }
                    }
                    .lineLimit(1)
                }
                .buttonStyle(.plain)
            }
        }
        .font(.subheadline)
    }

    // MARK: - Trailers, related, about

    @ViewBuilder
    private func trailers(width: CGFloat) -> some View {
        if let id = title.trailer, let link = URL(string: "https://www.youtube.com/watch?v=\(id)") {
            let w = min(width * 0.72, 350)
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Trailers")
                Link(destination: link) {
                    Art(url: URL(string: "https://img.youtube.com/vi/\(id)/hqdefault.jpg"))
                        .frame(width: w, height: w * 9 / 16)
                        .overlay(LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom))
                        .overlay(alignment: .bottomLeading) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(title.name) Trailer").font(.subheadline.weight(.semibold)).lineLimit(1)
                                Label("YouTube", systemImage: "play.fill").font(.caption).foregroundStyle(.white.opacity(0.8))
                            }
                            .padding(14)
                        }
                        .clipShape(.rect(cornerRadius: 14))
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, Dx.gutter)
            }
        }
    }

    @ViewBuilder
    private var relatedRow: some View {
        if !related.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Related")
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(related, id: \.id) { t in
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

    /** The title, its genres and the whole story, in a box (and the facts, as Apple TV's Information) */
    @ViewBuilder
    private var about: some View {
        if title.description != nil || !title.genres.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("About").font(.title2.bold()).padding(.horizontal, Dx.gutter)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title.name).font(.title3.bold())
                    if !title.genres.isEmpty {
                        Text(title.genres.joined(separator: ", ").uppercased()).font(.caption.weight(.semibold)).foregroundStyle(Dx.muted)
                    }
                    if let d = title.description { Text(d).font(.subheadline).foregroundStyle(.white.opacity(0.85)).padding(.top, 6) }
                    let info = [title.year.map { "Released \($0)" }, title.runtime, details.flatMap { $0.directors.isEmpty ? nil : "Directed by \($0.directors.joined(separator: ", "))" }].compactMap { $0 }
                    if !info.isEmpty { Text(info.joined(separator: " · ")).font(.footnote).foregroundStyle(Dx.muted).padding(.top, 6) }
                }
                .padding(20)
                .frame(maxWidth: 620, alignment: .leading)
                .background(Dx.raised, in: .rect(cornerRadius: 16))
                .padding(.horizontal, Dx.gutter)
            }
        }
    }

    // MARK: - Episodes

    @ViewBuilder
    private func episodes(width: CGFloat) -> some View {
        if let d = details, !d.videos.isEmpty {
            let shown = d.videos.filter { $0.season == (season ?? d.seasons.first) }
            VStack(alignment: .leading, spacing: 14) {
                Menu {
                    Picker("Season", selection: Binding(get: { season ?? d.seasons.first ?? 1 }, set: { season = $0 })) {
                        ForEach(d.seasons, id: \.self) { s in Text(s == 0 ? "Specials" : "Season \(s)").tag(s) }
                    }
                } label: {
                    HStack(spacing: 6) {
                        let s = season ?? d.seasons.first ?? 1
                        Text(s == 0 ? "Specials" : "Season \(s)").font(.title2.bold())
                        Image(systemName: "chevron.up.chevron.down").font(.headline).foregroundStyle(Dx.muted)
                    }
                    .foregroundStyle(.white)
                }
                .padding(.horizontal, Dx.gutter)
                ScrollViewReader { proxy in
                    ScrollView(.horizontal) {
                        LazyHStack(spacing: 14) {
                            ForEach(shown) { v in episodeButton(v, width: min(width * 0.72, 340), d: d).id(v.id) }
                        }
                        .scrollTargetLayout()
                        .padding(.horizontal, Dx.gutter)
                    }
                    .scrollTargetBehavior(.viewAligned)
                    .scrollIndicators(.hidden)
                    .scrollClipDisabled()
                    // the episode they're on, in view (once the episodes are there, and again on its season)
                    .task(id: "\(shown.count)-\(season ?? -1)-\(d.videoId ?? "")") {
                        guard let v = nextVideo(d), shown.contains(where: { $0.id == v.id }) else { return }
                        try? await Task.sleep(for: .milliseconds(250))
                        withAnimation(.smooth) { proxy.scrollTo(v.id, anchor: .leading) }
                    }
                }
            }
        }
    }

    private func episodeButton(_ v: Video, width: CGFloat, d: Details) -> some View {
        Button { start(v.id) } label: {
            EpisodeCard(video: v, width: width, fallbackArt: title.backdrop, playing: v.id == nextVideo(d)?.id)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { start(v.id) } label: { Label("Play", systemImage: "play.fill") }
            Button {
                choices = nil
                stremio.open(ref.type, ref.id, videoId: v.id)
                streamsOpen = true
            } label: { Label("Choose a Stream", systemImage: "list.bullet") }
            Divider()
            if v.watched {
                Button { stremio.setWatched(video: v.id, false) } label: { Label("Mark as Unwatched", systemImage: "eye.slash") }
            } else {
                Button { stremio.setWatched(video: v.id, true) } label: { Label("Mark as Watched", systemImage: "eye") }
            }
            if (v.episode ?? 0) > 1 {
                Button { stremio.setWatched(video: v.id, true, upTo: true) } label: { Label("Mark Up to Here as Watched", systemImage: "checkmark.circle") }
            }
        }
    }

    // MARK: - Cast, crew

    @ViewBuilder
    private func extras(width: CGFloat) -> some View {
        let size: CGFloat = width > 600 ? 120 : 84
        if let d = details, !(d.cast.isEmpty && d.directors.isEmpty) || cast?.isEmpty == false {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Cast & Crew")
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: width > 600 ? 26 : 12) {
                        if let cast, !cast.isEmpty {
                            ForEach(Array(cast.enumerated()), id: \.offset) { _, p in person(name: p.string("name"), role: p.text("role"), photo: p.text("photo"), size: size) }
                        } else if let d = details {
                            ForEach(d.directors.prefix(3), id: \.self) { person(name: $0, role: "Director", photo: nil, size: size) }
                            ForEach(d.cast.prefix(15), id: \.self) { person(name: $0, role: nil, photo: nil, size: size) }
                        }
                    }
                    .padding(.horizontal, Dx.gutter)
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
            }
        }
    }

    /** Someone in it: tapping them searches for them, as on the desktop */
    private func person(name: String, role: String?, photo: String?, size: CGFloat) -> some View {
        Button { stremio.searchFor(name) } label: {
            VStack(spacing: 6) {
                ZStack {
                    Circle().fill(Color(white: 0.45))
                    Text(name.split(separator: " ").compactMap(\.first).prefix(2).map(String.init).joined())
                        .font(.system(size: size * 0.36, weight: .semibold))
                    if let photo, let url = URL(string: photo) {
                        WebImage(url: url) { $0.resizable().scaledToFill() } placeholder: { _ in Color.clear }
                    }
                }
                .frame(width: size, height: size)
                .clipShape(.circle)
                Text(name).font(.footnote.weight(.medium)).lineLimit(2).multilineTextAlignment(.center)
                if let role, !role.isEmpty { Text(role).font(.caption).foregroundStyle(Dx.muted).lineLimit(2).multilineTextAlignment(.center) }
            }
            .frame(width: size + 16)
        }
        .buttonStyle(.plain)
    }
}

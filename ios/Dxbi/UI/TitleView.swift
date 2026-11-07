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
    /** Download's More: every stream, to download one */
    @State private var downloadsOpen = false
    /** An anime's other seasons (Kitsu keeps each as a show of its own), shown together on each */
    @State private var seasons: [AnimeSeason] = []
    @State private var reviews: Reviews?
    @State private var reviewsOpen = false
    @State private var moreText = false
    @State private var autoPlayed = false
    /** Started from Shuffle: Next episode carries on with random ones */
    @State private var shuffling = false
    /** The cast with photos (TMDB, with a key in dx.bi settings), else nil */
    @State private var cast: [JSON]?
    /** The trailer, fading in over the art a moment after the page opens */
    @State private var preview = TrailerPreview.Controller()
    @State private var previewOn = false
    /** The art has scrolled away: the top bar's edge shows again */
    @State private var pastArt = false
    /** dx.bi settings → Blur unwatched episodes */
    @State private var blurUnwatched = false
    /** dx.bi settings → Play Trailers (nil until read) */
    @State private var playTrailers: Bool?

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
                        seasonsRow
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
            // the art runs up behind the top bar, as on Apple TV: no band under the bar until the page moves
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > 24 } action: { _, past in
                pastArt = past
            }
            .scrollEdgeEffectHidden(!pastArt, for: .top)
            .ignoresSafeArea(edges: .top)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
        .toolbar {
            if preview.playing {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { preview.setMuted(!preview.muted) } label: {
                        Image(systemName: preview.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .contentTransition(.symbolEffect(.replace))
                    }
                    .accessibilityLabel(preview.muted ? "Sound on" : "Sound off")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let link = shareLink { ShareLink(item: link) { Image(systemName: "square.and.arrow.up") } }
            }
        }
        // the trailer: a moment after the page is ready (not on mobile data or Low Data Mode)
        .task(id: "\(ref.id)-preview-\(title.trailer ?? "")-\(playTrailers.map(String.init) ?? "")") {
            guard title.trailer != nil, playTrailers == true, !NetworkStatus.shared.saving, !previewOn else { return }
            try? await Task.sleep(for: .seconds(1.5))
            if !Task.isCancelled { previewOn = true }
        }
        // quiet while another page is on top, or a video plays
        .onDisappear { preview.pause() }
        .onAppear { if previewOn { preview.resume() } }
        .onChange(of: stremio.playback != nil) { _, playing in
            if playing { preview.setMuted(true); preview.pause() } else { preview.resume() }
        }
        .sheet(isPresented: $streamsOpen) {
            StreamsSheet(choices: current, heading: title.name, subheading: episodeLine) { option, target in
                play(option, in: target)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $downloadsOpen) {
            StreamsSheet(choices: current, heading: title.name, subheading: "Choose a file to download", tapTarget: .download) { option, target in
                downloadsOpen = false
                play(option, in: target)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $reviewsOpen) { ReviewsView(title: title, reviews: reviews) }
        .task {
            let all = await stremio.settings()
            blurUnwatched = all.first { $0.key == "blurUnwatched" }?.value == "true"
            playTrailers = all.first { $0.key == "playTrailers" }?.value != "false"
        }
        .task(id: ref.id) {
            if stremio.details?.metaId != ref.id { stremio.open(ref.type, ref.id) }
            reviews = await stremio.reviews(ref.type, ref.id)
        }
        .task(id: "\(ref.id)-\(details?.state == .ready)") {
            guard let d = details, d.state == .ready else { return }
            let names: [JSON] = d.directors.prefix(3).map { ["name": $0, "role": "Director"] } + d.cast.prefix(13).map { ["name": $0, "role": ""] }
            cast = await stremio.cast(ref.type, imdbId ?? "", names: names)
        }
        .task(id: ref.id) {
            guard ref.id.hasPrefix("kitsu:") else { return }
            seasons = await stremio.animeSeasons(ref.id)
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
            if (ref.play || ref.fromStart || ref.shuffle) && !autoPlayed {
                autoPlayed = true
                start(ref.shuffle ? d.shuffled()?.id : nextVideo(d)?.id, fromStart: ref.fromStart, shuffle: ref.shuffle)
            }
        }
        .onChange(of: details?.state == .ready && ref.type != "series", initial: true) { _, ready in
            if ready && (ref.play || ref.fromStart) && !autoPlayed { autoPlayed = true; start(nil, fromStart: ref.fromStart) }
        }
        // The episode picked shows in its season (back from the player, the page picks the next one
        // when the last was finished: dxbi-engine.js afterPlaying)
        .onChange(of: details?.videoId) { _, id in
            guard let d = details, let v = d.videos.first(where: { $0.id == id }), let s = v.season else { return }
            season = s
        }
        // Next episode from the player: this page starts it, as Play does (once the page has come back from the player)
        .onChange(of: stremio.nextEpisode?.videoId, initial: true) {
            guard let n = stremio.nextEpisode, n.metaId == ref.id else { return }
            stremio.nextEpisode = nil
            Task {
                try? await Task.sleep(for: .seconds(1.3))
                start(n.videoId, shuffle: shuffling)
            }
        }
        // dx.bi's reading of the streams, again as more addons answer
        .task(id: details?.streamsKey) {
            guard details?.videoId != nil else { return }
            choices = await stremio.streams()
            justPlayIfSure()
            updateLaunch()
        }
        // While Play waits for streams, every half second: ask again (nothing may have changed the stream
        // list's key), move the bar, and once the addons have had a few seconds, play the pick
        .task(id: waitingFor) {
            guard waitingFor != nil else { return }
            let began = Date()
            while !Task.isCancelled, waitingFor != nil, stremio.launch != nil {
                try? await Task.sleep(for: .milliseconds(500))
                if Task.isCancelled || waitingFor == nil || stremio.launch == nil { return }
                if details?.videoId != nil, let fresh = await stremio.streams() { choices = fresh }
                if waitingFor == nil || stremio.launch == nil { return }
                justPlayIfSure()
                guard waitingFor != nil, stremio.launch != nil else { return }
                updateLaunch(waited: Date().timeIntervalSince(began))
                guard waitingFor != nil, stremio.launch?.failed == false else { return }
                // (checked while the rest answer, so Play needn't wait for it)
                if let pick = current?.picked, !NetworkStatus.shared.saving { stremio.probeAhead(pick) }
                if let c = current, let pick = c.picked, c.settled || Date().timeIntervalSince(began) > 6 || soonEnough(pick) {
                    // (the loading screen never hands over to the list: the best found plays, as on Lumen TV;
                    // on mobile data or Low Data Mode it offers a few, the smaller ones among them, right here)
                    if NetworkStatus.shared.saving { stremio.offerLaunch(dataChoices(c, pick)) } else { play(pick) }
                    return
                }
                if Date().timeIntervalSince(began) > 20 {
                    stremio.failLaunch("No streams found", "Your addons didn't answer in time.")
                    return
                }
            }
        }
    }

    // MARK: - Playing

    /** What Play starts: the episode last played if it was left part-way, the one after it if it was finished, else the first (the engine decides) */
    private func nextVideo(_ d: Details) -> Video? {
        guard ref.type == "series" else { return nil }
        if let id = d.nextVideoId, let v = d.videos.first(where: { $0.id == id }) { return v }
        return d.videos.first { $0.id == d.videoId } ?? d.videos.first { $0.id == d.resumeVideoId } ?? d.videos.first { ($0.season ?? 0) > 0 && !$0.upcoming } ?? d.videos.first
    }

    private func start(_ videoId: String?, fromStart: Bool = false, shuffle: Bool = false) {
        let target = videoId ?? details?.videoId ?? ref.id
        stremio.startFromBeginning(target, on: fromStart)
        // (Shuffle carries on with random episodes after this one, Next episode included; any other Play ends it)
        shuffling = shuffle
        stremio.shuffling(ref.id, on: shuffle)
        if ref.type == "series", let videoId, videoId != details?.videoId {
            choices = nil
            stremio.open(ref.type, ref.id, videoId: videoId)
        }
        waitingFor = target
        // (always the loading screen, on mobile data too: … → Choose a Stream has the list)
        let video = details?.videos.first { $0.id == target }
        let line = video.flatMap { v in v.season.flatMap { s in v.episode.map { "S\(s), E\($0)" + (v.title.isEmpty ? "" : " · \(v.title)") } } }
        stremio.beginLaunch(title, episode: line, text: "Opening \(title.name)")
        stremio.onLaunchCancel = { waitingFor = nil }
        stremio.onLaunchChoose = { option in play(option) }
        stremio.onLaunchMore = {
            waitingFor = nil
            streamsOpen = true
        }
        justPlayIfSure()
        updateLaunch()
    }

    /** For mobile data: the pick, then the smallest stream of each quality (4K, 1080p, 720p…), four at most */
    private func dataChoices(_ c: StreamChoices, _ pick: StreamOption) -> [StreamOption] {
        var out = [pick]
        let sized = c.items.filter { $0.size > 0 }
        for res in Array(Set(sized.map(\.res))).sorted(by: >) {
            guard out.count < 4, let small = sized.filter({ $0.res == res }).min(by: { $0.size < $1.size }), !out.contains(where: { $0.index == small.index }) else { continue }
            out.append(small)
        }
        return out
    }

    /**
     * Good enough not to wait for the slower addons: ready to play at once (cached) and 4K, or most addons have
     * answered. Waiting for every addon (or 6 seconds) made most of the time on the loading screen.
     */
    private func soonEnough(_ pick: StreamOption) -> Bool {
        guard pick.instant, let d = details, d.addonsAsked > 0 else { return false }
        return pick.res >= 2160 || d.addonsAnswered * 3 >= d.addonsAsked * 2
    }

    /** What the loading screen says while the streams come in */
    private func updateLaunch(waited: Double = 0) {
        guard waitingFor != nil, stremio.launch != nil else { return }
        guard let d = details, d.state == .ready else { return stremio.stepLaunch("Opening \(title.name)", progress: 0.1) }
        let found = current?.items.count ?? 0
        // (and a little more every second, so the bar never stands still while addons are slow)
        let progress = min(0.25 + 0.55 * (d.addonsAsked == 0 ? 0 : Double(d.addonsAnswered) / Double(d.addonsAsked)) + 0.01 * waited, 0.85)
        if let c = current, c.settled, c.picked == nil {
            return stremio.failLaunch("No streams found", "Your addons may not have this one yet.")
        }
        let addons = d.addonsAsked == 0 ? "Asking your addons" : found == 0 ? "\(d.addonsAnswered) of \(d.addonsAsked) addons answered" : "\(found) streams from \(d.addonsAnswered) of \(d.addonsAsked) addons"
        stremio.stepLaunch(
            "Finding the best stream",
            detail: [addons, internetText].compactMap { $0 }.joined(separator: " · "),
            progress: progress
        )
    }

    /** "Your internet: 120 Mbps", once Match my internet speed has measured it */
    private var internetText: String? {
        guard let s = current?.speed, s > 0 else { return nil }
        return "Your internet: \(Int(s)) Mbps"
    }

    /**
     * Just press Play (or the loading screen is up): the stream chosen last time plays at once, anything
     * else once every addon has answered
     */
    private func justPlayIfSure() {
        guard let c = current, c.justPlay || stremio.launch != nil, waitingFor == c.videoId, let pick = c.picked else { return }
        // (on mobile data or Low Data Mode the loading screen offers a choice instead: the loop above)
        guard !NetworkStatus.shared.saving else { return }
        if c.sameAsLastTime || c.settled { play(pick) }
    }

    private func play(_ option: StreamOption, in target: Stremio.Target = .player) {
        waitingFor = nil
        streamsOpen = false
        if stremio.launch != nil {
            let what = [option.quality, option.dv ? "Dolby Vision" : option.hdr ? "HDR" : nil].compactMap { $0 }.joined(separator: " · ")
            stremio.stepLaunch("Starting \(what)", detail: [option.provider, option.sizeText ?? "", option.mbpsText.map { "\($0) stream" } ?? "", internetText ?? "", option.source].filter { !$0.isEmpty && $0 != "Other" }.joined(separator: " · "), progress: 0.88)
        }
        stremio.play(option, in: target)
    }

    /** There's a saved place to go back to (the film's, or the episode Play would start's) */
    private var hasPlace: Bool {
        guard let d = details, d.resumeMs > 0 else { return false }
        if ref.type == "series" { return nextVideo(d)?.id == d.resumeVideoId }
        return d.resumeVideoId == ref.id
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

    /** Apple TV's: the art fills the top (most of the screen on an iPad); the details sit on it, low */
    private func artHeight(_ size: CGSize) -> CGFloat {
        size.width > 600 ? max(min(size.height * 0.8, size.width * 0.75), 520) : max(size.width * 1.35, 520)
    }

    private func header(size: CGSize) -> some View {
        let width = size.width
        let wide = width > 600
        let artHeight = artHeight(size)
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
            // the page scrolls up over the art, which moves up at half its speed (parallax, as Apple TV's) and
            // fades as it's covered (and grows when the page is pulled down). Darkened, no colour: low and on the left, under the text
            // (the trailer plays under the art, which fades away once it's going: WebKit won't start a
            // video in a view that can't be seen)
            ZStack {
                if previewOn, let trailer = title.trailer {
                    TrailerPreview(id: trailer, controller: preview)
                }
                Art(url: title.backdrop)
                    .opacity(preview.playing ? 0 : 1)
                    .animation(.easeIn(duration: 0.9), value: preview.playing)
            }
                .frame(width: width, height: artHeight)
                .clipped()
                .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.35), location: 0), .init(color: .clear, location: 0.22), .init(color: .clear, location: 0.42), .init(color: .black.opacity(0.72), location: 0.8), .init(color: .black, location: 1)], startPoint: .top, endPoint: .bottom))
                .overlay(wide ? LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .leading, endPoint: UnitPoint(x: 0.55, y: 0.5)) : nil)
                .visualEffect { content, proxy in
                    let y = proxy.frame(in: .scrollView(axis: .vertical)).minY
                    let covered = min(max(-y / (artHeight * 0.8), 0), 1)
                    return content
                        .scaleEffect(y > 0 ? 1 + y / artHeight : 1, anchor: .bottom)
                        .offset(y: y < 0 ? -y * 0.5 : 0)
                        .opacity(1 - covered * 0.9)
                }
        }
    }

    /** On the art's left: the logo, what it is, the story (the episode Play starts), the facts, Play */
    private func info(wide: Bool) -> some View {
        let video = details.flatMap(nextVideo)
        let label = video.flatMap { v in v.season.flatMap { s in v.episode.map { "S\(s), E\($0)" + (v.title.isEmpty ? "" : " · \(v.title)") } } }
        return TitleInfo(
            title: title, detailed: true, logoHeight: wide ? 120 : 100,
            episodeLabel: label, episodeText: video?.overview ?? (label == nil ? nil : title.description),
            more: $moreText, formats: formats
        ) {
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
        } buttons: {
            VStack(alignment: .leading, spacing: 14) {
                buttons
                if !wide { credits }
            }
        }
    }

    /** What the best stream offers, as small badges: 4K, Dolby Vision or HDR, Dolby Atmos, IMAX */
    private var formats: [String] {
        guard let p = current?.picked else { return [] }
        return [p.res >= 2160 ? "4K" : nil, p.dv ? "Dolby Vision" : p.hdr10plus ? "HDR10+" : p.hdr ? "HDR" : nil, p.atmos ? "Dolby Atmos" : nil, p.imax ? "IMAX" : nil].compactMap { $0 }
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
                if hasPlace {
                    Button { start(details.flatMap(nextVideo)?.id, fromStart: true) } label: { Label("Start from the Beginning", systemImage: "arrow.counterclockwise") }
                }
                if ref.type == "series", let d = details, d.state == .ready {
                    Button { start(d.shuffled()?.id, shuffle: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                }
                if !title.watched {
                    Button { stremio.setWatched(true) } label: { Label("Mark as Watched", systemImage: "eye") }
                }
                // (partly seen counts: Unwatched puts it back to the start)
                if title.watched || (details?.resumeMs ?? 0) > 0 || stremio.isStarted(title) {
                    Button { stremio.setWatched(false) } label: { Label("Mark as Unwatched", systemImage: "eye.slash") }
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
                        // (no sizes known: the whole list, to choose from)
                        Button { downloadsOpen = true } label: { Label("Download…", systemImage: "arrow.down.circle") }
                    } else {
                        Menu {
                            ForEach(sizes, id: \.option.index) { size in
                                Button { play(size.option, in: .download) } label: {
                                    Text(size.name)
                                    Text([size.option.quality, ByteCountFormatter.string(fromByteCount: Int64(size.option.size), countStyle: .file), size.option.mbpsText, size.option.provider.isEmpty ? nil : size.option.provider].compactMap { $0 }.joined(separator: " · "))
                                }
                            }
                            Divider()
                            // (every stream, with its size: to choose a file yourself)
                            Button { downloadsOpen = true } label: { Label("More…", systemImage: "list.bullet") }
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

    /** An anime's seasons, together: this one marked, the others open their own page */
    @ViewBuilder
    private var seasonsRow: some View {
        if seasons.count > 1 {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Seasons")
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(seasons) { s in
                            let card = VStack(alignment: .leading, spacing: 6) {
                                PosterCard(title: s.title, width: Dx.poster * 0.85)
                                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white, lineWidth: s.current ? 2.5 : 0))
                                Text(s.label).font(.footnote.weight(.semibold)).foregroundStyle(s.current ? .white : Dx.muted).lineLimit(2)
                                    .frame(width: Dx.poster * 0.85, alignment: .leading)
                            }
                            if s.current { card } else {
                                NavigationLink(value: Route.title(s.title.ref)) { card }.buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, Dx.gutter)
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
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
            EpisodeCard(video: v, width: width, fallbackArt: title.backdrop, playing: v.id == nextVideo(d)?.id, blurUnwatched: blurUnwatched)
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
                            ForEach(Array(cast.enumerated()), id: \.offset) { _, p in person(name: p.string("name"), role: p.text("role"), photo: p.text("photo"), id: p.text("id"), size: size) }
                        } else if let d = details {
                            ForEach(d.directors.prefix(3), id: \.self) { person(name: $0, role: "Director", photo: nil, id: nil, size: size) }
                            ForEach(d.cast.prefix(15), id: \.self) { person(name: $0, role: nil, photo: nil, id: nil, size: size) }
                        }
                    }
                    .padding(.horizontal, Dx.gutter)
                }
                .scrollIndicators(.hidden)
                .scrollClipDisabled()
            }
        }
    }

    /** Someone in it: tapping them opens their page (with a TMDB key), else searches for them, as on the desktop */
    @ViewBuilder
    private func person(name: String, role: String?, photo: String?, id: String?, size: CGFloat) -> some View {
        if let id, !id.isEmpty {
            NavigationLink(value: Route.person(id: id, name: name, photo: photo)) { personFace(name: name, role: role, photo: photo, size: size) }
                .buttonStyle(.plain)
        } else {
            Button { stremio.searchFor(name) } label: { personFace(name: name, role: role, photo: photo, size: size) }
                .buttonStyle(.plain)
        }
    }

    private func personFace(name: String, role: String?, photo: String?, size: CGFloat) -> some View {
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
}

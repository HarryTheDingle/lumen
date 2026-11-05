import AVFoundation
import Foundation
import MediaPlayer
import Observation
import UIKit

/**
 * One stream playing: what the page handed over (dxbi-tv.js's play payload), the player, and what
 * goes back to the page (watch progress, the watch party, how it closed).
 */
@MainActor
@Observable
final class Playback: Identifiable {

    struct Segment {
        var type: String
        var start: Double
        var end: Double
        var label: String { type == "intro" ? "Skip Intro" : type == "recap" ? "Skip Recap" : "Skip Credits" }
    }

    struct Subtitle {
        var url: String
        var label: String
        var lang: String
        /** "stream": comes with this stream; "addon": a subtitle addon's search by title (may not fit) */
        var source: String
        /** The one to switch on as the video starts (in Stremio's subtitle language) */
        var start = false
    }

    /** How the picture fits the screen */
    enum Fit: String, CaseIterable {
        case fit = "Fit", fill = "Fill", stretch = "Stretch"
        var gravity: AVLayerVideoGravity { self == .fit ? .resizeAspect : self == .fill ? .resizeAspectFill : .resize }
    }

    let id = UUID()
    let payload: JSON
    let mpv: MPV
    @ObservationIgnored let pip: PictureInPicture
    @ObservationIgnored private weak var stremio: Stremio?

    let title: String
    let subtitle: String
    let accent: UIColor
    let segments: [Segment]
    let autoSkip: String
    let nextTitle: String?
    let nextArt: URL?
    let upNextSeconds: Int
    let subtitles: [Subtitle]

    // what the screen shows (copied from mpv as it changes)
    var position: Double = 0
    var duration: Double = 0
    var paused = false
    var buffering = true
    var speed: Double = 1
    var tracks: [MPV.Track] = []
    var fit = Fit.fit {
        didSet { mpv.gravity(fit.gravity) }
    }
    var party: (code: String, people: [String])?
    var flash: String?
    /** It buffered while playing: the speed it was really getting, and lighter streams that fit it */
    var slow: (mbps: Double, options: [StreamOption])?
    /** The subtitle line Lumen draws (text subtitles), and how it looks (Stremio's subtitle settings) */
    var subtitleLine = ""
    let subtitleStyle: JSON
    /** The Streams button's list */
    var alternatives: [StreamGroup] = []
    /** The stats panel: shown, and what it shows (read every second while it's open) */
    var showStats = false {
        didSet { if showStats { readStats() } }
    }
    var stats: Stats?

    struct Stats {
        var format = "", size = "", fps = "", decoder = "", hdr = false
        var needMbps = 0.0, netMbps = 0.0, ahead = 0.0, dropped = 0, droppedRecently = 0, avsync = 0.0, stalls = 0
        /** What's holding it back, if anything */
        var verdict: (text: String, ok: Bool) {
            if droppedRecently >= 5 || decoder == "Software" {
                return ("The device: \(droppedRecently) frames dropped in 10 s\(decoder == "Software" ? ", decoding in software" : "")", false)
            }
            let starved = ahead < 5 && netMbps > 0 && needMbps > 0 && netMbps < needMbps * 1.2
            if starved || stalls > 0 && ahead < 10 {
                let rates = netMbps > 0 && needMbps > 0 ? ": \(Int(netMbps.rounded())) of \(Int(needMbps.rounded())) Mbps" : ""
                return ("The network\(rates). Try a lighter stream", false)
            }
            return ("Playing smoothly", true)
        }
    }
    @ObservationIgnored private var dropHistory: [(Date, Int)] = []
    @ObservationIgnored private var stallCount = 0
    @ObservationIgnored private var compositing = false
    private static let textCodecs: Set<String> = ["subrip", "srt", "webvtt", "mov_text", "text", "microdvd", "subviewer", "jacosub", "mpl2", "realtext", "sami", "vplayer", "pjs", "stl"]
    /** When the picture started moving (a resumed video starts far in: its position says nothing) */
    @ObservationIgnored private var playingSince: Date?
    /** It's been playing a few seconds: waiting for the network now is a stall, not the start */
    private var settledIn: Bool { playingSince.map { Date().timeIntervalSince($0) > 5 } ?? false }
    /** How far it's gone about stalls: 0 nothing yet, 1 and 2 buffering more, 3 lighter streams offered */
    @ObservationIgnored private var stallStep = 0

    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var lastReport: Date = .distantPast
    @ObservationIgnored private var skipped: Set<String> = []
    @ObservationIgnored private var closing = false
    @ObservationIgnored private var artwork: MPMediaItemArtwork?
    @ObservationIgnored private var tracksTask: Task<Void, Never>?
    @ObservationIgnored private var stalledSince: Date?
    @ObservationIgnored private var subtitlesDecided = false
    /** The seek bar's picture of where it's going (a second mpv, opened when scrubbing starts) */
    @ObservationIgnored private(set) var thumbs: SeekPreview?
    /** What the server gave while the buffer was filling (Mbit/s, once a second): the network's real speed */
    @ObservationIgnored private var speedReadings: [Double] = []
    @ObservationIgnored private var offeredLighter = false

    init(payload: JSON, stremio: Stremio) {
        self.payload = payload
        self.stremio = stremio
        subtitleStyle = payload.object("subtitleStyle") ?? [:]
        title = payload.string("title")
        subtitle = payload.string("subtitle")
        accent = UIColor(hex: payload.string("accent")) ?? UIColor(red: 0.9, green: 0.04, blue: 0.08, alpha: 1)
        segments = payload.objects("skips").compactMap { o in
            let type = o.string("type")
            guard ["intro", "recap", "credits"].contains(type) else { return nil }
            return Segment(type: type, start: o.double("start"), end: o.isNull("end") ? -1 : o.double("end", -1))
        }
        autoSkip = payload.text("autoSkip") ?? "off"
        let next = payload.object("next")
        nextTitle = next?.text("title")
        nextArt = next?.text("thumbnail").flatMap(URL.init(string:))
        upNextSeconds = next == nil ? 0 : Int(payload.text("upNext") ?? "10") ?? 0
        subtitles = payload.objects("subtitles").compactMap { s in
            guard let url = s.text("url") else { return nil }
            return Subtitle(url: url, label: s.text("label") ?? s.string("lang"), lang: s.string("lang"), source: s.text("source") ?? "addon", start: s.bool("start"))
        }
        let player = MPV()
        mpv = player
        pip = PictureInPicture(mpv: player)
        start()
    }

    /**
     * The app's sound as a video player's: only then is it Now Playing (lock screen, Control Centre).
     * Checked every second while it plays: the web page underneath can change it (to ambient sound,
     * which mixes with others and isn't Now Playing)
     */
    private func claimSound() {
        let session = AVAudioSession.sharedInstance()
        guard session.category != .playback || session.mode != .moviePlayback || !session.categoryOptions.isEmpty else { return }
        try? session.setCategory(.playback, mode: .moviePlayback, options: [])
        try? session.setActive(true)
    }

    private func start() {
        claimSound()
        try? AVAudioSession.sharedInstance().setActive(true)
        UIApplication.shared.isIdleTimerDisabled = true
        UIApplication.shared.beginReceivingRemoteControlEvents()
        mpv.onChange = { [weak self] in self?.update() }
        mpv.onLoaded = { [weak self] in self?.loaded() }
        mpv.onEnd = { [weak self] ended, error in
            guard let self else { return }
            if ended { self.finish(ended: true, next: self.nextTitle != nil && self.upNextSeconds > 0) }
            else { self.finish(ended: false, error: error) }
        }
        let headers = (payload.object("headers") ?? [:]).compactMapValues { $0 as? String }
        if let size = payload.object("subtitleStyle")?.double("size"), size > 0 { mpv.subtitleScale(size / 100) }
        mpv.gravity(fit.gravity)
        // (after a stream switch, exactly where the other one was)
        let start = stremio?.resumePoint(for: payload.string("videoId")) ?? payload.double("startMs") / 1000
        thumbs = SeekPreview(url: payload.string("url"), headers: headers)
        // (a lighter stream of the same video for the previews, when there is one; not for a download)
        if payload.text("offline") == nil {
            let videoId = payload.string("videoId")
            Task { [weak self] in
                if let light = await self?.stremio?.previewSource(videoId: videoId) {
                    self?.thumbs?.useLight(url: light.url, headers: light.headers)
                }
            }
        }
        mpv.load(payload.string("url"), start: start, headers: headers, audioLanguage: payload.text("audioLanguage"), subtitleLanguage: payload.text("subtitleLanguage"))
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        remoteCommands()
        nowPlaying()
        loadArtwork()
    }

    /**
     * Another stream of the same video, in this player at this second (the Streams button, or lighter
     * ones offered after stalls): the other file opens where this one was, nothing closes
     */
    func switchTo(url: String, headers: [String: String], label: String) {
        let at = position
        slow = nil
        offeredLighter = false
        stallCount = 0
        stallStep = 0
        stalledSince = nil
        playingSince = nil
        dropHistory = []
        alternatives = []
        subtitlesDecided = false
        compositing = false
        mpv.property("cache-pause-wait", "1")
        mpv.load(url, start: at, headers: headers, audioLanguage: payload.text("audioLanguage"), subtitleLanguage: payload.text("subtitleLanguage"))
        thumbs?.change(url: url, headers: headers)
        show(label.isEmpty ? "Switching stream" : "Switching to \(label)")
    }

    /** The Streams list, from the page (again when the button's opened: the list may have grown) */
    func loadAlternatives() {
        let size = mpv.number("file-size") ?? 0
        let nowMbps = size > 0 && duration > 0 ? size * 8 / duration / 1e6 : 0
        Task {
            let groups = await stremio?.alternatives(nowMbps: nowMbps) ?? []
            // (a new list while the menu's open redraws it under the finger)
            let ids = { (g: [StreamGroup]) in g.map { [$0.title] + $0.options.map { String($0.index) } } }
            if !closing, ids(groups) != ids(alternatives) { alternatives = groups }
        }
    }

    private func loaded() {
        Task {
            // (once the file's size is known, and again when slower addons have answered)
            try? await Task.sleep(for: .seconds(3))
            loadAlternatives()
            try? await Task.sleep(for: .seconds(30))
            loadAlternatives()
        }
        // (listed, not shown: decideSubtitles turns one on only if the audio needs it)
        for s in subtitles { mpv.addSubtitle(s.url, title: s.label, lang: s.lang, start: false) }
    }

    private func update() {
        // (only what changed: each change redraws what shows it, open menus included)
        if position != mpv.position { position = mpv.position }
        if duration != mpv.duration { duration = mpv.duration }
        if paused != mpv.paused { paused = mpv.paused; nowPlaying() }
        if buffering != mpv.buffering { buffering = mpv.buffering }
        if speed != mpv.speed { speed = mpv.speed; nowPlaying() }
        if tracks != mpv.tracks { settleTracks() }
        // Text subtitles (SRT, WebVTT: forced ones, the addons') Lumen draws over the picture. Styled ones
        // (ASS) and pictures (Blu-ray, DVD) need mpv, which then repaints every frame to draw them
        let shown = mpv.tracks.first { $0.type == "sub" && $0.selected }
        let mpvDraws = shown.map { !Self.textCodecs.contains($0.codec.lowercased()) } ?? false
        if mpvDraws != compositing {
            compositing = mpvDraws
            mpv.property("avfoundation-composite-osd", mpvDraws ? "yes" : "no")
            mpv.property("sub-visibility", mpvDraws ? "yes" : "no")
        }
        let line = shown != nil && !mpvDraws ? mpv.subtitleText : ""
        if line != subtitleLine { subtitleLine = line }
        if mpv.pipShowing != pip.active { mpv.pipShowing = pip.active }
        pip.refresh()
        autoSkipIfDue()
        if mpv.started, !mpv.buffering, playingSince == nil { playingSince = Date() }
        if mpv.stalled { if stalledSince == nil, settledIn, Date().timeIntervalSince(mpv.lastSeek) > 4 { stallCount += 1 }; stalledSince = stalledSince ?? Date() } else { stalledSince = nil }
        if !subtitlesDecided, mpv.started { decideSubtitles() }
    }

    /**
     * Subtitles are off unless the audio needs them. mpv starts forced ones (foreign-language lines)
     * itself, and full ones in the viewer's language when the audio's in another. On top of that: a
     * subtitle addon's track for audio in another language, and nothing (but forced) when the audio
     * doesn't say what language it is.
     */
    private func decideSubtitles() {
        let current = mpv.tracks
        guard let audio = current.first(where: { $0.type == "audio" && $0.selected }) else { return }
        // What the viewer chose in an earlier episode comes first
        if let show = showId, let choice = ShowChoices.get(show) {
            applyShowChoice(choice, tracks: current, audio: audio)
            return
        }
        let wanted = Set([payload.text("subtitleLanguage") ?? "", "eng", "en", "english"].filter { !$0.isEmpty })
        let addon = subtitles.first { $0.start }
        // (an addon's file is fetched after the video opens: it's given a few seconds to arrive)
        let arrived = addon.map { a in current.contains { $0.file == a.url } } ?? true
        guard arrived || position > 8 else { return }
        subtitlesDecided = true
        let shown = current.first { $0.type == "sub" && $0.selected }
        if audio.lang.isEmpty {
            if let shown, !shown.forced { mpv.select("sub", nil) }
        } else if !wanted.contains(audio.lang.lowercased()), shown == nil,
                  let a = addon, let track = current.first(where: { $0.file == a.url }) {
            mpv.select("sub", track.id)
        }
    }

    // MARK: - Choices per show

    /** The show an episode belongs to: "tt1234" from "tt1234:2:5" (a film has none) */
    private var showId: String? {
        payload.string("videoId").wholeMatch(of: /(.+):\d+:\d+/).map { String($0.1) }
    }

    /** A track chosen from the menu or the keyboard: chosen again for the show's next episodes */
    func choose(_ type: String, _ track: MPV.Track?) {
        mpv.select(type, track?.id)
        guard let show = showId else { return }
        var choice = ShowChoices.get(show) ?? ShowChoices.Choice()
        if type == "audio" {
            choice.audio = track?.lang
        } else {
            choice.subtitles = track.map { $0.lang.isEmpty ? $0.title : $0.lang } ?? "off"
            choice.forced = track?.forced ?? false
        }
        ShowChoices.set(show, choice)
    }

    /** The show's earlier choices, on this episode (an addon's subtitle file gets a few seconds to arrive) */
    private func applyShowChoice(_ choice: ShowChoices.Choice, tracks current: [MPV.Track], audio: MPV.Track) {
        if let lang = choice.audio, !lang.isEmpty, audio.lang != lang,
           let other = current.first(where: { $0.type == "audio" && $0.lang == lang }) {
            mpv.select("audio", other.id)
        }
        guard let wanted = choice.subtitles else { subtitlesDecided = true; return }
        if wanted == "off" {
            subtitlesDecided = true
            if current.contains(where: { $0.type == "sub" && $0.selected }) { mpv.select("sub", nil) }
            return
        }
        let subs = current.filter { $0.type == "sub" && ($0.lang == wanted || $0.title == wanted) }
        // the same kind (forced or full), from the video before an addon's
        let match = subs.first { $0.forced == choice.forced && !$0.external } ?? subs.first { $0.forced == choice.forced } ?? subs.first
        guard let match else {
            if position > 8 { subtitlesDecided = true }
            return
        }
        subtitlesDecided = true
        if !match.selected { mpv.select("sub", match.id) }
    }

    /** The keyboard's ↑ and ↓: mpv's own volume (the device's stays where it is) */
    func changeVolume(by step: Double) {
        let now = min(max((mpv.number("volume") ?? 100) + step, 0), 100)
        mpv.property("volume", String(format: "%.0f", now))
        if mpv.text("mute") == "yes" { mpv.property("mute", "no") }
        show("Volume \(Int(now))%")
    }

    func toggleMute() {
        let muted = mpv.text("mute") == "yes"
        mpv.property("mute", muted ? "no" : "yes")
        show(muted ? "Sound on" : "Muted")
    }

    /** Subtitles on or off from the keyboard: on is the show's language, else the viewer's, else English */
    func toggleSubtitles() {
        let subs = subtitleTracks
        if subs.contains(where: \.selected) {
            choose("sub", nil)
            show("Subtitles off")
            return
        }
        let remembered = showId.flatMap { ShowChoices.get($0)?.subtitles }.flatMap { $0 == "off" ? nil : $0 }
        let langs = [remembered, payload.text("subtitleLanguage"), "eng", "en"].compactMap { $0 }.filter { !$0.isEmpty }
        let full = subs.filter { !$0.forced }
        guard let pick = langs.lazy.compactMap({ l in full.first { $0.lang == l || $0.title == l } }).first ?? full.first else {
            show("No subtitles for this video")
            return
        }
        choose("sub", pick)
        show("Subtitles: \(trackLabel(pick))")
    }

    private func trackLabel(_ t: MPV.Track) -> String {
        let name = Locale.current.localizedString(forLanguageCode: t.lang) ?? t.lang
        return t.title.isEmpty || t.title == t.lang ? name : "\(name) · \(t.title)"
    }

    /** What the stats panel shows, from mpv */
    private func readStats() {
        var s = Stats()
        s.format = (mpv.text("video-format") ?? "").uppercased()
        let w = Int(mpv.number("video-params/w") ?? 0), h = Int(mpv.number("video-params/h") ?? 0)
        s.size = w > 0 ? "\(w)×\(h)" : ""
        if let fps = mpv.number("container-fps") ?? mpv.number("estimated-vf-fps"), fps > 0 { s.fps = String(format: "%.3g fps", fps) }
        // (mpv says which decoder once the first frame's out: before that it's not known)
        let hw = mpv.text("hwdec-current") ?? ""
        s.decoder = !mpv.started ? "" : hw.isEmpty || hw == "no" ? "Software" : "Hardware"
        s.hdr = ["pq", "hlg"].contains(mpv.text("video-params/gamma") ?? "")
        let size = mpv.number("file-size") ?? 0
        let bitrate = (mpv.number("video-bitrate") ?? 0) + (mpv.number("audio-bitrate") ?? 0)
        s.needMbps = bitrate > 0 ? bitrate / 1e6 : size > 0 && duration > 0 ? size * 8 / duration / 1e6 : 0
        s.netMbps = (mpv.number("cache-speed") ?? 0) * 8 / 1e6
        s.ahead = mpv.number("demuxer-cache-duration") ?? 0
        s.dropped = Int((mpv.number("frame-drop-count") ?? 0) + (mpv.number("decoder-frame-drop-count") ?? 0))
        let now = Date()
        dropHistory.append((now, s.dropped))
        dropHistory.removeAll { now.timeIntervalSince($0.0) > 10 }
        s.droppedRecently = s.dropped - (dropHistory.first?.1 ?? s.dropped)
        s.avsync = mpv.number("avsync") ?? 0
        s.stalls = stallCount
        stats = s
    }

    /**
     * A real stall (playing a while, not just after a seek, not a download or a watch party): first it
     * waits for more before carrying on, 10 seconds and then 30, which rides out a slow patch. Stalling
     * again after that (or at the second stall when the server is clearly slower than the stream needs),
     * it offers lighter streams that fit the speed mpv is really getting from the server.
     */
    private func offerLighterIfStalling() {
        guard !offeredLighter, let since = stalledSince, Date().timeIntervalSince(since) >= 1.5,
              settledIn, Date().timeIntervalSince(mpv.lastSeek) > 4,
              payload.text("offline") == nil, party == nil, stallStep < stallCount else { return }
        stallStep = stallCount
        // (bytes a second while it's catching up: all the server can give)
        let mbps = (mpv.number("cache-speed") ?? 0) * 8 / 1e6
        let size = mpv.number("file-size") ?? 0
        let nowMbps = size > 0 && duration > 0 ? size * 8 / duration / 1e6 : 0
        let tooSlow = mbps > 0 && nowMbps > 0 && mbps < nowMbps * 0.8
        if stallStep == 1 || (stallStep == 2 && !tooSlow) {
            let wait = stallStep == 1 ? 10 : 30
            mpv.property("cache-pause-wait", String(wait))
            show("Buffering \(wait) seconds ahead to keep it smooth")
            return
        }
        offeredLighter = true
        Task {
            let options = await stremio?.lighter(mbps: mbps, nowMbps: nowMbps) ?? []
            guard !closing else { return }
            slow = (mbps, options)
        }
    }

    private func tick() {
        guard !closing else { return }
        if !paused { claimSound() }
        offerLighterIfStalling()
        if showStats { readStats() }
        // (only while it's filling the buffer: with the buffer full it reads nothing, and that's no speed)
        if settledIn, !paused, payload.text("offline") == nil, (mpv.number("demuxer-cache-duration") ?? 999) < 40,
           let speed = mpv.number("cache-speed"), speed > 40_000, speedReadings.count < 1800 {
            speedReadings.append(speed * 8 / 1e6)
        }
        stremio?.partyTick(position: position, duration: duration, paused: paused, buffering: buffering, rate: speed)
        // Stremio's watch progress, every few seconds
        if Date().timeIntervalSince(lastReport) > 5, mpv.started {
            lastReport = Date()
            stremio?.progress(position: position, duration: duration, paused: paused)
        }
        nowPlaying()
    }

    // MARK: - Skip intro, up next

    /** The intro, recap or credits playing now */
    var segment: Segment? {
        segments.first { s in position >= s.start && position < (s.end > 0 ? s.end : duration) - 1 && !(s.type == "credits" && nextTitle != nil) }
    }

    func skip() {
        guard let s = segment else { return }
        skipped.insert("\(s.type)\(s.start)")
        if s.end > 0 { mpv.seek(to: s.end) } else { finish(ended: true, next: nextTitle != nil) }
    }

    private func autoSkipIfDue() {
        guard autoSkip != "off", let s = segment, !skipped.contains("\(s.type)\(s.start)") else { return }
        if autoSkip == "all" || (autoSkip == "intro" && s.type != "credits") { skip() }
    }

    /** The end credits of an episode with another after it: Up Next shows (from the credits, or the last seconds) */
    var upNextDue: Bool {
        guard nextTitle != nil, upNextSeconds > 0, duration > 60 else { return false }
        if let credits = segments.first(where: { $0.type == "credits" }) { return position >= credits.start }
        return duration - position <= Double(upNextSeconds) + 20
    }

    func playNext() { finish(ended: true, next: true) }

    // MARK: - Subtitles and sound

    /**
     * The first list straight away; changes after that once they stop coming. Subtitle files arrive one
     * by one for a while after the start, and each change redrew an open subtitle menu, jumping it about
     */
    private func settleTracks() {
        guard !tracks.isEmpty else { tracks = mpv.tracks; return }
        tracksTask?.cancel()
        tracksTask = Task {
            try? await Task.sleep(for: .seconds(1.5))
            if !Task.isCancelled { tracks = mpv.tracks }
        }
    }

    var audioTracks: [MPV.Track] { tracks.filter { $0.type == "audio" } }
    var subtitleTracks: [MPV.Track] { tracks.filter { $0.type == "sub" } }

    /** Where a subtitle comes from: in the video, with this stream, or a subtitle addon's guess */
    func source(of track: MPV.Track) -> String {
        guard track.external else { return "video" }
        return subtitles.first { $0.url == track.file }?.source ?? "addon"
    }

    // MARK: - From the page's watch party

    func control(_ c: JSON) {
        switch c.string("cmd") {
        case "play": mpv.play()
        case "pause": mpv.pause()
        case "seek": mpv.seek(to: c.double("ms") / 1000)
        case "rate": mpv.setSpeed(c.double("rate", 1))
        case "close": finish(ended: false)
        case "party": party = c.bool("on") ? (c.string("code"), c.strings("people")) : nil
        case "toast": show(c.string("text"))
        default: break
        }
    }

    func show(_ text: String) {
        flash = text
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.5))
            if flash == text { flash = nil }
        }
    }

    // MARK: - Lock screen and Control Centre

    private func nowPlaying() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: subtitle.isEmpty ? title : subtitle,
            MPMediaItemPropertyArtist: subtitle.isEmpty ? "" : title,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: paused ? 0 : speed,
        ]
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /** The title's poster, for the lock screen and Control Centre */
    private func loadArtwork() {
        // the poster, else the backdrop (a download's are on the phone)
        let urls = [payload.text("poster"), payload.text("background")].compactMap { $0 }.compactMap { link -> URL? in
            link.hasPrefix("/") ? URL(fileURLWithPath: link) : URL(string: link)
        }
        guard !urls.isEmpty else { return }
        Task {
            for attempt in 0..<2 {
                for url in urls {
                    if let image = await Self.picture(url) {
                        artwork = Self.artwork(image)
                        nowPlaying()
                        return
                    }
                }
                if attempt == 0 { try? await Task.sleep(for: .seconds(3)) }
            }
        }
    }

    /** The picture, at most 1000 pixels across (iOS drops an artwork it finds too big) */
    nonisolated private static func picture(_ url: URL) async -> UIImage? {
        let data: Data
        if url.isFileURL {
            guard let d = try? Data(contentsOf: url) else { return nil }
            data = d
        } else {
            var request = URLRequest(url: url, timeoutInterval: 15)
            request.setValue("image/jpeg,image/png,image/*", forHTTPHeaderField: "Accept")
            guard let (d, response) = try? await URLSession.shared.data(for: request),
                  ((response as? HTTPURLResponse)?.statusCode ?? 200) < 400 else { return nil }
            data = d
        }
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let scale = min(1, 1000 / max(image.size.width * image.scale, image.size.height * image.scale))
        let size = CGSize(width: (image.size.width * image.scale * scale).rounded(), height: (image.size.height * image.scale * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    /**
     * (iOS asks for the picture from its own thread: the closure mustn't belong to the main one.)
     * Drawn at the size it asks for
     */
    nonisolated private static func artwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { @Sendable asked in
            guard asked.width > 0, asked.height > 0, asked != image.size else { return image }
            let fit = min(asked.width / image.size.width, asked.height / image.size.height)
            let size = CGSize(width: max(1, (image.size.width * fit).rounded()), height: max(1, (image.size.height * fit).rounded()))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        }
    }

    private func remoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand, center.skipForwardCommand,
                        center.skipBackwardCommand, center.changePlaybackPositionCommand] {
            command.isEnabled = true
        }
        center.playCommand.addTarget { [weak self] _ in self?.mpv.play(); return .success }
        center.pauseCommand.addTarget { [weak self] _ in self?.mpv.pause(); return .success }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.mpv.toggle(); return .success }
        center.skipForwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.preferredIntervals = [10]
        center.skipForwardCommand.addTarget { [weak self] e in self?.mpv.seek(by: (e as? MPSkipIntervalCommandEvent)?.interval ?? 10); return .success }
        center.skipBackwardCommand.addTarget { [weak self] e in self?.mpv.seek(by: -((e as? MPSkipIntervalCommandEvent)?.interval ?? 10)); return .success }
        center.changePlaybackPositionCommand.addTarget { [weak self] e in
            if let e = e as? MPChangePlaybackPositionCommandEvent { self?.mpv.seek(to: e.positionTime) }
            return .success
        }
        if nextTitle != nil {
            center.nextTrackCommand.isEnabled = true
            center.nextTrackCommand.addTarget { [weak self] _ in self?.playNext(); return .success }
        }
    }

    private func clearRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand, center.skipForwardCommand,
                        center.skipBackwardCommand, center.changePlaybackPositionCommand, center.nextTrackCommand] {
            command.removeTarget(nil)
        }
        center.nextTrackCommand.isEnabled = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - Closing

    func finish(ended: Bool, error: String? = nil, next: Bool = false) {
        guard !closing else { return }
        closing = true
        timer?.invalidate()
        let at = position, length = duration
        thumbs?.stop()
        // What this network really gave: a high reading (the 75th of 100), from enough of them
        if speedReadings.count >= 10 {
            let sorted = speedReadings.sorted()
            stremio?.networkSample(sorted[sorted.count * 3 / 4])
        }
        pip.end()
        mpv.quit()
        UIApplication.shared.isIdleTimerDisabled = false
        clearRemoteCommands()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        stremio?.closed(position: at, duration: length, ended: ended, error: error, next: next)
    }
}

extension UIColor {
    convenience init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        guard s.hasPrefix("#") else { return nil }
        s.removeFirst()
        guard s.count == 6 || s.count == 8, let v = UInt64(s, radix: 16) else { return nil }
        let rgb = s.count == 8 ? v >> 8 : v
        self.init(red: CGFloat((rgb >> 16) & 0xff) / 255, green: CGFloat((rgb >> 8) & 0xff) / 255, blue: CGFloat(rgb & 0xff) / 255, alpha: 1)
    }
}

/** Audio and subtitles chosen for a show, kept on this device (the newest 300 shows) */
enum ShowChoices {
    struct Choice: Codable {
        /** The audio track's language */
        var audio: String?
        /** "off", or the subtitle track's language */
        var subtitles: String?
        var forced = false
        var at = Date()
    }

    private static let key = "lumen.showChoices"

    static func get(_ show: String) -> Choice? { all()[show] }

    static func set(_ show: String, _ choice: Choice) {
        var list = all()
        var c = choice
        c.at = Date()
        list[show] = c
        if list.count > 300 {
            for old in list.sorted(by: { $0.value.at < $1.value.at }).prefix(list.count - 300) { list[old.key] = nil }
        }
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: key)
    }

    private static func all() -> [String: Choice] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [:] }
        return (try? JSONDecoder().decode([String: Choice].self, from: data)) ?? [:]
    }
}

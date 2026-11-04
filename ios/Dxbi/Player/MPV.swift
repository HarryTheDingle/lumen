import AVFoundation
import CoreMedia
import Foundation
import MPVKit
import UIKit

/**
 * mpv (through MPVKit) playing what Apple's player can't (MKV, HEVC, Dolby and DTS sound, ASS
 * subtitles), which is most of what addons find. It draws into an AVSampleBufferDisplayLayer
 * (mpv's avfoundation output), the kind of layer picture in picture can take over.
 */
final class MPV {

    struct Track: Identifiable, Hashable {
        var id: Int
        var type: String
        var title: String
        var lang: String
        var codec: String
        var external: Bool
        var file: String
        var selected: Bool
        /** A forced subtitle: just the lines in another language */
        var forced: Bool = false
    }

    /** What the player shows changes: always told on the main thread */
    var onChange: (() -> Void)?
    /** The file ended (true) or couldn't play (the reason) */
    var onEnd: ((Bool, String?) -> Void)?
    var onLoaded: (() -> Void)?
    /** A seek (or the start) has its first frame on screen */
    var onRestart: (() -> Void)?

    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    private(set) var paused = false
    private(set) var buffering = true
    private(set) var speed: Double = 1
    private(set) var tracks: [Track] = []
    private(set) var started = false
    /** Waiting for the network while playing (not loading, not a seek: those set [buffering] too) */
    private(set) var stalled = false
    /** The subtitle line showing now (text subtitles, which Lumen draws itself) */
    private(set) var subtitleText = ""
    private(set) var lastSeek = Date.distantPast

    let layer = AVSampleBufferDisplayLayer()
    /** The layer's clock: the frames mpv sends are stamped from it, and picture in picture reads it */
    private var timebase: CMTimebase?
    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "dxbi.mpv", qos: .userInitiated)

    /**
     * [preview]: the seek bar's picture of where it's going. A second, silent mpv on the same stream that
     * shows only keyframes, with a small buffer, no sound and no subtitles
     */
    init(preview: Bool = false) {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = UIColor.black.cgColor
        if preview {
            // mpv's output reads the layer's clock to stamp each frame (with none it crashed): the
            // preview's just runs, so a frame shows as soon as it's decoded
            if CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault, sourceClock: CMClockGetHostTimeClock(), timebaseOut: &timebase) == noErr, let timebase {
                CMTimebaseSetTime(timebase, time: .zero)
                CMTimebaseSetRate(timebase, rate: 1)
                layer.controlTimebase = timebase
            }
            setUpPreview()
            return
        }
        layer.preventsDisplaySleepDuringVideoPlayback = true
        if CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault, sourceClock: CMClockGetHostTimeClock(), timebaseOut: &timebase) == noErr, let timebase {
            CMTimebaseSetTime(timebase, time: .zero)
            CMTimebaseSetRate(timebase, rate: 0)
            layer.controlTimebase = timebase
        }
        guard let mpv = mpv_create() else { return }
        handle = mpv
        option("wid", layer)
        set("vo", "avfoundation")
        // mpv repaints every frame into a new (64-bit, HDR) picture to draw subtitles on it: at 4K that
        // dropped frames on older iPads. Off until subtitles show (Lumen draws its own controls)
        set("avfoundation-composite-osd", "no")
        // (Lumen draws text subtitles over the picture; mpv draws only styled and picture ones)
        set("sub-visibility", "no")
        set("hwdec", "videotoolbox")
        set("hwdec-software-fallback", "yes")
        set("video-rotate", "no")
        // (the end of a file is reported as its end, for Up Next)
        set("keep-open", "no")
        set("idle", "yes")
        // Subtitles start only when they're needed: in the viewer's language (slang) when the audio isn't,
        // or the forced ones (foreign-language lines) when it is. Never just because a file has some.
        set("subs-with-matching-audio", "forced")
        set("subs-fallback", "no")
        set("subs-fallback-forced", "yes")
        set("subs-match-os-language", "no")
        set("cache", "yes")
        // High-bitrate files (4K remuxes run 50–80 Mbit/s) used up 150 MB in seconds: any slow patch from
        // the server showed. More ahead (the Mac has the memory; an iPad less), read in bigger pieces, and
        // a dropped connection picked up again rather than ending the file
        set("demuxer-max-bytes", ProcessInfo.processInfo.isiOSAppOnMac ? "512MiB" : "256MiB")
        set("stream-buffer-size", "2MiB")
        // The buffer stays in memory, freed when the player closes: never written to the device's storage
        set("cache-on-disk", "no")
        set("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5")
        #if DEBUG
        // Test builds: what mpv saw (dropped frames, a slow decoder, timing) in Documents/mpv.log
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            set("log-file", dir.appendingPathComponent("mpv.log").path)
        }
        #endif
        set("demuxer-max-back-bytes", "50MiB")
        set("network-timeout", "30")
        set("user-agent", "Mozilla/5.0 Lumen")
        set("sub-ass-override", "scale")
        mpv_request_log_messages(mpv, "no")
        mpv_initialize(mpv)
        for (name, format) in [("time-pos", MPV_FORMAT_DOUBLE), ("duration", MPV_FORMAT_DOUBLE), ("pause", MPV_FORMAT_FLAG),
                               ("paused-for-cache", MPV_FORMAT_FLAG), ("speed", MPV_FORMAT_DOUBLE), ("track-list", MPV_FORMAT_NONE),
                               ("seeking", MPV_FORMAT_FLAG), ("core-idle", MPV_FORMAT_FLAG), ("sub-text", MPV_FORMAT_STRING)] {
            mpv_observe_property(mpv, 0, name, format)
        }
        mpv_set_wakeup_callback(mpv, { context in
            guard let context else { return }
            Unmanaged<MPV>.fromOpaque(context).takeUnretainedValue().readEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    deinit { quit() }

    /** Stops for good (mpv's own thread finishes in the background) */
    private func setUpPreview() {
        guard let mpv = mpv_create() else { return }
        handle = mpv
        option("wid", layer)
        set("vo", "avfoundation")
        set("avfoundation-composite-osd", "no")
        set("hwdec", "videotoolbox")
        set("hwdec-software-fallback", "yes")
        set("aid", "no")
        set("sid", "no")
        set("sub-visibility", "no")
        // (the nearest keyframe: quick, and close enough for a preview)
        set("hr-seek", "no")
        set("pause", "yes")
        set("keep-open", "yes")
        set("idle", "yes")
        set("cache", "yes")
        set("cache-on-disk", "no")
        set("demuxer-max-bytes", "24MiB")
        set("demuxer-max-back-bytes", "8MiB")
        set("demuxer-readahead-secs", "2")
        set("network-timeout", "20")
        set("user-agent", "Mozilla/5.0 Lumen")
        set("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=3")
        #if DEBUG
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            set("log-file", dir.appendingPathComponent("preview.log").path)
        }
        #endif
        mpv_request_log_messages(mpv, "no")
        mpv_initialize(mpv)
        // (to hear when each seek's frame is up: the next waits for it)
        mpv_set_wakeup_callback(mpv, { context in
            guard let context else { return }
            Unmanaged<MPV>.fromOpaque(context).takeUnretainedValue().readEvents()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    func quit() {
        guard let mpv = handle else { return }
        handle = nil
        mpv_set_wakeup_callback(mpv, nil, nil)
        // (after anything still on its way to it). The layer is kept until mpv has finished: its video
        // output's thread still uses it as it shuts down, and a layer let go of first crashed it
        let layer = self.layer
        control.async {
            // Stopping first, then a moment before the end: the video output's stop hands work to the main
            // thread that still uses its state, and ending straight away freed that under it (a crash on
            // closing, seen with the seek bar's preview)
            var stop: [UnsafePointer<CChar>?] = [UnsafePointer(strdup("stop")), nil]
            mpv_command(mpv, &stop)
            free(UnsafeMutablePointer(mutating: stop[0]))
            Thread.sleep(forTimeInterval: 0.5)
            mpv_terminate_destroy(mpv)
            withExtendedLifetime(layer) {}
        }
        layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }

    // MARK: - Setting up

    private func set(_ name: String, _ value: String) {
        guard let handle else { return }
        mpv_set_option_string(handle, name, value)
    }

    private func option(_ name: String, _ layer: CALayer) {
        guard let handle else { return }
        var pointer = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
        mpv_set_option(handle, name, MPV_FORMAT_INT64, &pointer)
    }

    // Everything asked of mpv goes through its own queue: mpv can take a moment to answer (opening a
    // stream, fetching a subtitle file), and waiting on the main thread held up the whole screen
    private let control = DispatchQueue(label: "dxbi.mpv.control", qos: .userInitiated)

    func property(_ name: String, _ value: String) {
        control.async { [weak self] in
            guard let handle = self?.handle else { return }
            mpv_set_property_string(handle, name, value)
        }
    }

    /** [wait]: false runs it in mpv's background (subtitle downloads), so nothing queues behind it */
    func command(_ args: [String], wait: Bool = true) {
        control.async { [weak self] in
            guard let handle = self?.handle else { return }
            var cargs: [UnsafePointer<CChar>?] = args.map { UnsafePointer(strdup($0)) }
            cargs.append(nil)
            defer { for p in cargs where p != nil { free(UnsafeMutablePointer(mutating: p)) } }
            if wait { mpv_command(handle, &cargs) } else { mpv_command_async(handle, 0, &cargs) }
        }
    }

    /** Text mpv reports (video-format, hwdec-current…), read when asked */
    func text(_ name: String) -> String? {
        string(name)
    }

    /** A number mpv reports (cache-speed, file-size…), read when asked */
    func number(_ name: String) -> Double? {
        string(name).flatMap(Double.init)
    }

    private func string(_ name: String) -> String? {
        guard let handle, let c = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }

    // MARK: - Playing

    func load(_ url: String, start: Double, headers: [String: String], audioLanguage: String?, subtitleLanguage: String? = nil) {
        let fields = headers.map { "\($0.key): \($0.value)" }.joined(separator: ",")
        property("http-header-fields", fields)
        // (the startup option doesn't reach the video output, which is made for each file: set just before,
        // it does. Playback turns it on only for subtitles mpv has to draw)
        property("avfoundation-composite-osd", "no")
        property("sub-visibility", "no")
        property("start", start > 0 ? String(format: "%.2f", start) : "none")
        if let audioLanguage, !audioLanguage.isEmpty { property("alang", "\(audioLanguage),eng,en") }
        // (the viewer's subtitle language, then English: see subs-with-matching-audio)
        property("slang", ([subtitleLanguage ?? ""] + ["eng", "en"]).filter { !$0.isEmpty }.joined(separator: ","))
        command(["loadfile", url, "replace"])
    }

    /** A subtitle file, listed but not shown until it's chosen ([start]: shown straight away) */
    func addSubtitle(_ url: String, title: String, lang: String, start: Bool = false) {
        command(["sub-add", url, start ? "select" : "auto", title, lang], wait: false)
    }

    // (shown at once: the button doesn't wait for mpv to say so)
    func play() { paused = false; property("pause", "no"); onChange?() }
    func pause() { paused = true; property("pause", "yes"); onChange?() }
    func toggle() { paused ? play() : pause() }
    func seek(to seconds: Double) { command(["seek", String(format: "%.3f", max(0, seconds)), "absolute+exact"]) }
    func seek(by seconds: Double) { command(["seek", String(format: "%.3f", seconds), "relative+exact"]) }
    func setSpeed(_ rate: Double) { property("speed", String(format: "%.2f", rate)) }
    func select(_ type: String, _ id: Int?) { property(type == "audio" ? "aid" : "sid", id.map(String.init) ?? "no") }
    /** Fit the screen, fill it (cropping the edges) or stretch to it */
    func gravity(_ g: AVLayerVideoGravity) { layer.videoGravity = g }
    func subtitleScale(_ scale: Double) { property("sub-scale", String(format: "%.2f", scale)) }
    func subtitleDelay(_ seconds: Double) { property("sub-delay", String(format: "%.2f", seconds)) }

    /** Picture in picture is showing: its progress bar reads the layer's clock */
    var pipShowing = false

    /**
     * The layer's clock. mpv's AVFoundation output stamps each frame from it and does all the timing
     * itself, so it only runs or stops with pause (not stalls, not the speed). Moving it, to follow the
     * position, threw out the frames already stamped: a frame glitched a second into playing, and older
     * ones flashed up. Only picture in picture, whose progress bar reads it, has it follow the position.
     */
    private func clock() {
        guard let timebase else { return }
        let rate: Float64 = paused ? 0 : 1
        if CMTimebaseGetRate(timebase) != rate { CMTimebaseSetRate(timebase, rate: rate) }
        if pipShowing, abs(CMTimebaseGetTime(timebase).seconds - position) > 1 {
            CMTimebaseSetTime(timebase, time: CMTime(seconds: position, preferredTimescale: 1000))
        }
    }

    // MARK: - Hearing back

    private func readTracks() -> [Track] {
        let count = Int(string("track-list/count") ?? "0") ?? 0
        return (0..<count).map { i in
            let p = "track-list/\(i)/"
            return Track(
                id: Int(string(p + "id") ?? "0") ?? 0,
                type: string(p + "type") ?? "",
                title: string(p + "title") ?? "",
                lang: string(p + "lang") ?? "",
                codec: string(p + "codec") ?? "",
                external: string(p + "external") == "yes",
                file: string(p + "external-filename") ?? "",
                selected: string(p + "selected") == "yes",
                forced: string(p + "forced") == "yes"
            )
        }
    }

    private func readEvents() {
        queue.async { [weak self] in
            guard let self else { return }
            while let handle = self.handle {
                guard let event = mpv_wait_event(handle, 0)?.pointee, event.event_id != MPV_EVENT_NONE else { break }
                switch event.event_id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    guard let property = UnsafePointer<mpv_event_property>(OpaquePointer(event.data))?.pointee else { break }
                    let name = String(cString: property.name)
                    let double = property.format == MPV_FORMAT_DOUBLE ? property.data?.assumingMemoryBound(to: Double.self).pointee : nil
                    let flag = property.format == MPV_FORMAT_FLAG ? (property.data?.assumingMemoryBound(to: Int32.self).pointee ?? 0) != 0 : nil
                    let tracks = name == "track-list" ? self.readTracks() : nil
                    let text = property.format == MPV_FORMAT_STRING
                        ? property.data?.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee.map { String(cString: $0) } : nil
                    DispatchQueue.main.async {
                        switch name {
                        case "time-pos": if let double { self.position = double; if double > 0 { self.started = true } }
                        case "duration": if let double { self.duration = double }
                        case "pause": if let flag { self.paused = flag }
                        case "paused-for-cache": if let flag { self.buffering = flag; self.stalled = flag }
                        // (a seek that's done stops the spinner too, unless it's still waiting for the network)
                        case "seeking": if let flag { if flag { self.buffering = true; self.lastSeek = Date() } else { self.buffering = self.stalled } }
                        case "core-idle": if let flag, !flag { self.buffering = false }
                        case "speed": if let double { self.speed = double }
                        case "sub-text": self.subtitleText = text ?? ""
                        case "track-list": if let tracks { self.tracks = tracks }
                        default: break
                        }
                        self.clock()
                        self.onChange?()
                    }
                case MPV_EVENT_PLAYBACK_RESTART:
                    DispatchQueue.main.async { self.onRestart?() }
                case MPV_EVENT_FILE_LOADED:
                    DispatchQueue.main.async {
                        self.buffering = false
                        self.onLoaded?()
                        self.onChange?()
                    }
                case MPV_EVENT_END_FILE:
                    guard let end = UnsafePointer<mpv_event_end_file>(OpaquePointer(event.data))?.pointee else { break }
                    let reason = end.reason
                    let error = end.error < 0 ? String(cString: mpv_error_string(end.error)) : nil
                    DispatchQueue.main.async {
                        if reason == MPV_END_FILE_REASON_EOF { self.onEnd?(true, nil) }
                        else if reason == MPV_END_FILE_REASON_ERROR { self.onEnd?(false, error ?? "it couldn't be opened") }
                    }
                case MPV_EVENT_SHUTDOWN:
                    return
                default:
                    break
                }
            }
        }
    }
}


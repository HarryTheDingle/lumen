import SwiftUI
import UIKit

/**
 * The picture of where a seek will land, over the seek bar while it's dragged (or the screen's swiped).
 * A second, silent mpv on the same stream. It opens in the background when the controls show (opening a
 * remote file takes a few seconds, mostly reading its index), then jumps between keyframes as the finger
 * moves: one seek at a time, each shown before the next goes straight to where the finger is then (seeks
 * sent as it moved cancelled each other, and nothing showed until it stopped). Half a minute without
 * scrubbing, it closes, so it costs nothing while watching.
 */
@MainActor
final class SeekPreview {
    private var mpv: MPV?
    private var source: (url: String, headers: [String: String])
    private var shown = -10.0
    private var wanted: Double?
    private var busy = false
    private var busySince = Date.distantPast
    private var idle: Task<Void, Never>?

    init(url: String, headers: [String: String]) {
        source = (url, headers)
    }

    /** The layer showing the preview (none until it's opened) */
    var layer: CALayer? { mpv?.layer }

    /** The stream changed (a switch): it opens the new one next time */
    func change(url: String, headers: [String: String]) {
        source = (url, headers)
        stop()
    }

    /** The controls are showing: scrubbing may be next, so it opens now, where the video is */
    func warm(at seconds: Double) {
        if mpv == nil { open(at: seconds) }
        rest()
    }

    func show(_ seconds: Double) {
        idle?.cancel()
        wanted = seconds
        if mpv == nil { open(at: seconds) } else { next() }
    }

    private func open(at seconds: Double) {
        let m = MPV(preview: true)
        m.onRestart = { [weak self] in
            self?.busy = false
            self?.next()
        }
        mpv = m
        busy = true
        busySince = Date()
        shown = seconds
        m.load(source.url, start: seconds, headers: source.headers, audioLanguage: nil)
    }

    /** The finger's latest place, once the last seek's frame is up (keyframes are rarely 2 s apart) */
    private func next() {
        // (a seek that never answered doesn't hold it up for good)
        if busy && Date().timeIntervalSince(busySince) > 4 { busy = false }
        guard !busy, let mpv, let target = wanted else { return }
        wanted = nil
        guard abs(target - shown) >= 2 else { return }
        busy = true
        busySince = Date()
        shown = target
        mpv.command(["seek", String(format: "%.1f", target), "absolute+keyframes"])
    }

    /** Scrubbing stopped: kept a while for the next scrub, then closed */
    func rest() {
        idle?.cancel()
        idle = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            if !Task.isCancelled { self?.stop() }
        }
    }

    func stop() {
        idle?.cancel()
        mpv?.onRestart = nil
        mpv?.quit()
        mpv = nil
        busy = false
        wanted = nil
        shown = -10
    }
}

/** The preview's picture, in a small rounded frame */
struct SeekPreviewSurface: UIViewRepresentable {
    let layer: CALayer
    func makeUIView(context: Context) -> Holder { Holder(layer) }
    func updateUIView(_ view: Holder, context: Context) {}

    final class Holder: UIView {
        let picture: CALayer
        init(_ layer: CALayer) {
            picture = layer
            super.init(frame: .zero)
            backgroundColor = .black
            self.layer.addSublayer(layer)
        }
        required init?(coder: NSCoder) { fatalError() }
        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            picture.frame = bounds
            CATransaction.commit()
        }
    }
}

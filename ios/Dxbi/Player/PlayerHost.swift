import SwiftUI
import WebKit
import UIKit

/**
 * The player's own screen, sideways. Shown over the app (which stays upright underneath) rather than
 * as a SwiftUI cover: a cover turned the whole app sideways with it, and the title page came back
 * laid out wrong when it turned upright again.
 */
final class PlayerHost: UIHostingController<AnyView> {

    private static weak var shown: PlayerHost?
    private weak var playback: Playback?
    /** Stremio's page: it goes with the player (see PageBox) */
    private static weak var page: WKWebView?

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { AppDelegate.isPad ? .all : .landscape }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .landscapeRight }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }

    // A keyboard (an iPad's, or the Mac's). Holding ⌘ on an iPad lists them
    override var canBecomeFirstResponder: Bool { true }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeFirstResponder()
    }

    private static let keys: [(input: String, title: String)] = [
        (" ", "Play or Pause"), ("k", "Play or Pause"),
        (UIKeyCommand.inputLeftArrow, "Back 10 Seconds"), ("j", "Back 10 Seconds"),
        (UIKeyCommand.inputRightArrow, "Forward 10 Seconds"), ("l", "Forward 10 Seconds"),
        (UIKeyCommand.inputUpArrow, "Volume Up"), (UIKeyCommand.inputDownArrow, "Volume Down"),
        ("m", "Mute"), ("s", "Subtitles On or Off"), ("n", "Next Episode"), ("f", "Full Screen"),
    ]

    override var keyCommands: [UIKeyCommand]? {
        Self.keys.compactMap { key in
            // (full screen is the Mac's window; an iPad's player always fills the screen)
            if key.input == "f" && !ProcessInfo.processInfo.isiOSAppOnMac { return nil }
            let command = UIKeyCommand(title: key.title, action: #selector(key(_:)), input: key.input)
            // (the arrows and Space would otherwise move focus or scroll)
            command.wantsPriorityOverSystemBehavior = true
            // (one name in the ⌘ list for keys that do the same)
            if ["k", "j", "l"].contains(key.input) { command.attributes = .hidden }
            return command
        }
    }

    @objc private func key(_ command: UIKeyCommand) {
        guard let playback else { return }
        switch command.input {
        case " ", "k": playback.mpv.toggle()
        case UIKeyCommand.inputLeftArrow, "j":
            playback.mpv.seek(by: -10)
            playback.show("−10 seconds")
        case UIKeyCommand.inputRightArrow, "l":
            playback.mpv.seek(by: 10)
            playback.show("+10 seconds")
        case UIKeyCommand.inputUpArrow: playback.changeVolume(by: 10)
        case UIKeyCommand.inputDownArrow: playback.changeVolume(by: -10)
        case "m": playback.toggleMute()
        case "s": playback.toggleSubtitles()
        case "n":
            if playback.nextTitle != nil { playback.playNext() } else { playback.show("No next episode") }
        case "f":
            // (the Mac's window, through the responder chain: an iPad app has no NSWindow of its own)
            UIApplication.shared.sendAction(Selector(("toggleFullScreen:")), to: nil, from: nil, for: nil)
        default: break
        }
    }

    @MainActor
    static func show(_ playback: Playback, stremio: Stremio) {
        if let old = shown {
            if let page, page.superview === old.view { PageBox.bringBack(page) }
            old.dismiss(animated: false)
        }
        let host = PlayerHost(rootView: AnyView(PlayerScreen(playback: playback).environment(stremio).preferredColorScheme(.dark)))
        host.modalPresentationStyle = .fullScreen
        host.view.backgroundColor = .black
        host.playback = playback
        shown = host
        page = stremio.page.webView
        present(host, playback: playback, tries: 0)
    }

    /**
     * iOS won't show it while something else is coming or going (the stream list closing as Play is
     * pressed): it waits for that, and tries again
     */
    @MainActor
    private static func present(_ host: PlayerHost, playback: Playback, tries: Int) {
        guard shown === host, host.presentingViewController == nil else { return }
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              var top = scene.keyWindow?.rootViewController else { return }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        let busy = top.transitionCoordinator != nil || top.isBeingDismissed || top.isBeingPresented || top.presentedViewController != nil
        if busy {
            guard tries < 40 else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { present(host, playback: playback, tries: tries + 1) }
            return
        }
        // (sideways only once the player is there to be sideways)
        if !AppDelegate.isPad { AppDelegate.orientations = .landscape }
        if let page { PageBox.lend(page, to: host.view) }
        top.present(host, animated: true)
    }

    @MainActor
    static func hide() {
        guard let host = shown else { return }
        shown = nil
        if !AppDelegate.isPad { AppDelegate.orientations = .portrait }
        let presenter = host.presentingViewController
        host.dismiss(animated: true) {
            // the page back under the app's screens, unless another player has it already
            if let page, page.superview === host.view { PageBox.bringBack(page) }
            guard !AppDelegate.isPad else { return }
            presenter?.setNeedsUpdateOfSupportedInterfaceOrientations()
            (presenter?.view.window?.windowScene)?.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        }
    }
}

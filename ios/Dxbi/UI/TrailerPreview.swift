import SwiftUI
import WebKit

/**
 * A title's trailer, playing silently and looped over its art, as Apple TV's pages do (and Lumen's
 * Android TV cards): YouTube's privacy-enhanced player, out of sight until the video is really playing,
 * then faded in, so YouTube's loading screen and its errors never show. [controller] mutes and pauses it.
 */
struct TrailerPreview: UIViewRepresentable {
    var id: String
    var controller: Controller

    /** What the page asks of the trailer, and whether it's playing yet */
    @MainActor @Observable
    final class Controller {
        var playing = false
        var muted = true
        @ObservationIgnored weak var web: WKWebView?

        func setMuted(_ on: Bool) {
            muted = on
            run(on ? "player && player.mute()" : "player && player.unMute()")
        }
        /** It was stopped (a video played, another page was on top): YouTube shows its pause mark as it starts again */
        @ObservationIgnored var stopped = false

        func pause() {
            playing = false
            stopped = true
            run("player && player.pauseVideo()")
        }
        func resume() { run("player && player.playVideo()") }

        private func run(_ js: String) { web?.evaluateJavaScript(js, completionHandler: nil) }
    }

    func makeCoordinator() -> Coordinator { Coordinator(controller) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.userContentController.add(context.coordinator, name: "trailer")
        let web = WKWebView(frame: .zero, configuration: config)
        // a picture behind the page, nothing to touch
        web.isUserInteractionEnabled = false
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        web.scrollView.isScrollEnabled = false
        controller.web = web
        // YouTube's player wants to know which site it's on: Stremio's, as in Lumen's own cards
        web.loadHTMLString(Self.page(id), baseURL: URL(string: "https://web.stremio.com/"))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "trailer")
        web.loadHTMLString("", baseURL: nil)
    }

    final class Coordinator: NSObject, WKScriptMessageHandler {
        let controller: Controller
        @MainActor private var last: String?
        init(_ controller: Controller) { self.controller = controller }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            let state = message.body as? String
            Task { @MainActor in
                self.last = state
                if state == "playing" {
                    // (a moment for YouTube's own play mark to go, so it's never seen over the page: longer after a
                    // stop, when its pause mark lingers)
                    try? await Task.sleep(for: .milliseconds(self.controller.stopped ? 2600 : 700))
                    if self.last == "playing" {
                        self.controller.stopped = false
                        self.controller.playing = true
                    }
                } else {
                    self.controller.playing = false
                }
            }
        }
    }

    /**
     * The player fills the art and more, cropped like a picture set to fill (its title bar and the
     * letterboxing fall outside), and loops: at the end it starts again
     */
    private static func page(_ id: String) -> String {
        """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          html,body{margin:0;height:100%;background:transparent;overflow:hidden}
          #p{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);
             width:max(118vw,210vh);height:max(66vw,118vh);border:0;pointer-events:none}
        </style></head>
        <body><div id="p"></div>
        <script>
          var player;
          function say(s){ try{ webkit.messageHandlers.trailer.postMessage(s) }catch(e){} }
          function onYouTubeIframeAPIReady(){
            player = new YT.Player('p', {
              host: 'https://www.youtube-nocookie.com', videoId: '\(id)',
              playerVars: {autoplay:1, mute:1, controls:0, disablekb:1, fs:0, iv_load_policy:3, modestbranding:1, playsinline:1, rel:0},
              events: {
                onReady: function(e){ e.target.mute(); e.target.playVideo() },
                onStateChange: function(e){
                  if (e.data === YT.PlayerState.PLAYING) say('playing');
                  if (e.data === YT.PlayerState.PAUSED) say('paused');
                  if (e.data === YT.PlayerState.ENDED) { e.target.seekTo(0); e.target.playVideo() }
                },
                onError: function(){ say('error') }
              }
            });
          }
        </script>
        <script src="https://www.youtube.com/iframe_api"></script>
        </body></html>
        """
    }
}

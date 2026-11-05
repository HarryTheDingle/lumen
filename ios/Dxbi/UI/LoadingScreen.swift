import SwiftUI

/** What's on the loading screen: the title, the step, what it found, how far along (0 to 1) */
struct LaunchScreen {
    var title: Title
    /** "S2, E5 · The Name", for an episode */
    var episode: String?
    var text: String
    var detail: String?
    var progress: Double
    var failed = false
}

/**
 * Play, and a change of stream: the title's picture, darkened, with its logo, what's happening and a
 * bar, low on the left as on Apple TV's pages (and Lumen's on Android TV). It stays until the player's up.
 */
struct LoadingScreen: View {
    @Environment(Stremio.self) private var stremio
    var screen: LaunchScreen

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottomLeading) {
                Color.black
                Art(url: screen.title.backdrop)
                    .frame(width: g.size.width, height: g.size.height)
                    .clipped()
                LinearGradient(colors: [.black.opacity(0.8), .black.opacity(0.35), .clear], startPoint: g.size.width > 600 ? .leading : .bottom, endPoint: g.size.width > 600 ? .trailing : .top)
                LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .init(x: 0.5, y: 0.35), endPoint: .bottom)
                VStack(alignment: .leading, spacing: 8) {
                    TitleLogo(title: screen.title, maxHeight: 96, textSize: 38, alignment: .leading)
                        .frame(maxWidth: 360)
                        .padding(.bottom, 6)
                    if let episode = screen.episode {
                        Text(episode).font(.subheadline.weight(.medium)).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                    }
                    Text(screen.text).font(.headline).padding(.top, 14)
                    if let detail = screen.detail, !detail.isEmpty {
                        Text(detail).font(.subheadline).foregroundStyle(Dx.muted).lineLimit(2)
                    }
                    Capsule().fill(.white.opacity(0.22))
                        .frame(height: 5)
                        .overlay(alignment: .leading) {
                            GeometryReader { bar in
                                Capsule().fill(screen.failed ? Color(red: 1, green: 0.42, blue: 0.42) : .white)
                                    .frame(width: bar.size.width * min(max(screen.progress, 0), 1))
                            }
                        }
                        .padding(.top, 6)
                        .animation(.easeInOut(duration: 0.5), value: screen.progress)
                }
                .frame(maxWidth: 460, alignment: .leading)
                .padding(.horizontal, 28)
                .padding(.bottom, 64)
            }
            .overlay(alignment: .topTrailing) {
                Button { stremio.cancelLaunch() } label: {
                    Image(systemName: "xmark").font(.body.weight(.semibold)).frame(width: 24, height: 24)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .accessibilityLabel(screen.failed ? "Close" : "Cancel")
                .padding(.top, g.safeAreaInsets.top + 8)
                .padding(.trailing, 16)
            }
        }
        .ignoresSafeArea()
    }
}

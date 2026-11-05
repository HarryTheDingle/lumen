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
    /** Mobile data or Low Data Mode: streams to choose from, here on the loading screen (nothing starts by itself) */
    var choices: [StreamOption] = []
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
                let wide = g.size.width > g.size.height
                HStack(alignment: .bottom, spacing: 32) {
                    VStack(alignment: .leading, spacing: 8) {
                        TitleLogo(title: screen.title, maxHeight: g.size.height < 500 ? 64 : 96, textSize: g.size.height < 500 ? 30 : 38, alignment: .leading)
                            .frame(maxWidth: 360)
                            .padding(.bottom, 6)
                        if let episode = screen.episode {
                            Text(episode).font(.subheadline.weight(.medium)).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                        }
                        Text(screen.text).font(.headline).padding(.top, g.size.height < 500 ? 6 : 14)
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
                        // (down here by the thumb: at the top it sat by the status bar, hard to press)
                        Button { stremio.cancelLaunch() } label: {
                            Label(screen.failed ? "Close" : "Cancel", systemImage: "xmark")
                                .font(.body.weight(.semibold))
                                .padding(.horizontal, 6)
                                .frame(height: 30)
                        }
                        .buttonStyle(.glass)
                        .padding(.top, 18)
                        // (upright, the choices go under the rest)
                        if !wide && !screen.choices.isEmpty { choices.padding(.top, 10) }
                    }
                    .frame(maxWidth: 460, alignment: .leading)
                    Spacer(minLength: 0)
                    // (on its side, beside it on the right)
                    if wide && !screen.choices.isEmpty { choices.frame(maxWidth: 360) }
                }
                // (a phone on its side is short: less above the bottom edge, clear of the rounded corners)
                .padding(.horizontal, g.size.height < 500 ? 56 : 28)
                .padding(.bottom, g.size.height < 500 ? 28 : 64)
            }
        }
        .ignoresSafeArea()
    }

    /** Mobile data or Low Data Mode: a few streams to choose from (the pick, and the smallest of each quality), and all of them */
    private var choices: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(screen.choices) { o in
                Button { stremio.chooseLaunch(o) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text([o.quality, o.dv ? "Dolby Vision" : o.hdr ? "HDR" : nil, o.imax ? "IMAX" : nil].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline.weight(.semibold))
                        Text([o.sizeText, o.provider.isEmpty ? nil : o.provider].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(Dx.muted).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 16))
            }
            Button { stremio.moreLaunchChoices() } label: {
                Label("All Streams", systemImage: "list.bullet").font(.subheadline.weight(.semibold)).padding(.horizontal, 6).frame(height: 30)
            }
            .buttonStyle(.glass)
        }
    }
}

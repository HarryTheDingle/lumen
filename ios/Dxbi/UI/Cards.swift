import SwiftUI

/** A title in a row: its poster, and (a Top 10) a frosted number in the corner, as on Apple TV */
struct PosterCard: View {
    @Environment(Stremio.self) private var stremio
    var title: Title
    var width: CGFloat = 150
    var rank: Int?
    var progress: Double?

    private var corner: CGFloat { min(18, width * 0.12) }

    var body: some View {
        ZStack(alignment: .bottom) {
            // (the poster names the title itself; an anime's (Kitsu's) often doesn't, in a language you can read:
            // so its name goes on it in text)
            Art(url: title.art)
                .frame(width: width, height: width * 1.5)
            if title.id.hasPrefix("kitsu:") || title.id.hasPrefix("mal:") {
                LinearGradient(colors: [.clear, .black.opacity(0.78)], startPoint: .center, endPoint: .bottom)
                    .frame(width: width, height: width * 1.5)
                Text(title.name)
                    .font(.system(size: max(11, width * 0.1), weight: .bold))
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)
                    .minimumScaleFactor(0.8)
                    .shadow(color: .black.opacity(0.6), radius: 3)
                    .frame(width: width - 16, alignment: .leading)
                    .padding(.bottom, progress ?? 0 > 0 ? 24 : 9)
            }
            if let progress, progress > 0 {
                ProgressLine(value: progress, colour: Dx.accent).padding(.horizontal, 10).padding(.bottom, 10)
            }
        }
        .frame(width: width, height: width * 1.5)
        .overlay(alignment: .topLeading) {
            if let rank {
                // frosted glass in the number's shape: the poster shows through, blurred and lightened
                let number = Text("\(rank)").font(.system(size: width * 0.22, weight: .bold))
                number.hidden()
                    .overlay {
                        ZStack {
                            Rectangle().fill(.ultraThinMaterial)
                            Color.white.opacity(0.55)
                        }
                        .mask(number)
                    }
                    .shadow(color: .black.opacity(0.35), radius: 4, y: 1)
                    .padding(.leading, width * 0.09)
                    .padding(.top, width * 0.04)
            }
        }
        .overlay(alignment: .topTrailing) {
            if stremio.isWatched(title) {
                Image(systemName: "checkmark.circle.fill").font(width < 130 ? .body : .title3).symbolRenderingMode(.palette).foregroundStyle(.black, .white).padding(width < 130 ? 6 : 8)
            }
        }
        .clipShape(.rect(cornerRadius: corner))
        // a glass rim, catching the light at the top
        .overlay(RoundedRectangle(cornerRadius: corner).strokeBorder(LinearGradient(colors: [.white.opacity(0.32), .white.opacity(0.06), .white.opacity(0.14)], startPoint: .top, endPoint: .bottom), lineWidth: 1))
        .contentShape(.rect(cornerRadius: corner))
        .contentShape(.contextMenuPreview, .rect(cornerRadius: corner))
    }
}

/** Continue Watching: wide art with the logo, and a glass strip with how far they got */
struct ContinueCard: View {
    @Environment(Stremio.self) private var stremio
    var item: Continue
    var width: CGFloat

    var body: some View {
        let title = stremio.filledIn(item.title)
        ZStack(alignment: .bottom) {
            Art(url: title.backdrop).frame(width: width, height: width * 0.62)
            BlurFade(url: title.backdrop, from: 0.55).frame(width: width, height: width * 0.62)
            TitleLogo(title: title, maxHeight: width * 0.2, textSize: 22)
                .frame(maxWidth: width * 0.7)
                .frame(maxHeight: .infinity)
                .padding(.bottom, 40)
            HStack(spacing: 10) {
                if item.progress > 0 { ProgressLine(value: item.progress, colour: Dx.accent).frame(width: 64) }
                Text([episodeLabel(item.videoId), timeLeft(item), item.newEpisodes > 0 ? "\(item.newEpisodes) new" : nil].compactMap { $0 }.joined(separator: " · ").ifEmpty(typeName(title.type)))
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
        }
        .frame(width: width, height: width * 0.62)
        .clipShape(.rect(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(.white.opacity(0.12)))
        .contentShape(.rect(cornerRadius: 22))
    }
}

/** New episodes this week: the episode's picture, the show and when */
struct AiringCard: View {
    var item: Airing
    var width: CGFloat

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Art(url: URL(string: item.still ?? item.background ?? "")).frame(width: width, height: width * 0.56)
            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.out ? "NEW EPISODE" : item.countdown.uppercased())
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(item.out ? Dx.accent : Dx.muted)
                Text(item.name).font(.headline).lineLimit(1)
                Text("S\(item.season), E\(item.episode)\(item.more > 0 ? " +\(item.more)" : "") · \(item.whenText)")
                    .font(.footnote).foregroundStyle(Dx.muted).lineLimit(1)
            }
            .padding(14)
        }
        .frame(width: width, height: width * 0.56)
        .clipShape(.rect(cornerRadius: 20))
        .contentShape(.rect(cornerRadius: 20))
    }
}

/** An episode, as Apple TV shows them: a tall card, the picture above and the words on glass below */
struct EpisodeCard: View {
    var video: Video
    var width: CGFloat
    var fallbackArt: URL?
    var playing = false
    /** Blur unwatched episodes (Lumen settings): the picture hidden until it's watched or started */
    var blurUnwatched = false

    private var hidden: Bool { blurUnwatched && !video.watched && (video.progress ?? 0) <= 0 }

    var body: some View {
        ZStack(alignment: .bottom) {
            EpisodeArt(url: URL(string: video.thumbnail ?? ""), fallback: fallbackArt)
                .frame(width: width, height: width * 0.92)
            .blur(radius: hidden ? 22 : 0, opaque: true)
            VStack(alignment: .leading, spacing: 4) {
                Text(video.episode.map { "EPISODE \($0)" } ?? "SPECIAL")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Dx.muted)
                Text(video.title.ifEmpty("Episode \(video.episode ?? 0)"))
                    .font(.headline)
                    .lineLimit(1)
                if let overview = video.overview {
                    Text(overview).font(.subheadline).foregroundStyle(Dx.muted).lineLimit(3)
                }
                HStack(spacing: 8) {
                    if video.upcoming, let date = video.releaseDate {
                        Text(date, format: .dateTime.day().month(.abbreviated)).font(.footnote.weight(.semibold))
                    } else if video.watched {
                        Label("Watched", systemImage: "checkmark").font(.footnote.weight(.semibold))
                    } else if let p = video.progress, p > 0 {
                        ProgressLine(value: p, colour: Dx.accent).frame(width: 60)
                    } else if let date = video.releaseDate {
                        Text(date, format: .dateTime.year().month(.abbreviated).day()).font(.footnote).foregroundStyle(Dx.muted)
                    }
                    Spacer(minLength: 0)
                    if playing { Image(systemName: "play.circle.fill").font(.title3).foregroundStyle(.white) }
                }
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .frame(width: width, height: width * 0.92)
        .clipShape(.rect(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(.white.opacity(playing ? 0.6 : 0.1), lineWidth: playing ? 2 : 1))
        .opacity(video.upcoming ? 0.6 : 1)
        .contentShape(.rect(cornerRadius: 22))
    }
}

/** An episode's picture and the blurred foot under its words; the title's own art when the episode's can't be had */
private struct EpisodeArt: View {
    var url: URL?
    var fallback: URL?
    @State private var missing = false

    var body: some View {
        let shown = missing ? fallback : (url ?? fallback)
        ZStack {
            Art(url: shown)
            BlurFade(url: shown, from: 0.36)
        }
        .task(id: url) {
            missing = false
            guard let url else { return }
            if case .missing = await Pictures.load(url) { missing = true }
        }
    }
}

extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}

/**
 * The bottom of an Apple TV card: the card's own picture, more and more blurred toward the bottom,
 * coming in from clear (no line where the glass starts), with a little shade for the words on it
 */
struct BlurFade: View {
    var url: URL?
    /** Where the blur starts, from the top (0…1) */
    var from: CGFloat

    var body: some View {
        Art(url: url)
            .blur(radius: 26, opaque: true)
            .mask(LinearGradient(stops: [.init(color: .clear, location: from), .init(color: .black, location: min(from + 0.25, 1))], startPoint: .top, endPoint: .bottom))
            .overlay(LinearGradient(stops: [.init(color: .clear, location: from), .init(color: .black.opacity(0.4), location: 1)], startPoint: .top, endPoint: .bottom))
            .allowsHitTesting(false)
    }
}

/** "28m left", "1h 12m left": from the title's length and how far in it is (nothing when the length isn't known) */
func timeLeft(_ item: Continue) -> String? {
    guard item.progress > 0.02, item.progress < 0.98, let runtime = item.title.runtime else { return nil }
    let h = runtime.firstMatch(of: /(\d+)\s*h/).flatMap { Int($0.1) } ?? 0
    let m = runtime.firstMatch(of: /(\d+)\s*m/).flatMap { Int($0.1) } ?? (h == 0 ? Int(runtime.prefix { $0.isNumber }) ?? 0 : 0)
    let total = h * 60 + m
    guard total > 0 else { return nil }
    let left = Int((Double(total) * (1 - item.progress)).rounded())
    guard left > 0 else { return nil }
    return left >= 60 ? "\(left / 60)h \(left % 60)m left" : "\(left)m left"
}

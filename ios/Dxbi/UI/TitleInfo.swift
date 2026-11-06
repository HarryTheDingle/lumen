import SwiftUI

/**
 * The words and buttons over a title's art, Apple TV's: the logo, "TV Show · Drama · Sci-Fi", the story,
 * then Play and +. Home's billboard shows the short one; a title's page the detailed one (the episode Play
 * would start, how long it is and what the best stream offers). Left-aligned, white, nothing coloured.
 */
struct TitleInfo<Facts: View, Buttons: View>: View {
    var title: Title
    var detailed = false
    var logoHeight: CGFloat = 110
    /** Billboard: one line of what's new ("New Episode Every Friday"), when there is something to say */
    var status: String?
    /** Detailed, a series: the episode Play starts ("S4, E3 · The Bear Hug") and its story */
    var episodeLabel: String?
    var episodeText: String?
    /** Detailed: the story's "MORE" opens it in full */
    @Binding var more: Bool
    /** Detailed: the year and length, then what the best stream offers (4K, Dolby Vision…) */
    var formats: [String] = []
    @ViewBuilder var facts: Facts
    @ViewBuilder var buttons: Buttons

    var body: some View {
        VStack(alignment: .leading, spacing: detailed ? 12 : 10) {
            Group {
                TitleLogo(title: title, maxHeight: logoHeight, textSize: detailed ? 38 : 40, alignment: .leading)
                    .frame(maxWidth: detailed ? 380 : 320, alignment: .leading)
                    .padding(.bottom, detailed ? 6 : 2)
                kindLine
                story
                if detailed { factsLine }
                if !detailed, let status {
                    Text(status).font(.footnote.weight(.semibold)).lineLimit(1)
                }
            }
            // (the billboard's words open the title; the page's own MORE is a button)
            .allowsHitTesting(detailed)
            buttons.padding(.top, 6)
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.35), radius: 8)
    }

    /** "TV Show · Drama · Sci-Fi" */
    private var kindLine: some View {
        Text(([title.type == "movie" ? "Film" : typeName(title.type)] + title.genres.prefix(2)).joined(separator: " · "))
            .font(.subheadline.weight(.medium))
            .foregroundStyle(.white.opacity(0.85))
            .lineLimit(1)
    }

    @ViewBuilder
    private var story: some View {
        if !detailed {
            if let d = title.description {
                Text(d).font(.footnote).foregroundStyle(Dx.muted).lineLimit(2).multilineTextAlignment(.leading)
            }
        } else if episodeLabel != nil || (episodeText ?? title.description) != nil {
            let text = episodeText ?? title.description ?? ""
            let words: Text = {
                var t = Text("")
                if let label = episodeLabel { t = t + Text(label + (text.isEmpty ? "" : ": ")).fontWeight(.semibold) }
                return t + Text(text).foregroundStyle(.white.opacity(0.78))
            }()
            let long = (episodeLabel?.count ?? 0) + text.count > 130
            Button { withAnimation(.snappy) { more.toggle() } } label: {
                words
                    .lineLimit(more ? nil : 3)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .bottomTrailing) {
                        if long && !more {
                            Text("MORE")
                                .font(.caption2.weight(.bold))
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(.ultraThinMaterial, in: .capsule)
                                .background(.black.opacity(0.4), in: .capsule)
                        }
                    }
            }
            .buttonStyle(.plain)
            .font(.subheadline)
        }
    }

    /** "2026 · 47 min", the scores, then small outlined badges: 4K, Dolby Vision, Dolby Atmos… */
    private var factsLine: some View {
        HStack(spacing: 10) {
            Text([title.year, title.runtime].compactMap { $0 }.joined(separator: " · "))
            facts
            ForEach(formats, id: \.self) { f in
                Text(f)
                    .font(.system(size: 11, weight: .bold))
                    .padding(.horizontal, 5).padding(.vertical, 1.5)
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(.white.opacity(0.6), lineWidth: 1))
            }
        }
        .font(.subheadline)
        .foregroundStyle(.white.opacity(0.85))
        .lineLimit(1)
    }
}

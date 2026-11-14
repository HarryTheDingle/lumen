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
    /** A small frosted tag over the logo, as Apple TV's "New": "Watched" */
    var tag: String? = nil
    @ViewBuilder var facts: Facts
    @ViewBuilder var buttons: Buttons

    var body: some View {
        VStack(alignment: .leading, spacing: detailed ? 12 : 10) {
            Group {
                if let tag {
                    Text(tag)
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 7).padding(.vertical, 2.5)
                        .background(.ultraThinMaterial, in: .rect(cornerRadius: 6))
                        .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(.white.opacity(0.3), lineWidth: 1))
                }
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

    /** Apple TV's order: "2026 · 2 hr 6 min", what the best stream offers (frosted badges: 4K, Dolby Vision…), then the scores */
    private var factsLine: some View {
        HStack(spacing: 10) {
            Text([title.year, runtimeText(title.runtime)].compactMap { $0 }.joined(separator: " · "))
            ForEach(formats, id: \.self) { f in
                Text(f)
                    .font(.system(size: 11, weight: .bold))
                    .padding(.horizontal, 5).padding(.vertical, 1.5)
                    .background(.white.opacity(0.12), in: .rect(cornerRadius: 4))
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(.white.opacity(0.35), lineWidth: 1))
            }
            facts.padding(.leading, formats.isEmpty ? 0 : 4)
        }
        .font(.subheadline)
        .foregroundStyle(.white.opacity(0.85))
        .lineLimit(1)
    }
}

/** "126 min" → "2 hr 6 min", as Apple TV says it (anything else as it was) */
func runtimeText(_ runtime: String?) -> String? {
    guard let runtime else { return nil }
    guard let m = runtime.firstMatch(of: /^\s*(\d+)\s*min/).flatMap({ Int($0.1) }) else { return runtime }
    if m < 60 { return "\(m) min" }
    return m % 60 == 0 ? "\(m / 60) hr" : "\(m / 60) hr \(m % 60) min"
}

/**
 * A score's own mark, drawn (no emoji), as on Lumen TV: Rotten Tomatoes' tomato (a green splat under 60%), the audience's
 * popcorn, IMDb's yellow badge, Metacritic's coloured square
 */
struct ScoreMark: View {
    var source: String
    var value: String
    var size: CGFloat = 14
    private var pct: Double? { Double(value.filter { $0.isNumber || $0 == "." }) }

    var body: some View {
        switch source {
        case "tomatoes":
            if let p = pct, p < 60 {
                ZStack {
                    Circle().fill(Color(red: 0.06, green: 0.64, blue: 0.29)).frame(width: size * 0.72, height: size * 0.72)
                    ForEach(0..<7, id: \.self) { i in
                        let a = Double(i) * 2 * .pi / 7 + 0.17
                        Circle().fill(Color(red: 0.06, green: 0.64, blue: 0.29)).frame(width: size * 0.22, height: size * 0.22)
                            .offset(x: cos(a) * size * 0.4, y: sin(a) * size * 0.4)
                    }
                }.frame(width: size, height: size)
            } else {
                ZStack(alignment: .top) {
                    Circle().fill(Color(red: 0.98, green: 0.2, blue: 0.04)).frame(width: size * 0.88, height: size * 0.88).offset(y: size * 0.1)
                    HStack(spacing: 0) {
                        ForEach(0..<3, id: \.self) { _ in Ellipse().fill(Color(red: 0, green: 0.57, blue: 0.18)).frame(width: size * 0.18, height: size * 0.2) }
                    }
                }.frame(width: size, height: size)
            }
        case "popcorn":
            ZStack(alignment: .bottom) {
                HStack(spacing: -size * 0.08) {
                    ForEach(0..<3, id: \.self) { _ in Circle().fill(Color(red: 1, green: 0.91, blue: 0.66)).frame(width: size * 0.32, height: size * 0.32) }
                }.offset(y: -size * 0.58)
                Rectangle().fill(Color(red: 0.98, green: 0.2, blue: 0.04)).frame(width: size * 0.62, height: size * 0.6)
                    .overlay(HStack(spacing: size * 0.1) { ForEach(0..<2, id: \.self) { _ in Rectangle().fill(.white).frame(width: size * 0.08) } })
            }.frame(width: size, height: size)
        case "imdb":
            Text("IMDb").font(.system(size: size * 0.62, weight: .black)).foregroundStyle(.black)
                .padding(.horizontal, 3).frame(height: size)
                .background(Color(red: 0.96, green: 0.77, blue: 0.09), in: .rect(cornerRadius: 3))
        case "metacritic":
            Text("M").font(.system(size: size * 0.62, weight: .black)).foregroundStyle(.black)
                .frame(width: size, height: size)
                .background(pct.map { $0 >= 61 ? Color(red: 0, green: 0.81, blue: 0.48) : $0 >= 40 ? Color(red: 1, green: 0.74, blue: 0.25) : Color(red: 1, green: 0.41, blue: 0.45) } ?? .gray, in: .rect(cornerRadius: 3))
        default:
            Text(source.prefix(4)).font(.system(size: size * 0.7, weight: .bold)).foregroundStyle(.white.opacity(0.8))
        }
    }
}

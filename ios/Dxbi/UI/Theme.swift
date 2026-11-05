import CoreImage
import SwiftUI
import UIKit

enum Dx {
    /** Lumen's cyan, from the icon: what's chosen, small highlights */
    static let accent = Color(red: 0.180, green: 0.902, blue: 0.839)
    /** The icon's light, cyan through blue to amber: the logo and progress */
    static let spectrum = LinearGradient(
        colors: [accent, Color(red: 0.184, green: 0.545, blue: 1.0), Color(red: 1.0, green: 0.710, blue: 0.278)],
        startPoint: .leading, endPoint: .trailing)
    /** The icon's blue: progress (watched so far, downloads) */
    static let blue = Color(red: 0.184, green: 0.545, blue: 1.0)
    /** Text on the accent (it's light) */
    static let onAccent = Color(red: 0.024, green: 0.078, blue: 0.102)
    static let background = Color.black
    static let raised = Color(white: 0.12)
    static let muted = Color.white.opacity(0.62)
    static let faint = Color.white.opacity(0.4)
    static let gutter: CGFloat = 20
    /** iPad: posters a little bigger, text no wider than reads well */
    static let pad = UIDevice.current.userInterfaceIdiom == .pad
    /** A row's posters: on the iPhone three and a bit across, as Apple TV's */
    static let poster: CGFloat = pad ? 180 : 112
    static let readable: CGFloat = 720
}

/** LUMEN in Bebas Neue, in the icon's colours, as on the desktop */
struct Logo: View {
    var size: CGFloat = 30
    var body: some View {
        Text("LUMEN")
            .font(.custom("BebasNeue-Regular", size: size))
            .tracking(size * 0.08)
            .foregroundStyle(Dx.spectrum)
            .shadow(color: Dx.accent.opacity(0.35), radius: size / 5)
            .accessibilityLabel("Lumen")
    }
}

// MARK: - Pictures

/**
 * Pictures from the web, kept in memory (and on disk by URLCache). A load carries on when the card that
 * asked scrolls away, so it's there when the card comes back; one that fails is tried twice more.
 * (SwiftUI's AsyncImage gave up for good on a load cut short in a scrolling row: a blank poster
 * until the page was opened again. metahub's newest posters can take seconds to be made.)
 */
@MainActor
enum Pictures {
    enum Result { case image(UIImage), missing }
    private static let memory: NSCache<NSURL, UIImage> = {
        let c = NSCache<NSURL, UIImage>()
        c.totalCostLimit = 120 * 1024 * 1024
        return c
    }()
    private static var missing = Set<URL>()
    private static var loading: [URL: Task<UIImage?, Never>] = [:]

    static func cached(_ url: URL?) -> Result? {
        guard let url else { return .missing }
        if let image = memory.object(forKey: url as NSURL) { return .image(image) }
        return missing.contains(url) ? .missing : nil
    }

    static func load(_ url: URL) async -> Result {
        if let known = cached(url) { return known }
        let task = loading[url] ?? Task.detached(priority: .utility) {
            for attempt in 0..<3 {
                if attempt > 0 { try? await Task.sleep(for: .seconds(attempt == 1 ? 1.5 : 4)) }
                guard let (data, response) = try? await URLSession.shared.data(from: url) else { continue }
                // (not there: no use asking again)
                if let http = response as? HTTPURLResponse, http.statusCode == 404 { return nil }
                if let image = UIImage(data: data)?.preparingForDisplay() ?? UIImage(data: data) { return image }
            }
            return nil
        }
        loading[url] = task
        let image = await task.value
        loading[url] = nil
        if let image {
            memory.setObject(image, forKey: url as NSURL, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
            return .image(image)
        }
        missing.insert(url)
        return .missing
    }

    private static var darkness: [URL: Bool] = [:]

    /** Mostly dark (its visible pixels): a logo drawn in black, lost on a dark page */
    static func isDark(_ url: URL?) -> Bool {
        guard let url, let image = memory.object(forKey: url as NSURL)?.cgImage else { return false }
        if let known = darkness[url] { return known }
        let side = 24
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return false }
        var light = 0.0, weight = 0.0
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let a = Double(pixels[i + 3]) / 255
            guard a > 0.2 else { continue }
            // (premultiplied: back to the colour itself)
            let r = Double(pixels[i]) / 255 / a, g = Double(pixels[i + 1]) / 255 / a, b = Double(pixels[i + 2]) / 255 / a
            light += (0.299 * r + 0.587 * g + 0.114 * b) * a
            weight += a
        }
        let dark = weight > 0 && light / weight < 0.3
        darkness[url] = dark
        return dark
    }
}

/** A picture from the web: [content] once it's in, [placeholder] while it loads or when there's none */
struct WebImage<Content: View, Placeholder: View>: View {
    var url: URL?
    @ViewBuilder var content: (Image) -> Content
    @ViewBuilder var placeholder: (_ missing: Bool) -> Placeholder
    @State private var result: Pictures.Result?

    var body: some View {
        let shown = result ?? Pictures.cached(url)
        ZStack {
            switch shown {
            case .image(let image): content(Image(uiImage: image)).transition(.opacity)
            case .missing: placeholder(true)
            case nil: placeholder(false)
            }
        }
        .task(id: url) {
            result = Pictures.cached(url)
            guard result == nil, let url else { return }
            let loaded = await Pictures.load(url)
            withAnimation(.easeOut(duration: 0.25)) { result = loaded }
        }
    }
}

/** A picture from the web, filling its frame (or fitting) */
struct Art: View {
    var url: URL?
    var contentMode: ContentMode = .fill

    var body: some View {
        WebImage(url: url) { image in
            image.resizable().aspectRatio(contentMode: contentMode)
        } placeholder: { _ in
            Rectangle().fill(Dx.raised)
        }
    }
}

/**
 * A title's logo (its name drawn as on the poster), or its name in large type when it has none. A logo
 * drawn in black is shown in white, as Apple TV does: it's always over dark art
 */
struct TitleLogo: View {
    var title: Title
    var maxHeight: CGFloat = 100
    var textSize: CGFloat = 34
    var alignment: Alignment = .center

    var body: some View {
        WebImage(url: title.logoURL) { image in
            image.renderingMode(Pictures.isDark(title.logoURL) ? .template : .original)
                .resizable().scaledToFit().frame(maxHeight: maxHeight, alignment: alignment)
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.5), radius: 12)
                .frame(maxWidth: .infinity, alignment: alignment)
        } placeholder: { missing in
            if !missing {
                Color.clear.frame(height: maxHeight * 0.6)
            } else {
                Text(title.name)
                    .font(.system(size: textSize, weight: .heavy))
                    .multilineTextAlignment(alignment == .leading ? .leading : .center)
                    .lineLimit(3)
                    .minimumScaleFactor(0.6)
                    .shadow(color: .black.opacity(0.5), radius: 10)
            }
        }
    }
}

/** The main colour of a picture, darkened to sit behind white text (a title's page takes its art's colour) */
@MainActor
enum ArtColour {
    private static var known: [URL: Color] = [:]
    private static let context = CIContext(options: [.workingColorSpace: NSNull()])

    static func of(_ url: URL?) async -> Color? {
        guard let url else { return nil }
        if let c = known[url] { return c }
        guard let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }
        let colour: Color? = await Task.detached(priority: .utility) {
            guard let image = CIImage(data: data),
                  let filter = CIFilter(name: "CIAreaAverage", parameters: [kCIInputImageKey: image, kCIInputExtentKey: CIVector(cgRect: image.extent)]),
                  let output = filter.outputImage else { return nil }
            var pixel = [UInt8](repeating: 0, count: 4)
            CIContext(options: [.workingColorSpace: NSNull()]).render(output, toBitmap: &pixel, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1), format: .RGBA8, colorSpace: nil)
            var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            UIColor(red: CGFloat(pixel[0]) / 255, green: CGFloat(pixel[1]) / 255, blue: CGFloat(pixel[2]) / 255, alpha: 1).getHue(&h, saturation: &s, brightness: &b, alpha: &a)
            // dark enough for white text, a little colour kept
            return Color(hue: h, saturation: min(s, 0.55), brightness: min(max(b * 0.62, 0.16), 0.42))
        }.value
        if let colour { known[url] = colour }
        return colour
    }
}

// MARK: - Buttons

/** Apple TV's white Play pill */
struct PrimaryPill: View {
    var label: String
    var icon: String = "play.fill"
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Label(label, systemImage: icon)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.glassProminent)
        .tint(.white)
        .foregroundStyle(.black)
        .buttonBorderShape(.capsule)
    }
}

/** A round glass button (+, ✓, ⋯) */
struct GlassCircle: View {
    var icon: String
    var label: String
    var size: CGFloat = 50
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size * 0.4, weight: .semibold))
                .frame(width: size - 14, height: size - 14)
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }
}

/** "Continue Watching ›": a row's title, which opens all of it */
struct SectionHeader: View {
    var title: String
    var action: (() -> Void)?
    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: 4) {
                Text(title).font(.title2.bold()).lineLimit(1)
                if action != nil { Image(systemName: "chevron.right").font(.title3.weight(.bold)).foregroundStyle(Dx.muted) }
            }
            .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        // (not .disabled: that greys a heading that opens nothing)
        .allowsHitTesting(action != nil)
        .padding(.horizontal, Dx.gutter)
    }
}

/** A small outlined tag (New, 4K, HDR…) */
struct Badge: View {
    var text: String
    var filled = false
    var colour: Color = .white
    var body: some View {
        Text(text)
            .font(.caption.weight(.bold))
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .foregroundStyle(filled ? .black : colour)
            .background(filled ? AnyShapeStyle(colour) : AnyShapeStyle(.clear), in: .rect(cornerRadius: 5))
            .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(filled ? .clear : colour.opacity(0.7), lineWidth: 1.2))
    }
}

/**
 * A progress line. In the accent it's the icon's blue, a little thicker, with a soft glow, over a track
 * that shows on light art too; and a little progress still shows (a dot, not a sliver)
 */
struct ProgressLine: View {
    var value: Double
    var colour: Color = .white
    var body: some View {
        let accent = colour == Dx.accent
        let height: CGFloat = accent ? 5 : 4
        let fill = accent ? Dx.blue : colour
        GeometryReader { g in
            Capsule().fill(.white.opacity(accent ? 0.28 : 0.3))
                .overlay(Capsule().strokeBorder(.black.opacity(accent ? 0.25 : 0), lineWidth: 0.5))
                .overlay(alignment: .leading) {
                    let p = min(max(value, 0), 1)
                    Capsule().fill(fill)
                        .frame(width: p > 0 ? max(g.size.width * p, height) : 0)
                        .shadow(color: accent ? fill.opacity(0.7) : .clear, radius: 4)
                }
        }
        .frame(height: height)
    }
}

extension View {
    /** The page's black, edge to edge */
    func screenBackground() -> some View { background(Dx.background.ignoresSafeArea()) }
}

/** "S1, E3" from Stremio's video id (tt0000:1:3) */
func episodeLabel(_ videoId: String?) -> String? {
    guard let parts = videoId?.split(separator: ":"), parts.count >= 3, let s = Int(parts[parts.count - 2]), let e = Int(parts[parts.count - 1]) else { return nil }
    return "S\(s), E\(e)"
}

func typeName(_ type: String) -> String {
    switch type {
    case "movie": "Movie"
    case "series": "TV Show"
    case "anime": "Anime"
    case "channel": "Channel"
    case "tv": "Live TV"
    default: type.capitalized
    }
}

extension Color {
    /** Stremio's colour settings: #RRGGBB or #RRGGBBAA. nil when missing, or fully see-through */
    init?(hex: String?) {
        guard var s = hex?.trimmingCharacters(in: .whitespaces), s.hasPrefix("#") else { return nil }
        s.removeFirst()
        guard s.count == 6 || s.count == 8, let v = UInt64(s, radix: 16) else { return nil }
        let alpha = s.count == 8 ? Double(v & 0xff) / 255 : 1
        guard alpha > 0 else { return nil }
        let rgb = s.count == 8 ? v >> 8 : v
        self.init(.sRGB, red: Double((rgb >> 16) & 0xff) / 255, green: Double((rgb >> 8) & 0xff) / 255, blue: Double(rgb & 0xff) / 255, opacity: alpha)
    }
}

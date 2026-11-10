import SwiftUI

/**
 * Someone from a title's cast, the Apple TV way: their photo and name, who they are (where and when they
 * were born), a short biography that opens out, then what they're known for and all their films and
 * shows, each a poster that opens its page. The data is TMDB's (a key in Lumen settings); without one
 * the cast row searches for the name instead and this page isn't reached.
 *
 * A director or writer from Discover ([crew]) has the same page with their work behind the camera: what
 * they directed and what they wrote, newest first. Without a key it shows the films the app's own list
 * has for them.
 */
struct PersonView: View {
    @Environment(Stremio.self) private var stremio
    var id: String
    var name: String
    var photo: String?
    /** Opened as a filmmaker (Discover's Directors & Writers): directing and writing, not acting */
    var crew = false

    @State private var person: JSON?
    @State private var loaded = false
    @State private var moreText = false

    /** The app's own entry for a filmmaker: their films when there's no TMDB key */
    private var listed: Filmmaker? { crew ? Listed.filmmaker(id: id, name: name) : nil }

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    header(wide: geo.size.width > 600)
                    if let person {
                        shelf("Known For", person.objects("known"))
                        if crew {
                            shelf("Directed", person.objects("directed"))
                            shelf("Written", person.objects("written"))
                        } else {
                            shelf("Films", person.objects("films"))
                        }
                        shelf("TV Shows", person.objects("shows"))
                    } else if loaded, let listed {
                        // (no TMDB key: the list's films, newest first)
                        shelf("Films", listed.films.sorted { $0.year > $1.year }.map { $0.title("movie") })
                        shelf("TV Shows", listed.series.map { $0.title("series") })
                        Text("A selection of their work. With a TMDB key in Lumen settings, this page has all of it.")
                            .font(.footnote).foregroundStyle(Dx.faint)
                            .padding(.horizontal, Dx.gutter)
                    } else if loaded {
                        VStack(spacing: 12) {
                            Text("Nothing to show for \(name) right now.").foregroundStyle(Dx.muted)
                            Button("Search for \(name)") { stremio.searchFor(name) }.buttonStyle(.bordered)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 20)
                    } else {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 60)
            }
        }
        .background(Color.black.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id + name) {
            // (a filmmaker the list has no TMDB id for is found by the name TMDB knows them by)
            person = crew ? await stremio.filmmaker(id: id, name: listed?.person ?? name) : await stremio.person(id: id, name: name)
            loaded = true
        }
    }

    // MARK: - Header

    private func header(wide: Bool) -> some View {
        let size: CGFloat = wide ? 220 : 128
        // (on a phone the biography goes under the photo: beside it, it was a narrow column)
        return VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: wide ? .top : .center, spacing: wide ? 32 : 16) {
                ZStack {
                    Circle().fill(Color(white: 0.3))
                    Text(name.split(separator: " ").compactMap(\.first).prefix(2).map(String.init).joined())
                        .font(.system(size: size * 0.36, weight: .semibold))
                    if let url = URL(string: person?.text("photo") ?? photo ?? "") {
                        WebImage(url: url) { $0.resizable().scaledToFill() } placeholder: { _ in Color.clear }
                    }
                }
                .frame(width: size, height: size)
                .clipShape(.circle)
                VStack(alignment: .leading, spacing: 10) {
                    // (a filmmaker keeps the name they were opened by: the Coen Brothers are Joel Coen at TMDB)
                    Text(crew ? name : person?.text("name") ?? name).font(wide ? .largeTitle.bold() : .title.bold())
                    ForEach(facts, id: \.self) { Text($0).font(.subheadline).foregroundStyle(Dx.muted) }
                    if wide { bio(wide: true) }
                }
                .frame(maxWidth: Dx.readable, alignment: .leading)
                Spacer(minLength: 0)
            }
            if !wide { bio(wide: false) }
        }
        .padding(.horizontal, Dx.gutter)
    }

    @ViewBuilder
    private func bio(wide: Bool) -> some View {
        if let bio = person?.text("bio") {
            Button { withAnimation(.snappy) { moreText.toggle() } } label: {
                Text(bio)
                    .font(.callout)
                    .lineLimit(moreText ? nil : (wide ? 6 : 4))
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .bottomTrailing) {
                        if !moreText && bio.count > (wide ? 420 : 200) {
                            Text("MORE")
                                .font(.caption.weight(.bold))
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(.ultraThinMaterial, in: .capsule)
                        }
                    }
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
        }
    }

    /** What they do, when and where they were born (and died) */
    private var facts: [String] {
        guard let p = person else { return loaded ? [listed?.role].compactMap { $0 } : [] }
        var out: [String] = []
        if let role = listed?.role {
            out.append(role)
        } else if let d = p.text("department") {
            out.append(["Acting": "Actor", "Directing": "Director", "Writing": "Writer"][d] ?? d)
        }
        if let born = date(p.text("born")) {
            let age = p.text("age").map { " (\($0))" } ?? ""
            if let died = date(p.text("died")) {
                out.append("Born \(born)" + (p.text("place").map { " in \($0)" } ?? ""))
                out.append("Died \(died)" + age)
            } else {
                out.append("Born \(born)\(age)" + (p.text("place").map { " in \($0)" } ?? ""))
            }
        } else if let place = p.text("place") {
            out.append("Born in \(place)")
        }
        return out
    }

    private func date(_ iso: String?) -> String? {
        guard let iso else { return nil }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        guard let d = f.date(from: iso) else { return nil }
        return d.formatted(.dateTime.day().month(.wide).year())
    }

    // MARK: - Rows

    private func shelf(_ name: String, _ items: [JSON]) -> some View {
        shelf(name, items.map { j -> Title in
            var t = Title(id: j.string("id"), type: j.string("type"), name: j.string("name"))
            t.poster = j.text("poster")
            t.year = j.text("year")
            return t
        })
    }

    @ViewBuilder
    private func shelf(_ name: String, _ titles: [Title]) -> some View {
        if !titles.isEmpty {
            Shelf(title: name) {
                ForEach(titles) { title in
                    NavigationLink(value: Route.title(title.ref)) {
                        VStack(alignment: .leading, spacing: 6) {
                            PosterCard(title: title, width: Dx.poster).zoomSource(title.id)
                            if let y = title.year, !y.isEmpty { Text(y).font(.caption).foregroundStyle(Dx.muted) }
                        }
                    }
                    .buttonStyle(.plain)
                    .titleMenu(title)
                }
            }
        }
    }
}

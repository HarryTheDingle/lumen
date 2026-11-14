import SwiftUI

/**
 * Settings → Networks: the speed Lumen has learnt on each connection (from what streams really got, so a
 * known one needs no speed test) and a quality cap for each. A slow reading isn't trusted until it's
 * been seen three times: until then it says "Checking".
 */
struct NetworksView: View {
    @Environment(Stremio.self) private var stremio
    @State private var networks: [NetworkInfo] = []
    @State private var loaded = false
    @State private var renaming: NetworkInfo?
    @State private var newName = ""

    var body: some View {
        List {
            if loaded && networks.isEmpty {
                ContentUnavailableView("No Networks Yet", systemImage: "wifi", description: Text("Play something, and the speed it really gets on this network is remembered here. Match my internet speed (Lumen Settings) uses it."))
            }
            ForEach(networks) { n in
                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(n.title).font(.headline)
                            Text(detail(n)).font(.footnote).foregroundStyle(Dx.muted)
                        }
                        Spacer()
                        if n.current { Text("Now").font(.caption.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 3).background(Dx.accent.opacity(0.25), in: .capsule) }
                    }
                    Picker("Highest quality here", selection: Binding(get: { n.cap }, set: { cap in update(n) { await stremio.setNetwork(n.id, cap: cap) } })) {
                        Text("Auto (its speed)").tag(0)
                        Text("4K").tag(2160)
                        Text("1080p").tag(1080)
                        Text("720p").tag(720)
                    }
                    Button("Rename") { newName = n.name; renaming = n }
                    Button("Measure Again") { update(n) { await stremio.setNetwork(n.id, relearn: true) } }
                    Button("Forget", role: .destructive) { update(n) { await stremio.forgetNetwork(n.id) } }
                }
            }
        }
        .navigationTitle("Networks")
        .task { await reload() }
        .refreshable { await reload() }
        .alert("Name This Network", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Home, Office…", text: $newName)
            Button("Save") { if let n = renaming { update(n) { await stremio.setNetwork(n.id, name: newName) } } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func detail(_ n: NetworkInfo) -> String {
        let speed = n.mbps > 0 ? "\(Int(n.mbps.rounded())) Mbps" : "Not measured yet"
        let state = n.trusted ? "from \(n.readings) reading\(n.readings == 1 ? "" : "s")" : "Checking: a slow reading is measured again"
        return "\(speed) · \(state)"
    }

    private func update(_ n: NetworkInfo, _ change: @escaping () async -> Void) {
        Task {
            await change()
            await reload()
        }
    }

    private func reload() async {
        networks = await stremio.networks()
        loaded = true
    }
}

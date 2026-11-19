import LumosEngine
import SwiftUI

/**
 * Signing in to Lumos, in Lumen's welcome screen's style: the first device sets Lumos up with the setup phrase;
 * the others show a code that a signed-in device approves (Settings → Lumos → Sign In a Device).
 */
struct LumosWelcome: View {
    @Environment(Stremio.self) private var stremio
    @State private var phrase = ""
    @State private var pending: PendingSignIn?
    @State private var settingUp = false
    @State private var busy = false
    @State private var problem: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Logo(size: 84).padding(.top, 80)
                if let pending {
                    Text("On a device that's signed in, open Profile → Lumos → Sign In a Device, and enter")
                        .multilineTextAlignment(.center).foregroundStyle(Dx.muted)
                    Text(pending.code).font(.system(size: 40, weight: .semibold, design: .monospaced)).tracking(4).padding(.vertical, 8)
                    ProgressView().tint(.white)
                    Button("Back") { self.pending = nil }.foregroundStyle(Dx.muted).padding(.top, 8)
                } else if settingUp {
                    Text("Set up Lumos with your setup phrase.\nYour profiles and progress sync from here.")
                        .multilineTextAlignment(.center).foregroundStyle(Dx.muted)
                    SecureField("Setup phrase", text: $phrase)
                        .textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(16).glassEffect(.regular, in: .rect(cornerRadius: 18)).padding(.top, 20)
                        .onSubmit { Task { await setUp() } }
                    PrimaryPill(label: busy ? "Setting Up…" : "Set Up", icon: "key.fill") { Task { await setUp() } }
                        .disabled(busy || phrase.count < 8)
                    Button("Back") { settingUp = false; problem = nil }.foregroundStyle(Dx.muted)
                } else {
                    Text("Sign in to Lumos.\nYour profiles, Continue Watching and My List come with you.")
                        .multilineTextAlignment(.center).foregroundStyle(Dx.muted)
                    PrimaryPill(label: "Sign In with a Code", icon: "qrcode") { Task { await startCode() } }.padding(.top, 20)
                    Button("Set Up Lumos on This Device") { settingUp = true }.foregroundStyle(.white)
                }
                if let problem { Text(problem).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center) }
            }
            .padding(.horizontal, 28)
            .frame(maxWidth: 460)
            .frame(maxWidth: .infinity)
        }
        .task(id: pending?.code) { await waitForApproval() }
    }

    private func setUp() async {
        busy = true
        problem = nil
        do { stremio.page.signedIn(try await stremio.page.core.createAccount(setup: phrase, deviceName: UIDevice.current.name)) }
        catch { problem = error.words }
        busy = false
    }

    private func startCode() async {
        problem = nil
        do { pending = try await stremio.page.core.startSignIn(deviceName: UIDevice.current.name) } catch { problem = error.words }
    }

    private func waitForApproval() async {
        guard let pending else { return }
        while !Task.isCancelled, self.pending?.code == pending.code {
            try? await Task.sleep(for: .seconds(3))
            do {
                if let session = try await stremio.page.core.pollSignIn(pending: pending) { stremio.page.signedIn(session); return }
            } catch {
                problem = error.words
                self.pending = nil
                return
            }
        }
    }
}

/** Profile → Lumos: TorBox, addons, signing in other devices, and bringing Lumen and Stremio across */
struct LumosSettingsView: View {
    @Environment(Stremio.self) private var stremio
    @State private var torBox = ""
    @State private var addonText = ""
    @State private var code = ""
    @State private var devices: [Device] = []
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var message: String?
    @State private var keepsStremio = false

    private var page: Page { stremio.page }

    var body: some View {
        List {
            Section {
                SecureField("TorBox API key", text: $torBox).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .onSubmit { page.torBoxKey = torBox }
            } header: { Text("TorBox") } footer: { Text("For playing torrents your addons find. Your key is at torbox.app/settings and stays on this device.") }

            Section {
                TextField("Addon links, one per line", text: $addonText, axis: .vertical).lineLimit(3...8)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().font(.footnote.monospaced())
                Button("Save Addons") {
                    page.addons = addonText.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                    message = "Addons saved."
                    Task { await stremio.loadHome() }
                }
            } header: { Text("Stream Addons") } footer: { Text("StremThru Torz (set up with your TorBox key), Easynews+ and others, by their manifest link. Cinemeta is always on.") }

            Section("Sign In a Device") {
                TextField("Code shown on the other device", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled()
                Button("Approve") { Task { await approve() } }.disabled(code.count < 8 || busy)
                ForEach(devices, id: \.id) { d in
                    HStack {
                        Text(d.name + (d.current ? " (this one)" : ""))
                        Spacer()
                        if !d.current {
                            Button("Sign Out", role: .destructive) { Task { try? await page.core.sync.signOut(device: d); await loadDevices() } }.font(.footnote)
                        }
                    }
                }
            }

            Section {
                TextField("Stremio email", text: $email).textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Stremio password", text: $password).textContentType(.password)
                Button("Bring My History and Addons Across") { Task { await importStremio() } }.disabled(email.isEmpty || password.isEmpty || busy)
                if keepsStremio {
                    Toggle("Keep Stremio in Step", isOn: Binding(get: { keepsStremio }, set: { if !$0 { page.keepStremio(nil); keepsStremio = false } }))
                }
            } header: { Text("From Lumen and Stremio") } footer: {
                Text("Your stream addons, Lumen profiles, Continue Watching, watched titles and library come into Lumos. While you still use Lumen, what you watch here goes to Stremio and what you watch in Lumen comes back. Your password isn't kept.")
            }

            if let message { Section { Text(message).font(.footnote) } }

            Section {
                Button("Sign Out of Lumos", role: .destructive) {
                    Keychain.set("token", nil)
                    Keychain.set("account", nil)
                    exit(0)
                }
            }
        }
        .navigationTitle("Lumos")
        .onAppear {
            torBox = page.torBoxKey
            addonText = page.addons.joined(separator: "\n")
            keepsStremio = page.core.keepsStremio
        }
        .onDisappear { if torBox != page.torBoxKey { page.torBoxKey = torBox } }
        .task { await loadDevices() }
    }

    private func loadDevices() async { devices = (try? await page.core.sync.devices()) ?? [] }

    private func approve() async {
        busy = true
        do {
            message = "\(try await page.core.approveSignIn(code: code)) is signed in."
            code = ""
            await loadDevices()
        } catch { message = error.words }
        busy = false
    }

    private func importStremio() async {
        busy = true
        message = "Bringing your history across…"
        do {
            let got = try await page.core.importStremio(email: email, password: password)
            password = ""
            page.keepStremio(got.stremioKey)
            keepsStremio = true
            let new = got.addons.filter { !page.addons.contains($0) && !$0.contains("v3-cinemeta") }
            if !new.isEmpty { page.addons += new; addonText = page.addons.joined(separator: "\n") }
            await page.syncNow()
            await stremio.loadHome()
            stremio.refreshExtras()
            message = "Done: \(got.titles) titles and \(new.count) stream addon\(new.count == 1 ? "" : "s") from Stremio."
        } catch { message = error.words }
        busy = false
    }
}

/** Errors from the engine, in words */
extension Error {
    var words: String {
        let ns = self as NSError
        return (ns.userInfo["KotlinException"] as? KotlinThrowable)?.message ?? ns.localizedDescription
    }
}

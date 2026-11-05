import SwiftUI

/** dx.bi's viewer colours (the plugin accepts only these) */
let profileColours = ["#e50914", "#2f80ff", "#1db954", "#f5a524", "#8b5cf6", "#ec4899", "#14b8a6", "#f97316"]

/** A profile's circle: its colour and first letter, as on Apple TV */
struct ProfileAvatar: View {
    var name: String
    var colour: String?
    var size: CGFloat = 96

    var body: some View {
        Text(String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
            .font(.system(size: size * 0.46, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [Color.profile(colour), Color.profile(colour).opacity(0.62)], startPoint: .topLeading, endPoint: .bottomTrailing), in: .circle)
            .overlay(Circle().strokeBorder(.white.opacity(0.2), lineWidth: 1))
    }
}

/**
 * Who's watching? A person each, with their own Continue Watching, watched marks and picks. Opens when the
 * app starts with more than one, and from Profile → Switch Profile. Manage adds, renames and removes them.
 */
struct ProfilePicker: View {
    @Environment(Stremio.self) private var stremio
    var onDone: () -> Void
    @State private var managing = false
    @State private var editing: Viewer?
    @State private var adding = false
    @State private var accountsSheet = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                if !stremio.viewers.isEmpty {
                    Button(managing ? "Done" : "Manage") { withAnimation(.snappy) { managing.toggle() } }
                        .font(.body.weight(.semibold))
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 14)
            Spacer(minLength: 0)
            Text("Who's watching?")
                .font(.system(size: Dx.pad ? 38 : 30, weight: .bold))
                .padding(.bottom, 34)
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100, maximum: 150), spacing: 22, alignment: .top)], spacing: 26) {
                    ForEach(stremio.viewers) { v in
                        Button {
                            if managing { editing = v } else {
                                stremio.chooseViewer(v.id)
                                onDone()
                            }
                        } label: {
                            VStack(spacing: 10) {
                                ProfileAvatar(name: v.name, colour: v.colour, size: 96)
                                    .overlay(alignment: .bottomTrailing) {
                                        if managing {
                                            Image(systemName: "pencil.circle.fill").font(.system(size: 28)).symbolRenderingMode(.palette)
                                                .foregroundStyle(.black, .white).offset(x: 4, y: 4)
                                        }
                                    }
                                    .overlay { if v.id == stremio.viewer?.id && !managing { Circle().strokeBorder(.white, lineWidth: 3).padding(-5) } }
                                Text(v.name).font(.callout.weight(.medium)).foregroundStyle(.white).lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    if stremio.viewers.count < 8 {
                        Button { adding = true } label: {
                            VStack(spacing: 10) {
                                Image(systemName: "plus").font(.system(size: 34, weight: .medium))
                                    .frame(width: 96, height: 96)
                                    .background(.white.opacity(0.12), in: .circle)
                                Text("Add Profile").font(.callout.weight(.medium)).foregroundStyle(Dx.muted)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 28)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: 420)
            Spacer(minLength: 0)
            Button { accountsSheet = true } label: {
                HStack(spacing: 10) {
                    Avatar(email: stremio.account?.email, size: 28)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(stremio.account?.signedIn == true ? (stremio.account?.email ?? "Stremio account") : "No account").font(.footnote.weight(.semibold))
                        Text(stremio.accounts.count > 1 || stremio.account?.signedIn != true ? "Switch account" : "Accounts").font(.caption).foregroundStyle(Dx.muted)
                    }
                    Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(Dx.faint)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                .glassEffect(.regular.interactive(), in: .capsule)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 26)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .screenBackground()
        .sheet(item: $editing) { v in ProfileEditor(viewer: v) }
        .sheet(isPresented: $adding) { ProfileEditor(viewer: nil) }
        .sheet(isPresented: $accountsSheet) { NavigationStack { AccountsView(onSwitch: { accountsSheet = false; onDone() }) } }
    }
}

/** Name and colour of a profile; Delete for one that exists */
struct ProfileEditor: View {
    @Environment(Stremio.self) private var stremio
    @Environment(\.dismiss) private var dismiss
    var viewer: Viewer?
    @State private var name = ""
    @State private var colour = profileColours[0]
    @State private var confirm = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack { Spacer(); ProfileAvatar(name: name.isEmpty ? "?" : name, colour: colour, size: 92); Spacer() }
                        .listRowBackground(Color.clear)
                    TextField("Name", text: $name)
                        .focused($focused)
                        .autocorrectionDisabled()
                        .onChange(of: name) { _, n in if n.count > 20 { name = String(n.prefix(20)) } }
                }
                Section("Colour") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 14) {
                        ForEach(profileColours, id: \.self) { c in
                            Button { colour = c } label: {
                                Circle().fill(Color.profile(c)).frame(width: 44, height: 44)
                                    .overlay { if c == colour { Image(systemName: "checkmark").font(.headline.bold()).foregroundStyle(.white) } }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(c)
                        }
                    }
                    .padding(.vertical, 6)
                }
                if let viewer, stremio.viewers.count > 1 {
                    Section {
                        Button("Delete Profile", role: .destructive) { confirm = true }
                    } footer: {
                        Text("Titles only they had saved leave the Library. Their Continue Watching and stats go with them.")
                    }
                }
            }
            .navigationTitle(viewer == nil ? "New Profile" : "Edit Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let n = name.trimmingCharacters(in: .whitespaces)
                        if let viewer { stremio.editViewer(viewer.id, name: n, colour: colour) } else { stremio.addViewer(name: n, colour: colour) }
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .confirmationDialog("Delete \(viewer?.name ?? "this profile")?", isPresented: $confirm, titleVisibility: .visible) {
                Button("Delete Profile", role: .destructive) {
                    if let viewer { stremio.removeViewer(viewer.id) }
                    dismiss()
                }
            }
            .onAppear {
                if let viewer { name = viewer.name; colour = viewer.colour ?? profileColours[0] }
                else {
                    let used = Set(stremio.viewers.compactMap(\.colour))
                    colour = profileColours.first { !used.contains($0) } ?? profileColours[0]
                    focused = true
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/** The accounts kept on this device: switch without a password, add another, or browse without one */
struct AccountsView: View {
    @Environment(Stremio.self) private var stremio
    @Environment(\.dismiss) private var dismiss
    var onSwitch: () -> Void = {}

    var body: some View {
        List {
            Section {
                ForEach(stremio.accounts) { a in
                    Button {
                        if a.id != stremio.currentAccountId || stremio.account?.signedIn != true {
                            stremio.switchAccount(to: a.id)
                            onSwitch()
                        }
                    } label: {
                        HStack(spacing: 12) {
                            Avatar(email: a.email, size: 34)
                            Text(a.email.isEmpty ? "Stremio account" : a.email).foregroundStyle(.white).lineLimit(1)
                            Spacer()
                            if a.id == stremio.currentAccountId && stremio.account?.signedIn == true { Image(systemName: "checkmark").foregroundStyle(Dx.accent) }
                        }
                    }
                    .swipeActions {
                        Button("Remove", role: .destructive) { stremio.forgetAccount(a.id) }
                    }
                }
                if stremio.accounts.isEmpty && stremio.account?.signedIn == true {
                    HStack(spacing: 12) {
                        Avatar(email: stremio.account?.email, size: 34)
                        Text(stremio.account?.email ?? "Stremio account")
                        Spacer()
                        Image(systemName: "checkmark").foregroundStyle(Dx.accent)
                    }
                }
                Button { stremio.beginAddAccount(); onSwitch() } label: {
                    Label("Add Account", systemImage: "plus.circle.fill")
                }
                Button {
                    stremio.switchAccount(to: nil)
                    onSwitch()
                } label: {
                    HStack {
                        Label("Browse Without an Account", systemImage: "person.crop.circle.dashed")
                        Spacer()
                        if stremio.guest && stremio.account?.signedIn != true { Image(systemName: "checkmark").foregroundStyle(Dx.accent) }
                    }
                }
            } footer: {
                Text("Each account you sign in to stays on this device, in the Keychain, so you can switch without typing the password again. The password itself is never kept. Swipe an account to take it off this device.")
            }
        }
        .navigationTitle("Accounts")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", systemImage: "checkmark") { dismiss() } } }
    }
}

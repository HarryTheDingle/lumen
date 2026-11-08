import Foundation
import Security
import SwiftUI

/**
 * Several Stremio accounts on one device. Each account that has signed in keeps its Stremio session
 * (the page's "profile": the sign-in key, addons and settings) and its people on this device (Lumen's
 * viewers and what is theirs) in the Keychain, so swapping is swapping those and starting Stremio's
 * page again: the password is typed once, when the account is added, and never kept.
 */
struct SavedAccount: Codable, Identifiable, Equatable {
    /** Stremio's user id (the email when it has none); "guest" is browsing without an account */
    var id: String
    var email: String
    var avatar: String?
    /** The page's profile, as the page wrote it ("" for guest) */
    var profile: String
    /** Lumen's data for this account's people, as JSON */
    var data: String

    static let guestId = "guest"
    var isGuest: Bool { id == Self.guestId }
    var dataObject: JSON { (Json.decode(data) as? JSON) ?? [:] }
}

enum AccountVault {
    private static let service = "com.harrythedingle.lumen.accounts"

    private static func query(_ id: String? = nil) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        if let id { q[kSecAttrAccount as String] = id }
        return q
    }

    // The Keychain is the home of these. A build that isn't signed (the Simulator's, or one re-signed in a way
    // that gives it no Keychain) is refused it, so then a file the system encrypts while the device is locked,
    // kept out of backups, holds them instead.
    private static var folder: URL {
        let url = URL.applicationSupportDirectory.appending(path: "accounts", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    private static func file(_ id: String) -> URL { folder.appending(path: id.replacingOccurrences(of: "/", with: "_") + ".json") }

    static func save(_ account: SavedAccount) {
        guard let data = try? JSONEncoder().encode(account) else { return }
        let update = [kSecValueData as String: data]
        var status = SecItemUpdate(query(account.id) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(account.id)
            add[kSecValueData as String] = data
            // (this device only, once it has been unlocked since starting: never in a backup or on another device)
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status != errSecSuccess {
            var url = file(account.id)
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        }
    }

    static func load(_ id: String) -> SavedAccount? {
        var q = query(id)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data,
           let account = try? JSONDecoder().decode(SavedAccount.self, from: data) { return account }
        return (try? Data(contentsOf: file(id))).flatMap { try? JSONDecoder().decode(SavedAccount.self, from: $0) }
    }

    /** The signed-in accounts that are kept, in the order they were added (guest isn't one) */
    static func all() -> [SavedAccount] {
        var found: [(Date, SavedAccount)] = []
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecReturnAttributes as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitAll
        var out: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let rows = out as? [[String: Any]] {
            for row in rows {
                if let data = row[kSecValueData as String] as? Data, let a = try? JSONDecoder().decode(SavedAccount.self, from: data) {
                    found.append((row[kSecAttrCreationDate as String] as? Date ?? .distantPast, a))
                }
            }
        }
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.creationDateKey])) ?? []
        for url in files where url.pathExtension == "json" {
            if let data = try? Data(contentsOf: url), let a = try? JSONDecoder().decode(SavedAccount.self, from: data), !found.contains(where: { $0.1.id == a.id }) {
                found.append(((try? url.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast, a))
            }
        }
        return found.sorted { $0.0 < $1.0 }.map(\.1).filter { !$0.isGuest }
    }

    static func remove(_ id: String) {
        SecItemDelete(query(id) as CFDictionary)
        try? FileManager.default.removeItem(at: file(id))
    }
}

extension Stremio {

    /** Keeps the signed-in account (its session and people) in the Keychain, so it can be switched back to without a password */
    func rememberAccount(force: Bool = false) async {
        guard !swapping else { return }
        if account?.signedIn == true {
            if !force, Date().timeIntervalSince(lastRemembered) < 300 { return }
            guard let export = await askPublic("accountExport") as? JSON, let profile = export.text("profile") else { return }
            let data = await askDx("phone.accountData") as? JSON ?? [:]
            let id = export.string("id")
            guard !id.isEmpty else { return }
            AccountVault.save(SavedAccount(id: id, email: export.string("email"), avatar: export.text("avatar"), profile: profile, data: Json.encode(data)))
            currentAccountId = id
        } else if guest {
            let data = await askDx("phone.accountData") as? JSON ?? [:]
            AccountVault.save(SavedAccount(id: SavedAccount.guestId, email: "", avatar: nil, profile: "", data: Json.encode(data)))
            currentAccountId = nil
        } else {
            return
        }
        lastRemembered = Date()
        accounts = AccountVault.all()
    }

    /** Switches to a kept account, or (nil) to browsing without one: nothing to type */
    func switchAccount(to id: String?) {
        guard !swapping, id != currentAccountId || (id == nil && !guest) else { return }
        if let id, AccountVault.load(id) == nil {
            show("That account is no longer kept on this device. Sign in again to add it.")
            accounts = AccountVault.all()
            return
        }
        addingAccount = false
        swap(to: id.flatMap(AccountVault.load) ?? AccountVault.load(SavedAccount.guestId), signedOut: false, guest: id == nil)
    }

    /** Add Account: the sign-in screen, with Cancel to come back to this one */
    func beginAddAccount() {
        guard !swapping else { return }
        returnTo = currentAccountId
        returnToGuest = account?.signedIn != true
        addingAccount = true
        swap(to: nil, signedOut: true, guest: false)
    }

    func cancelAddAccount() {
        addingAccount = false
        if let id = returnTo, AccountVault.load(id) != nil { swap(to: AccountVault.load(id), signedOut: false, guest: false) }
        else { swap(to: AccountVault.load(SavedAccount.guestId), signedOut: false, guest: true) }
    }

    /** Takes a kept account off this device (the account itself, and its Stremio session on other devices, stay) */
    func forgetAccount(_ id: String) {
        if id == currentAccountId { return signOut() }
        AccountVault.remove(id)
        accounts = AccountVault.all()
    }

    /** Signed in a new account: kept from now on */
    func didSignIn() {
        addingAccount = false
        Task { await rememberAccount(force: true) }
    }

    /**
     * The swap: the account being left is kept first, then its data and session are replaced by [target]'s
     * (nothing: the sign-in screen, or guest) and Stremio's page starts again to read them.
     */
    private func swap(to target: SavedAccount?, signedOut: Bool, guest toGuest: Bool) {
        Task {
            await rememberAccount(force: true)
            swapping = true
            clearScreens()
            _ = await askDx("phone.useAccountData", target?.dataObject ?? [:])
            guest = toGuest
            currentAccountId = signedOut || toGuest ? nil : target?.id
            lastRemembered = .distantPast
            let profile: Any = (signedOut || toGuest) ? NSNull() : (target?.profile ?? NSNull())
            _ = await askPublic("accountImport", ["profile": profile])
            // (the page says it's ready when it has started again: started() ends this; if it never does, the screen doesn't stay)
            try? await Task.sleep(for: .seconds(30))
            swapping = false
        }
    }
}

/** A profile's colour (Lumen's viewer colours are hex; a missing one is Lumen's blue) */
extension Color {
    static func profile(_ hex: String?) -> Color { Color(hex: hex) ?? Color(red: 0.35, green: 0.56, blue: 0.69) }
}

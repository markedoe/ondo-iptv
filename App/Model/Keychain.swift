import Foundation
import Security
import CryptoKit

/// Small wrapper over the keychain for provider passwords and the parental PIN.
enum Keychain {
    private static let service = "com.markedoe.ondo"

    /// Stores (or deletes, with `nil`) a value. Returns `false` when the keychain refused the write.
    @discardableResult
    static func set(_ value: String?, for key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return true }
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func passwordKey(for playlistID: UUID) -> String { "xtream-password-\(playlistID.uuidString)" }

    static let parentalPINKey = "parental-pin-sha256"
    /// Wrong PIN count and the date before which no PIN is checked (see `AppModel.verifyParentalPIN`).
    static let pinFailuresKey = "pin-failures"
    static let pinRetryKey = "pin-retry-after"
    static let tmdbKey = "tmdb-credential"

    static func hash(pin: String) -> String {
        let digest = SHA256.hash(data: Data(("ondo:" + pin).utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

/// Where secrets (provider passwords, tokens, PIN hashes) are kept. Injected so tests never touch the keychain.
protocol SecretStore: AnyObject {
    @discardableResult func set(_ value: String?, for key: String) -> Bool
    func get(_ key: String) -> String?
}

final class KeychainSecretStore: SecretStore {
    func set(_ value: String?, for key: String) -> Bool { Keychain.set(value, for: key) }
    func get(_ key: String) -> String? { Keychain.get(key) }
}

/// Volatile store for tests and previews; `failsWrites` simulates an unavailable keychain.
final class InMemorySecretStore: SecretStore {
    private(set) var values: [String: String] = [:]
    var failsWrites = false

    init(failsWrites: Bool = false) {
        self.failsWrites = failsWrites
    }

    func set(_ value: String?, for key: String) -> Bool {
        guard !failsWrites else { return false }
        values[key] = value
        return true
    }

    func get(_ key: String) -> String? { values[key] }
}

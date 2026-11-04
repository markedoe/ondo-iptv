import Foundation

/// What a device decides about profiles received by sync, the same on every platform (docs/SYNC.md, "Profiles"). A port
/// of the Kotlin `SyncProfiles` (android/core).
public enum SyncProfiles {
    /// Whether a profile stays a Kids profile when sync brings `received` for it: a profile made Kids always becomes one,
    /// but one made not Kids elsewhere stays Kids on a device where a parent set a PIN (`parentProtected`), as its PIN
    /// does. Its record (and time) are taken all the same: that device's own snapshot keeps saying Kids, at the same time,
    /// so the merge settles on the other device's choice (the smaller tie) and nothing changes from one session to the
    /// next.
    public static func keptKids(local: Bool, received: Bool, parentProtected: Bool) -> Bool {
        received || (local && parentProtected)
    }

    /// A name as the one-time match compares it: trimmed, without accents, in lower case.
    public static func normalizedName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let bare = String(String.UnicodeScalarView(trimmed.decomposedStringWithCanonicalMapping.unicodeScalars.filter {
            $0.properties.generalCategory != .nonspacingMark
        }))
        return bare.lowercased()
    }

    /// The one-time match of profiles made apart on two devices before they synced profiles: each of `local` (this
    /// device's, never synced) is one with the profile of `received` (none of which this device has) of the same
    /// normalised name and the same Kids switch, the first free one by ID. The smaller ID is kept; the pairs are given as
    /// (dropped, kept). The main profile is never matched: it is the same on every device.
    public static func matches(local: [SyncProfile], received: [SyncProfile]) -> [(dropped: String, kept: String)] {
        let localIDs = Set(local.map(\.id))
        let receivedIDs = Set(received.map(\.id))
        var free = received.filter { $0.id != SyncProfile.main && !localIDs.contains($0.id) }.sorted { $0.id.isBefore($1.id) }
        var pairs: [(dropped: String, kept: String)] = []
        for mine in local.filter({ $0.id != SyncProfile.main && !receivedIDs.contains($0.id) }).sorted(by: { $0.id.isBefore($1.id) }) {
            let name = normalizedName(mine.name)
            guard let index = free.firstIndex(where: { normalizedName($0.name) == name && $0.kids == mine.kids }) else { continue }
            let other = free.remove(at: index)
            pairs.append(mine.id.isBefore(other.id) ? (dropped: other.id, kept: mine.id) : (dropped: mine.id, kept: other.id))
        }
        return pairs
    }
}

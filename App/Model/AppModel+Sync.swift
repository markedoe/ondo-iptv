import Foundation
import IPTVCore
#if os(iOS)
import UIKit
#endif

/// What sync keeps on this device besides the library (docs/SYNC.md): its ID, when each playlist was added or last
/// edited, when the TMDb key was saved, the keys of other services passed on, what was deleted and when, and which
/// playlists became another. Kept in `sync.json`, next to the library, and never in a backup: another device restored
/// from one must not take this device's ID.
struct SyncState: Codable, Equatable {
    /// A random UUID made once (lowercase), sent as `device`.
    var deviceID = UUID().uuidString.lowercased()
    /// Milliseconds since 1970, by playlist ID (lowercase): when its name, address, login or guide last changed.
    var playlistUpdatedAt: [String: Int64] = [:]
    /// Milliseconds since 1970: when the TMDb key of the keychain was saved, here or on the device it came from. A key
    /// found without a date (saved before sync carried it, or kept by the keychain across a reinstall) is dated when the
    /// app starts (`loadSyncState`): undated, it would lose to any removal or key another device holds.
    var tmdbKeyUpdatedAt: Int64?
    /// The keys of services this app does not use, as other devices sent them: kept only to pass them on.
    var otherKeys: [SyncKey] = []
    /// Deleted records (`playlist:…`, `list:…`, `progress:…`, and those other devices sent) and when, in milliseconds.
    var deleted: [String: Int64] = [:]
    var replaced: [String: String] = [:]

    init() {}

    enum CodingKeys: String, CodingKey { case deviceID, playlistUpdatedAt, tmdbKeyUpdatedAt, otherKeys, deleted, replaced }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = (try? c.decodeIfPresent(String.self, forKey: .deviceID)) ?? UUID().uuidString.lowercased()
        playlistUpdatedAt = (try? c.decodeIfPresent([String: Int64].self, forKey: .playlistUpdatedAt)) ?? [:]
        tmdbKeyUpdatedAt = try? c.decodeIfPresent(Int64.self, forKey: .tmdbKeyUpdatedAt)
        otherKeys = (try? c.decodeIfPresent([SyncKey].self, forKey: .otherKeys)) ?? []
        deleted = (try? c.decodeIfPresent([String: Int64].self, forKey: .deleted)) ?? [:]
        replaced = (try? c.decodeIfPresent([String: String].self, forKey: .replaced)) ?? [:]
    }

    /// Without deletions older than the spec keeps them (180 days), nor the replacements they ended.
    func pruned(now: Date) -> SyncState {
        let kept = SyncSnapshot(device: deviceID, deleted: deleted, replaced: replaced).pruned(now: now.syncMilliseconds)
        var copy = self
        copy.deleted = kept.deleted
        copy.replaced = kept.replaced
        return copy
    }
}

extension Date {
    /// Milliseconds since 1970, as sync writes times. Past Int64's range it saturates, as Kotlin's `toLong()` does: a
    /// time of `Int64.max` from another device comes back from `Date` as 2^63, which `Int64(_:)` traps on.
    var syncMilliseconds: Int64 {
        let milliseconds = (timeIntervalSince1970 * 1000).rounded()
        if let exact = Int64(exactly: milliseconds) { return exact }
        if milliseconds.isNaN { return 0 }
        return milliseconds < 0 ? .min : .max
    }

    init(syncMilliseconds milliseconds: Int64) {
        self.init(timeIntervalSince1970: Double(milliseconds) / 1000)
    }
}

/// The app's data as a sync snapshot, and a merged snapshot back into the app's data. Pure functions: `AppModel+Sync`
/// reads and writes the model with them.
enum SyncMapping {
    // MARK: Model → snapshot

    static func snapshot(playlists: [Playlist], library: UserLibrary, state: SyncState, tmdbKey: String? = nil, deviceName: String?,
                         now: Date) -> SyncSnapshot {
        var deleted = state.deleted
        func delete(_ key: String, at date: Date) {
            deleted[key] = max(deleted[key] ?? date.syncMilliseconds, date.syncMilliseconds)
        }
        for (itemKey, date) in library.favoriteTombstones {
            delete("favorite:" + lowercasingPlaylistID(itemKey), at: date)
        }
        for (playbackKey, date) in library.historyTombstones where isSyncedProgressKey(playbackKey) {
            delete("progress:" + lowercasingPlaylistID(playbackKey), at: date)
        }
        let snapshot = SyncSnapshot(
            device: state.deviceID,
            deviceName: deviceName,
            playlists: playlists.compactMap { playlist in
                let id = playlist.id.uuidString.lowercased()
                return syncPlaylist(playlist, updatedAt: state.playlistUpdatedAt[id] ?? playlist.createdAt.syncMilliseconds)
            },
            favorites: library.favorites.map { favorite in
                SyncFavorite(playlist: favorite.reference.playlistID.uuidString.lowercased(), kind: favorite.reference.type.rawValue,
                             item: favorite.reference.itemID, title: favorite.title, image: favorite.imageURL?.absoluteString,
                             addedAt: favorite.addedAt.syncMilliseconds)
            },
            progress: library.history.filter { $0.reference.type != .live }.map { entry in
                SyncProgress(playlist: entry.reference.playlistID.uuidString.lowercased(), kind: entry.reference.type.rawValue,
                             item: entry.reference.itemID, episode: entry.reference.episodeID, title: entry.title,
                             subtitle: entry.subtitle, image: entry.imageURL?.absoluteString,
                             position: entry.position.isFinite ? entry.position : 0, duration: entry.duration.isFinite ? entry.duration : 0,
                             watchedAt: entry.lastWatchedAt.syncMilliseconds)
            },
            lists: library.customLists.map { list in
                SyncList(id: list.id.uuidString.lowercased(), name: list.name, items: list.items.map { item in
                    SyncListItem(playlist: item.reference.playlistID.uuidString.lowercased(), kind: item.reference.type.rawValue,
                                 item: item.reference.itemID, title: item.title, image: item.imageURL?.absoluteString)
                }, updatedAt: list.updatedAt.syncMilliseconds)
            },
            keys: keys(tmdbKey: tmdbKey, state: state),
            deleted: deleted,
            replaced: state.replaced
        )
        return snapshot.pruned(now: now.syncMilliseconds)
    }

    /// The keychain's TMDb key, dated when it was saved (0 only before `loadSyncState` dated it), and the keys passed on
    /// for the services this app does not use.
    static func keys(tmdbKey: String?, state: SyncState) -> [SyncKey] {
        var keys = state.otherKeys.filter { $0.service != SyncKey.tmdb }
        if let tmdbKey, !tmdbKey.isEmpty {
            keys.append(SyncKey(service: SyncKey.tmdb, value: tmdbKey, updatedAt: state.tmdbKeyUpdatedAt ?? 0))
        }
        return keys
    }

    /// A key from another device as it can be used: not empty, no spaces or control characters (it goes into a header
    /// or an address).
    static func isUsableKey(_ value: String) -> Bool {
        !value.isEmpty && value.rangeOfCharacter(from: CharacterSet.whitespacesAndNewlines.union(.controlCharacters)) == nil
    }

    /// An M3U address or an Xtream login; nil for a file playlist, which stays on its device.
    static func syncPlaylist(_ playlist: Playlist, updatedAt: Int64) -> SyncPlaylist? {
        let id = playlist.id.uuidString.lowercased()
        let guide = playlist.epgURLs.first?.absoluteString
        let added = playlist.createdAt.syncMilliseconds
        switch playlist.source {
        case .remote(let url):
            return SyncPlaylist(id: id, name: playlist.name, kind: SyncPlaylist.m3u, url: url.absoluteString, epgUrl: guide,
                                addedAt: added, updatedAt: updatedAt)
        case .xtream(let login):
            return SyncPlaylist(id: id, name: playlist.name, kind: SyncPlaylist.xtream, url: login.server.absoluteString,
                                username: login.username, password: login.password, epgUrl: guide, addedAt: added, updatedAt: updatedAt)
        case .local:
            return nil
        }
    }

    /// Whether a change from `previous` to `playlist` is one sync carries (name, address, login, guide).
    static func syncedFieldsDiffer(_ previous: Playlist, _ playlist: Playlist) -> Bool {
        syncPlaylist(previous, updatedAt: 0) != syncPlaylist(playlist, updatedAt: 0)
    }

    /// `UUID|kind|item…` with the playlist ID in lowercase; item IDs keep their case (they are the provider's).
    static func lowercasingPlaylistID(_ key: String) -> String {
        guard let bar = key.firstIndex(of: "|") else { return key.lowercased() }
        return key[..<bar].lowercased() + key[bar...]
    }

    private static func isSyncedProgressKey(_ playbackKey: String) -> Bool {
        let parts = playbackKey.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false)
        return parts.count > 1 && (parts[1] == ContentType.movie.rawValue || parts[1] == ContentType.series.rawValue)
    }

    // MARK: Snapshot → model

    /// The playlist a record describes: `base` (the device's own) with the record's name, address, login and guide, or
    /// a new playlist with the app's defaults. Nil for a record the app cannot hold (unknown kind, invalid ID or address).
    static func playlist(from record: SyncPlaylist, base: Playlist?) -> Playlist? {
        guard let id = UUID(uuidString: record.id) else { return nil }
        if let base, case .local = base.source { return nil }
        var playlist = base ?? Playlist(id: id, name: record.name, source: .remote(URL(fileURLWithPath: "/")),
                                        createdAt: Date(syncMilliseconds: record.addedAt))
        playlist.name = record.name
        // The address and login as received; the playlist's own source when they are the same (no needless reload).
        let current = base.flatMap { syncPlaylist($0, updatedAt: 0) }
        if base == nil || current?.kind != record.kind || current?.url != record.url || current?.username != record.username
            || current?.password != record.password {
            switch record.kind {
            case SyncPlaylist.m3u:
                guard let url = address(record.url) else { return nil }
                playlist.source = .remote(url)
            case SyncPlaylist.xtream:
                guard let server = server(record.url) else { return nil }
                playlist.source = .xtream(XtreamCredentials(server: server, username: record.username ?? "", password: record.password ?? ""))
            default:
                return nil
            }
        }
        // Sync carries one guide address, the first of the playlist's.
        if current?.epgUrl != record.epgUrl || base == nil {
            if let text = record.epgUrl, let guide = address(text) {
                playlist.epgURLs = [guide] + playlist.epgURLs.dropFirst().filter { $0 != guide }
            } else if record.epgUrl == nil {
                playlist.epgURLs = []
            }
        }
        return playlist
    }

    /// Never a file: file playlists stay on their device (docs/SYNC.md), and a `file:` address from another device would
    /// make this one read its own files as a playlist or a guide, whole, past the download limit.
    private static func address(_ text: String) -> URL? {
        guard let url = URL(string: text) ?? URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              !url.isFileURL, url.scheme?.lowercased() != "file" else { return nil }
        return url
    }

    /// The Xtream server as written when it is a full address, else completed as the app completes a typed one.
    private static func server(_ text: String) -> URL? {
        if let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? ""), url.host != nil { return url }
        return XtreamCredentials.normalizeServer(text)
    }

    private static func reference(playlist: String, kind: String, item: String, episode: String? = nil) -> MediaReference? {
        guard let playlistID = UUID(uuidString: playlist), let type = ContentType(rawValue: kind) else { return nil }
        return MediaReference(playlistID: playlistID, type: type, itemID: item, episodeID: episode?.isEmpty == false ? episode : nil)
    }

    /// `base` with My List and favourite channels, progress and lists replaced by the snapshot's. What sync does not
    /// carry stays: live channel history, hidden categories and channels, reminders, the order of My List, the
    /// containers of episodes. Progress not later than "Clear Watch History" stays cleared.
    static func library(_ base: UserLibrary, applying snapshot: SyncSnapshot) -> UserLibrary {
        var library = base

        let favoritesByKey = Dictionary(base.favorites.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var nextIndex = (base.favorites.map(\.sortIndex).max() ?? -1) + 1
        var seenFavorites = Set<String>()
        library.favorites = snapshot.favorites.compactMap { record in
            guard let reference = reference(playlist: record.playlist, kind: record.kind, item: record.item),
                  seenFavorites.insert(reference.itemKey).inserted else { return nil }
            var entry: FavoriteEntry
            if let existing = favoritesByKey[reference.itemKey] {
                entry = existing
            } else {
                entry = FavoriteEntry(reference: reference, title: record.title, imageURL: nil, sortIndex: nextIndex)
                nextIndex += 1
            }
            entry.title = record.title
            entry.imageURL = image(record.image, keeping: entry.imageURL)
            entry.addedAt = Date(syncMilliseconds: record.addedAt)
            return entry
        }

        let historyByKey = Dictionary(base.history.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let clearedAt = base.historyClearedAt
        var seenProgress = Set<String>()
        let progress: [HistoryEntry] = snapshot.progress.compactMap { record in
            guard record.kind == ContentType.movie.rawValue || record.kind == ContentType.series.rawValue,
                  let reference = reference(playlist: record.playlist, kind: record.kind, item: record.item, episode: record.episode),
                  seenProgress.insert(reference.playbackKey).inserted else { return nil }
            let watchedAt = Date(syncMilliseconds: record.watchedAt)
            if let clearedAt, watchedAt <= clearedAt { return nil }
            var entry = historyByKey[reference.playbackKey] ?? HistoryEntry(reference: reference, title: record.title)
            entry.title = record.title
            entry.subtitle = record.subtitle
            entry.imageURL = image(record.image, keeping: entry.imageURL)
            entry.lastWatchedAt = watchedAt
            entry.position = record.position
            entry.duration = record.duration
            return entry
        }
        let channels = base.history.filter { $0.reference.type == .live }
        library.history = Array((channels + progress)
            .sorted { ($0.lastWatchedAt, $0.id) > ($1.lastWatchedAt, $1.id) }
            .prefix(UserLibrary.maxHistory))

        let listsByID = Dictionary(base.customLists.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var lists: [UUID: CustomList] = [:]
        var order: [UUID] = []
        for record in snapshot.lists {
            guard let id = UUID(uuidString: record.id), lists[id] == nil else { continue }
            let updatedAt = Date(syncMilliseconds: record.updatedAt)
            let itemsByKey = Dictionary((listsByID[id]?.items ?? []).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            var seenItems = Set<String>()
            let items: [FavoriteEntry] = record.items.compactMap { item in
                guard let reference = reference(playlist: item.playlist, kind: item.kind, item: item.item),
                      seenItems.insert(reference.itemKey).inserted else { return nil }
                var entry = itemsByKey[reference.itemKey] ?? FavoriteEntry(reference: reference, title: item.title, imageURL: nil, addedAt: updatedAt)
                entry.title = item.title
                entry.imageURL = image(item.image, keeping: entry.imageURL)
                entry.sortIndex = seenItems.count - 1
                return entry
            }
            lists[id] = CustomList(id: id, name: record.name, items: items, updatedAt: updatedAt)
            order.append(id)
        }
        // The device's lists keep their place; new ones come after them.
        let kept = base.customLists.map(\.id).filter { lists[$0] != nil }
        library.customLists = (kept + order.filter { listsByID[$0] == nil }).compactMap { lists[$0] }
        return library
    }

    /// The image address received; the device's own `URL` when it is the same one. Another device's is a web address:
    /// Nuke reads a `file:` or `data:` address inline and whole, a file of this device included.
    private static func image(_ text: String?, keeping current: URL?) -> URL? {
        guard let text else { return nil }
        if current?.absoluteString == text { return current }
        guard let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return nil }
        return url
    }
}

/// Sync on the local network (docs/SYNC.md), the model's side: what this device sends, and the merged snapshot it gets
/// back applied through the app's normal paths. The network side is `LocalSync`.
extension AppModel {
    /// Only the main profile syncs: kids and other profiles keep their library on this device.
    var syncsThisProfile: Bool { activeProfile.id == Profile.defaultID }

    /// The name other devices list this one under: the kind of device, never the name the owner gave it.
    static var syncDeviceName: String {
        #if os(iOS)
        return UIDevice.current.model
        #else
        return "Mac"
        #endif
    }

    func syncSnapshot(now: Date = Date()) -> SyncSnapshot {
        SyncMapping.snapshot(playlists: playlists, library: library, state: syncState, tmdbKey: secrets.get(Keychain.tmdbKey),
                             deviceName: Self.syncDeviceName, now: now)
    }

    /// The snapshot to send, or nil when this device does not sync now (not started, another profile).
    func syncSnapshotForSending() -> SyncSnapshot? {
        guard isReady, syncsThisProfile else { return nil }
        return syncSnapshot()
    }

    func setSyncOnLocalNetwork(_ on: Bool) {
        updateSettings { $0.syncOnLocalNetwork = on }
        refreshLocalSync()
    }

    /// The app came to the foreground or went to the background.
    func setInForeground(_ foreground: Bool) {
        isInForeground = foreground
        refreshLocalSync()
        // TMDb's background prefetch too.
        if foreground { resumeMetadataPrefetch() } else { pauseMetadataPrefetch() }
    }

    /// Starts or stops looking for devices: only while sync is on, the app in the foreground and the main profile open.
    func refreshLocalSync() {
        if isReady, settings.syncOnLocalNetwork, isInForeground, syncsThisProfile {
            localSync.start(deviceID: syncState.deviceID)
        } else {
            localSync.stop()
        }
    }

    /// Something sync carries changed here: the devices found hear of it a few seconds later.
    func syncSoon() {
        localSync.scheduleSoon()
    }

    /// Reads `sync.json`, or makes this device's ID the first time. A TMDb key of the keychain without a date is dated
    /// now, once: sent undated, it counted as the oldest, so a removal or a key on another device always won over it (the
    /// other device kept its own, and this one even removed its key) while sync said it went well.
    func loadSyncState(now: Date = Date()) {
        var changed = false
        if let state = userStore.load(SyncState.self, name: "sync") {
            syncState = state
        } else {
            changed = true
        }
        if syncState.tmdbKeyUpdatedAt == nil, secrets.get(Keychain.tmdbKey) != nil {
            syncState.tmdbKeyUpdatedAt = now.syncMilliseconds
            changed = true
        }
        if changed { saveSyncState(now: now) }
    }

    func saveSyncState(now: Date = Date()) {
        syncState = syncState.pruned(now: now)
        try? userStore.save(syncState, name: "sync")
    }

    /// A playlist was added or its name, address, login or guide edited.
    func notePlaylistChangedForSync(_ id: UUID, now: Date = Date()) {
        syncState.playlistUpdatedAt[id.uuidString.lowercased()] = now.syncMilliseconds
        saveSyncState(now: now)
        syncSoon()
    }

    /// A playlist was edited: only a change sync carries (name, address, login, guide) dates it.
    func notePlaylistEditedForSync(from previous: Playlist, to playlist: Playlist) {
        guard SyncMapping.syncedFieldsDiffer(previous, playlist) else { return }
        notePlaylistChangedForSync(playlist.id)
    }

    /// Playlists restored from a backup are dated now: they win over a deletion other devices still remember.
    func notePlaylistsRestoredForSync(_ restored: [Playlist], now: Date = Date()) {
        for playlist in restored {
            syncState.playlistUpdatedAt[playlist.id.uuidString.lowercased()] = now.syncMilliseconds
        }
        saveSyncState(now: now)
        syncSoon()
    }

    /// A playlist was deleted: other devices delete it too (file playlists stay on their device). `recordsDeletion` is
    /// false when sync deleted it, keeping the time it was deleted elsewhere.
    func notePlaylistDeletedForSync(_ playlist: Playlist, recordsDeletion: Bool = true, now: Date = Date()) {
        let id = playlist.id.uuidString.lowercased()
        syncState.playlistUpdatedAt[id] = nil
        guard recordsDeletion else { return }
        if case .local = playlist.source { return }
        syncState.deleted["playlist:" + id] = now.syncMilliseconds
        saveSyncState(now: now)
        syncSoon()
    }

    func noteListDeletedForSync(_ id: UUID, now: Date = Date()) {
        syncState.deleted["list:" + id.uuidString.lowercased()] = now.syncMilliseconds
        saveSyncState(now: now)
        syncSoon()
    }

    /// "Clear Watch History" leaves no deletion marker in the library: the movies and episodes it cleared are marked
    /// here, so other devices clear them too.
    func noteHistoryClearedForSync(_ entries: [HistoryEntry], now: Date = Date()) {
        for entry in entries where entry.reference.type != .live {
            syncState.deleted["progress:" + SyncMapping.lowercasingPlaylistID(entry.id)] = now.syncMilliseconds
        }
        saveSyncState(now: now)
        syncSoon()
    }

    /// Keeps the merged snapshot a device answered with (docs/SYNC.md, end of "Merge"): new playlists are added (and
    /// their catalogue downloaded), changed ones updated, deleted ones removed, My List, favourite channels, lists and
    /// progress replaced, and the TMDb key saved or removed. Merged again with the current state first, so nothing
    /// changed during the session is lost (and an answer without keys removes none).
    func applySync(_ received: SyncSnapshot, now: Date = Date()) async {
        guard isReady, syncsThisProfile else { return }
        let merged = SyncMerge.merge(syncSnapshot(now: now), received)
        let records = Dictionary(merged.playlists.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        // Playlists edited elsewhere, then the new ones. The device's own are found by ID (a snapshot can hold tens of
        // thousands), and found again after an update, which waits and lets other changes in.
        func byID() -> [UUID: Playlist] { Dictionary(playlists.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first }) }
        var locals = byID()
        var added: [Playlist] = []
        for record in merged.playlists {
            guard let id = UUID(uuidString: record.id) else { continue }
            if let local = locals[id] {
                guard let updated = SyncMapping.playlist(from: record, base: local) else { continue }
                if updated != local {
                    await updatePlaylist(updated, recordsSyncChange: false)
                    locals = byID()
                }
            } else if let created = SyncMapping.playlist(from: record, base: duplicate(becoming: record, in: merged)) {
                added.append(created)
            } else {
                continue
            }
            syncState.playlistUpdatedAt[record.id] = record.updatedAt
        }
        if !added.isEmpty { await addSyncedPlaylists(added) }

        // Playlists deleted elsewhere, or that turned out to be another one (the same account added twice).
        for local in playlists {
            let id = local.id.uuidString.lowercased()
            if case .local = local.source { continue }
            guard records[id] == nil, merged.deleted["playlist:" + id] != nil else { continue }
            let replacement = replacementID(of: id, in: merged).flatMap { newID in
                playlists.first { $0.id.uuidString.lowercased() == newID }
            }
            if let replacement { carryDeviceSettings(from: local.id, to: replacement.id) }
            if let replacement, activePlaylist?.id == local.id {
                updateSettings { $0.activePlaylistID = replacement.id }
                await deletePlaylist(local, recordsSyncDeletion: false)
                await activate(replacement)
            } else {
                await deletePlaylist(local, recordsSyncDeletion: false)
            }
        }

        // The library, merged again with what changed while the playlists were applied. Not when another profile was
        // opened meanwhile: its library is not the one that synced.
        guard syncsThisProfile else {
            saveSyncState()
            return
        }
        let latest = SyncMerge.merge(syncSnapshot(), merged)
        let applied = SyncMapping.library(library, applying: latest)
        if applied != library { updateLibrary { $0 = applied } }
        applySyncedKeys(latest)
        syncState.deleted = latest.deleted
        syncState.replaced = latest.replaced
        saveSyncState()
    }

    /// The keys of the merged snapshot: TMDb's saved when it changed (TMDb checked it on the device where it was typed),
    /// removed when it was removed elsewhere since it was saved here; those of other services kept to pass on, never
    /// used.
    private func applySyncedKeys(_ snapshot: SyncSnapshot) {
        let tmdb = snapshot.keys.first(where: { $0.service == SyncKey.tmdb })
        if let tmdb, SyncMapping.isUsableKey(tmdb.value) {
            // Dated as the merge dated it only once the keychain holds it.
            if tmdb.value == secrets.get(Keychain.tmdbKey) || storeTMDbCredential(tmdb.value) {
                syncState.tmdbKeyUpdatedAt = tmdb.updatedAt
            }
        } else if tmdb == nil, let removed = snapshot.deleted["key:" + SyncKey.tmdb], removed >= (syncState.tmdbKeyUpdatedAt ?? 0),
                  secrets.get(Keychain.tmdbKey) != nil {
            // Removed elsewhere: no new deletion here, the other device's time stays.
            if storeTMDbCredential(nil) { syncState.tmdbKeyUpdatedAt = nil }
        }
        syncState.otherKeys = snapshot.keys.filter { $0.service != SyncKey.tmdb }
    }

    /// The device's own playlist that the merge found to be `record` under another ID (the same account added on two
    /// devices), as the base of the one that replaces it: this device's settings for it (headers, stream format,
    /// channel edits, guide choices) stay.
    private func duplicate(becoming record: SyncPlaylist, in snapshot: SyncSnapshot) -> Playlist? {
        guard let id = UUID(uuidString: record.id),
              var base = playlists.first(where: { replacementID(of: $0.id.uuidString.lowercased(), in: snapshot) == record.id })
        else { return nil }
        if case .local = base.source { return nil }
        base.id = id
        base.createdAt = Date(syncMilliseconds: record.addedAt)
        base.lastRefreshedAt = nil
        return base
    }

    /// The playlist that replaced `id` (rule 4 of the merge), following replacements as the merge does.
    private func replacementID(of id: String, in snapshot: SyncSnapshot) -> String? {
        var current = id
        for _ in 0..<10 {
            guard let next = snapshot.replaced[current] else { break }
            current = next
        }
        return current == id ? nil : current
    }
}

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
    /// Milliseconds since 1970, by `SyncKey` service (`opensubtitles`, `subdl`, … `addic7ed`): when each subtitle
    /// service's field of the keychain (or the Addic7ed switch) was saved, here or on the device it came from. Dated
    /// when the app starts if found without a date, as the TMDb key.
    var subtitleKeysUpdatedAt: [String: Int64] = [:]
    /// The actors and directors other devices keep in My List, and in each list (by list ID, lowercase): this app does
    /// not show them, it keeps them to pass them on, so that its own edits of a list never drop them.
    var people: [SyncPerson] = []
    var listPeople: [String: [SyncPerson]] = [:]
    /// The order of the rows of the Movies and Series tabs and the rows hidden (Settings → Row order, `RowOrder`), one
    /// record per tab with the time it was set: here rather than in the settings, as sync carries it (docs/SYNC.md, "Rows").
    var rowOrders: [SyncRows] = []
    /// The hidden records of kinds this app does not know (docs/SYNC.md, "Hidden categories"), as other devices sent them:
    /// kept only to pass them on. The hidden categories, tags and words themselves are in the library.
    var otherHidden: [SyncHidden] = []
    /// The categories, tags and words each profile hid before they synced were dated once (`loadSyncState`).
    var hiddenDated = false
    /// Deleted records (`playlist:…`, `list:…`, `progress:…`, and those other devices sent) and when, in milliseconds.
    var deleted: [String: Int64] = [:]
    var replaced: [String: String] = [:]
    /// Milliseconds since 1970: when a snapshot from another device was last applied here (the diagnostic report).
    var lastSyncedAt: Int64?
    /// Milliseconds since 1970, by device ID: the last session with each device. Deletions stay until each device seen
    /// in the last year has had a session after them (`SyncSnapshot.pruned`).
    var peers: [String: Int64] = [:]
    /// Milliseconds since 1970, by deleted key: when this device learned a deletion made elsewhere (kept from then).
    var learned: [String: Int64] = [:]
    /// Milliseconds since 1970: the latest time sync dated or received here (`SyncClock.last`, docs/SYNC.md "Clocks").
    var clock: Int64 = 0
    /// What sync keeps for each profile other than the main one, by profile ID (lowercase); the main one's is above.
    var profileStates: [String: ProfileSyncState] = [:]
    /// The one-time match of profiles of the same name made apart was done (the first profiles received by sync).
    var profilesMatched = false
    /// What sync keeps for the collections shared with every profile (docs/SYNC.md, "Shared lists"): what was deleted among
    /// them, when it was learned, and the people of each.
    var household = ProfileSyncState()

    init() {}

    enum CodingKeys: String, CodingKey {
        case deviceID, playlistUpdatedAt, tmdbKeyUpdatedAt, otherKeys, subtitleKeysUpdatedAt, people, listPeople, deleted, replaced, lastSyncedAt, peers
        case learned, clock, rowOrders, profileStates, profilesMatched, household, otherHidden, hiddenDated
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = (try? c.decodeIfPresent(String.self, forKey: .deviceID)) ?? UUID().uuidString.lowercased()
        playlistUpdatedAt = (try? c.decodeIfPresent([String: Int64].self, forKey: .playlistUpdatedAt)) ?? [:]
        tmdbKeyUpdatedAt = try? c.decodeIfPresent(Int64.self, forKey: .tmdbKeyUpdatedAt)
        otherKeys = (try? c.decodeIfPresent([SyncKey].self, forKey: .otherKeys)) ?? []
        subtitleKeysUpdatedAt = (try? c.decodeIfPresent([String: Int64].self, forKey: .subtitleKeysUpdatedAt)) ?? [:]
        people = (try? c.decodeIfPresent([SyncPerson].self, forKey: .people)) ?? []
        listPeople = (try? c.decodeIfPresent([String: [SyncPerson]].self, forKey: .listPeople)) ?? [:]
        deleted = (try? c.decodeIfPresent([String: Int64].self, forKey: .deleted)) ?? [:]
        replaced = (try? c.decodeIfPresent([String: String].self, forKey: .replaced)) ?? [:]
        lastSyncedAt = try? c.decodeIfPresent(Int64.self, forKey: .lastSyncedAt)
        peers = (try? c.decodeIfPresent([String: Int64].self, forKey: .peers)) ?? [:]
        learned = (try? c.decodeIfPresent([String: Int64].self, forKey: .learned)) ?? [:]
        clock = (try? c.decodeIfPresent(Int64.self, forKey: .clock)) ?? 0
        rowOrders = (try? c.decodeIfPresent([SyncRows].self, forKey: .rowOrders)) ?? []
        profileStates = (try? c.decodeIfPresent([String: ProfileSyncState].self, forKey: .profileStates)) ?? [:]
        profilesMatched = (try? c.decodeIfPresent(Bool.self, forKey: .profilesMatched)) ?? false
        otherHidden = (try? c.decodeIfPresent([SyncHidden].self, forKey: .otherHidden)) ?? []
        hiddenDated = (try? c.decodeIfPresent(Bool.self, forKey: .hiddenDated)) ?? false
        household = (try? c.decodeIfPresent(ProfileSyncState.self, forKey: .household)) ?? ProfileSyncState()
    }

    /// The state the shared collections' records are made from, as `slice(for:)` gives a profile's.
    func householdSlice() -> SyncState {
        var state = SyncState()
        state.deviceID = deviceID
        state.listPeople = household.listPeople
        state.deleted = household.deleted
        return state
    }

    /// The state a profile's records are made from: the main one's (this), or another's slice (`profileStates`).
    func slice(for profileID: String) -> SyncState {
        guard profileID != SyncProfile.main else { return self }
        let profile = profileStates[profileID] ?? ProfileSyncState()
        var state = SyncState()
        state.deviceID = deviceID
        state.people = profile.people
        state.listPeople = profile.listPeople
        state.rowOrders = profile.rowOrders
        state.otherHidden = profile.otherHidden
        state.deleted = profile.deleted
        return state
    }

    /// Without deletions older than the spec keeps them (180 days, and until each device seen in the last year has had
    /// a session after them), nor the replacements they ended, nor the devices not seen for a year. `now`: on the sync
    /// clock (`AppModel.syncNow`).
    func pruned(now: Date) -> SyncState {
        let kept = SyncSnapshot(device: deviceID, deleted: deleted, replaced: replaced)
            .pruned(now: now.syncMilliseconds, sessions: Array(peers.values), learned: learned)
        var copy = self
        copy.deleted = kept.deleted
        copy.learned = learned.filter { kept.deleted[$0.key] != nil }
        copy.replaced = kept.replaced
        let forgotten = now.syncMilliseconds - Int64(SyncSnapshot.peersKeptDays) * 86_400_000
        copy.peers = peers.filter { $0.value >= forgotten }
        copy.profileStates = profileStates.mapValues { profile in
            var profile = profile
            profile.deleted = SyncSnapshot(device: deviceID, deleted: profile.deleted)
                .pruned(now: now.syncMilliseconds, sessions: Array(peers.values), learned: profile.learned).deleted
            profile.learned = profile.learned.filter { profile.deleted[$0.key] != nil }
            return profile
        }
        copy.household.deleted = SyncSnapshot(device: deviceID, deleted: household.deleted)
            .pruned(now: now.syncMilliseconds, sessions: Array(peers.values), learned: household.learned).deleted
        copy.household.learned = household.learned.filter { copy.household.deleted[$0.key] != nil }
        return copy
    }
}

/// A merge applied off the main actor (`AppModel.applySync`): the merged snapshot, every profile's library by profile ID
/// (lowercase; the main one's under `SyncProfile.main`), and the progress received for the items playing.
struct SyncOutcome: Sendable {
    let latest: SyncSnapshot
    let libraries: [String: UserLibrary]
    let parked: [String: HistoryEntry]
    /// The collections shared with every profile, as merged.
    let household: UserLibrary
}

/// What sync keeps for a profile other than the main one (docs/SYNC.md, "Profiles"), as `SyncState` keeps it for the
/// main one: the people passed on, the order of the rows, the hidden records of kinds this app does not know, and what was
/// deleted in that profile.
struct ProfileSyncState: Codable, Equatable {
    var people: [SyncPerson] = []
    var listPeople: [String: [SyncPerson]] = [:]
    var rowOrders: [SyncRows] = []
    var otherHidden: [SyncHidden] = []
    var deleted: [String: Int64] = [:]
    /// When this device learned each deletion made in that profile elsewhere (kept from then, as the main one's).
    var learned: [String: Int64] = [:]

    init() {}

    enum CodingKeys: String, CodingKey { case people, listPeople, rowOrders, otherHidden, deleted, learned }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        people = (try? c.decodeIfPresent([SyncPerson].self, forKey: .people)) ?? []
        listPeople = (try? c.decodeIfPresent([String: [SyncPerson]].self, forKey: .listPeople)) ?? [:]
        rowOrders = (try? c.decodeIfPresent([SyncRows].self, forKey: .rowOrders)) ?? []
        otherHidden = (try? c.decodeIfPresent([SyncHidden].self, forKey: .otherHidden)) ?? []
        deleted = (try? c.decodeIfPresent([String: Int64].self, forKey: .deleted)) ?? [:]
        learned = (try? c.decodeIfPresent([String: Int64].self, forKey: .learned)) ?? [:]
    }

    /// From a profile's records as merged (its bucket), after `previous`: a deletion it did not have is learned at `now`.
    init(_ bucket: SyncProfileData, previous: ProfileSyncState?, now: Int64) {
        people = bucket.people
        listPeople = SyncMapping.listPeople(bucket.records())
        rowOrders = bucket.rows
        otherHidden = SyncMapping.otherHidden(bucket.hiddenCategories)
        deleted = bucket.deleted
        learned = bucket.deleted.reduce(into: [:]) { learned, entry in
            if let known = previous?.learned[entry.key] {
                learned[entry.key] = known
            } else if previous?.deleted[entry.key] == nil {
                learned[entry.key] = now
            }
        }
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

    /// This device's snapshot, without the deletions `SyncSnapshot.pruned` forgets at `now` (on the sync clock,
    /// `AppModel.syncNow`). Its times are those stored: never moved.
    /// `library` is the main profile's; `others` the library of each other profile of `profiles`, by ID (lowercase): their
    /// records travel in `profileData` (docs/SYNC.md, "Profiles").
    /// `household`: the collections shared with every profile (docs/SYNC.md, "Shared lists").
    static func snapshot(playlists: [Playlist], library: UserLibrary, state: SyncState, tmdbKey: String? = nil, subtitleKeys: [SyncKey] = [],
                         deviceName: String?,
                         now: Date, profiles: [Profile] = [], others: [String: UserLibrary] = [:], household: UserLibrary = UserLibrary()) -> SyncSnapshot {
        withoutFilePlaylistHides(
            withHousehold(withProfiles(records(playlists: playlists, library: library, state: state, tmdbKey: tmdbKey, subtitleKeys: subtitleKeys,
                                               deviceName: deviceName, now: now),
                                       state: state, profiles: profiles, others: others, now: now), household: household, state: state, now: now),
            playlists: playlists
        )
            .pruned(now: now.syncMilliseconds, sessions: Array(state.peers.values), learned: state.learned,
                    profileLearned: state.profileStates.mapValues(\.learned), householdLearned: state.household.learned)
    }

    /// `snapshot` without the hidden categories of a file playlist: it is on this device only, as on Android.
    private static func withoutFilePlaylistHides(_ snapshot: SyncSnapshot, playlists: [Playlist]) -> SyncSnapshot {
        let files = Set(playlists.compactMap { playlist -> String? in
            if case .local = playlist.source { return playlist.id.uuidString.lowercased() }
            return nil
        })
        guard !files.isEmpty else { return snapshot }
        func travel(_ records: [SyncHidden]) -> [SyncHidden] { records.filter { $0.playlist.map { !files.contains($0) } ?? true } }
        var snapshot = snapshot
        snapshot.hiddenCategories = travel(snapshot.hiddenCategories)
        for index in snapshot.profileData.indices {
            snapshot.profileData[index].hiddenCategories = travel(snapshot.profileData[index].hiddenCategories)
        }
        return snapshot
    }

    /// `snapshot` with the collections shared with every profile; none (no household, as before) when there are none.
    private static func withHousehold(_ snapshot: SyncSnapshot, household: UserLibrary, state: SyncState, now: Date) -> SyncSnapshot {
        var snapshot = snapshot
        let shared = SyncHousehold(records: records(playlists: [], library: household, state: state.householdSlice(), deviceName: nil, now: now))
        snapshot.household = shared.isEmpty ? nil : shared
        return snapshot
    }

    /// The latest time of what this device holds for sync (its records and deletions, every profile's); nil without any.
    static func latestTime(playlists: [Playlist], library: UserLibrary, state: SyncState, profiles: [Profile] = [],
                           others: [String: UserLibrary] = [:], household: UserLibrary = UserLibrary()) -> Int64? {
        let epoch = Date(timeIntervalSince1970: 0)
        return withHousehold(withProfiles(records(playlists: playlists, library: library, state: state, deviceName: nil, now: epoch),
                                          state: state, profiles: profiles, others: others, now: epoch),
                             household: household, state: state, now: epoch).latestTime
    }

    /// `snapshot` with the profiles and the records of each profile other than the main one. A profile never changed (the
    /// main one as made) is not sent: the same "Me" made on two devices is no change.
    private static func withProfiles(_ snapshot: SyncSnapshot, state: SyncState, profiles: [Profile], others: [String: UserLibrary],
                                     now: Date) -> SyncSnapshot {
        var snapshot = snapshot
        snapshot.profiles = profiles.filter { $0.updatedAt > 0 }.map {
            SyncProfile(id: $0.id.uuidString.lowercased(), name: $0.name, colorIndex: $0.colorIndex, symbol: $0.symbol, kids: $0.isKids,
                        updatedAt: $0.updatedAt)
        }.sorted { $0.id < $1.id }
        snapshot.profileData = profiles.filter { !$0.isMain }.compactMap { profile -> SyncProfileData? in
            let id = profile.id.uuidString.lowercased()
            guard let library = others[id] else { return nil }
            return SyncProfileData(profile: id, records: records(playlists: [], library: library, state: state.slice(for: id), deviceName: nil, now: now))
        }.sorted { $0.profile < $1.profile }
        return snapshot
    }

    private static func records(playlists: [Playlist], library: UserLibrary, state: SyncState, tmdbKey: String? = nil,
                                subtitleKeys: [SyncKey] = [], deviceName: String?,
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
        // `listID|itemKey`: the list's ID and the item's playlist in lowercase.
        for (key, date) in library.listItemTombstones {
            guard let bar = key.firstIndex(of: "|") else { continue }
            delete(SyncList.itemPrefix + key[..<bar].lowercased() + "|" + lowercasingPlaylistID(String(key[key.index(after: bar)...])), at: date)
        }
        // A category, tag or word shown again (docs/SYNC.md, "Hidden categories").
        for (key, date) in library.categoryShownAt { delete(hiddenCategoryKey(key), at: date) }
        for (tag, date) in library.shownCategoryTags { delete(hiddenKey(SyncHidden.tag, tag), at: date) }
        for (word, date) in library.shownCategoryWords { delete(hiddenKey(SyncHidden.word, word), at: date) }
        return SyncSnapshot(
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
            lists: library.customLists.map { list -> SyncList in
                let id = list.id.uuidString.lowercased()
                return SyncList(id: id, name: list.name, items: list.items.map { item in
                    SyncListItem(playlist: item.reference.playlistID.uuidString.lowercased(), kind: item.reference.type.rawValue,
                                 item: item.reference.itemID, title: item.title, image: item.imageURL?.absoluteString,
                                 addedAt: item.addedAt.syncMilliseconds)
                }, people: state.listPeople[id] ?? [], updatedAt: list.updatedAt.syncMilliseconds,
                   renamedAt: list.renamedAt?.syncMilliseconds, orderedAt: list.orderedAt?.syncMilliseconds,
                   placedAt: list.placedAt?.syncMilliseconds)
            },
            people: state.people,
            keys: keys(tmdbKey: tmdbKey, subtitleKeys: subtitleKeys, state: state),
            rows: state.rowOrders,
            hiddenCategories: hiddenRecords(library, others: state.otherHidden),
            deleted: deleted,
            replaced: state.replaced,
            clock: now.syncMilliseconds
        )
    }

    // MARK: Hidden categories (docs/SYNC.md, "Hidden categories")

    static let knownHiddenKinds: Set<String> = [SyncHidden.category, SyncHidden.tag, SyncHidden.word]

    /// The records of kinds this app does not know: kept to pass them on.
    static func otherHidden(_ records: [SyncHidden]) -> [SyncHidden] {
        records.filter { !knownHiddenKinds.contains($0.kind) }
    }

    /// `hide:<kind>:<value>`.
    static func hiddenKey(_ kind: String, _ value: String) -> String {
        SyncHidden.keyPrefix + kind + ":" + value
    }

    /// The sync key of a hidden category as the library keeps it (`<playlist UUID>|<category>`): the playlist in lowercase.
    static func hiddenCategoryKey(_ local: String) -> String {
        SyncHidden.categoryKeyPrefix + lowercasingPlaylistID(local)
    }

    /// What a profile hides, as sync records: its categories, tags and words, each with the time it was hidden (0 when it has
    /// none), and `others`, the records of kinds this app does not know.
    static func hiddenRecords(_ library: UserLibrary, others: [SyncHidden]) -> [SyncHidden] {
        var records: [SyncHidden] = []
        for key in library.hiddenCategoryKeys where key.contains("|") {
            records.append(SyncHidden(kind: SyncHidden.category, value: lowercasingPlaylistID(key),
                                      hiddenAt: library.categoryHiddenAt[key]?.syncMilliseconds ?? 0))
        }
        for (tag, date) in library.hiddenCategoryTags {
            records.append(SyncHidden(kind: SyncHidden.tag, value: tag, hiddenAt: date.syncMilliseconds))
        }
        for (word, date) in library.hiddenCategoryWords {
            records.append(SyncHidden(kind: SyncHidden.word, value: word, hiddenAt: date.syncMilliseconds))
        }
        records += otherHidden(others)
        return records.sorted { $0.key < $1.key }
    }

    /// `base` with the hidden categories, tags and words after the merge `snapshot`: those it holds (the merge holds this
    /// device's own), and this device's own the merge did not carry (past its limit) unless shown again since. One shown
    /// again elsewhere keeps the time it was, as this device's own showing again does. `keepingHides` (a Kids profile on a
    /// device where a parent can unlock): this device's own stay, whatever was shown again elsewhere, with their own times
    /// (the merge keeps the later showing again, and nothing changes from one session to the next); a hide received still
    /// applies.
    static func applyingHidden(_ base: UserLibrary, _ snapshot: SyncSnapshot, keepingHides: Bool = false) -> UserLibrary {
        var library = base
        func removal(_ key: String, _ date: Date?) -> Date? {
            guard !keepingHides, let removed = SyncHidden.shownAt(key, in: snapshot.deleted), removed >= (date?.syncMilliseconds ?? 0) else {
                return nil
            }
            return Date(syncMilliseconds: removed)
        }
        for key in base.hiddenCategoryKeys {
            guard let shown = removal(hiddenCategoryKey(key), base.categoryHiddenAt[key]) else { continue }
            library.hiddenCategoryKeys.remove(key)
            library.categoryHiddenAt[key] = nil
            library.categoryShownAt[key] = shown
        }
        for (tag, date) in base.hiddenCategoryTags {
            guard let shown = removal(hiddenKey(SyncHidden.tag, tag), date) else { continue }
            library.hiddenCategoryTags[tag] = nil
            library.shownCategoryTags[tag] = shown
        }
        for (word, date) in base.hiddenCategoryWords {
            guard let shown = removal(hiddenKey(SyncHidden.word, word), date) else { continue }
            library.hiddenCategoryWords[word] = nil
            library.shownCategoryWords[word] = shown
        }
        for record in snapshot.hiddenCategories {
            let date = Date(syncMilliseconds: record.hiddenAt)
            switch record.kind {
            case SyncHidden.category:
                guard let bar = record.value.firstIndex(of: "|"), let playlistID = UUID(uuidString: String(record.value[..<bar])) else { continue }
                let key = UserLibrary.categoryKey(playlistID: playlistID, categoryID: String(record.value[record.value.index(after: bar)...]))
                library.hiddenCategoryKeys.insert(key)
                library.categoryHiddenAt[key] = max(library.categoryHiddenAt[key] ?? date, date)
                library.categoryShownAt[key] = nil
            case SyncHidden.tag:
                library.hiddenCategoryTags[record.value] = max(library.hiddenCategoryTags[record.value] ?? date, date)
                library.shownCategoryTags[record.value] = nil
            case SyncHidden.word:
                library.hiddenCategoryWords[record.value] = max(library.hiddenCategoryWords[record.value] ?? date, date)
                library.shownCategoryWords[record.value] = nil
            default:
                continue
            }
        }
        // The dates of what was shown again go once sync no longer carries them (`SyncSnapshot.pruned`).
        library.categoryShownAt = library.categoryShownAt.filter { snapshot.deleted[hiddenCategoryKey($0.key)] != nil }
        library.shownCategoryTags = library.shownCategoryTags.filter { snapshot.deleted[hiddenKey(SyncHidden.tag, $0.key)] != nil }
        library.shownCategoryWords = library.shownCategoryWords.filter { snapshot.deleted[hiddenKey(SyncHidden.word, $0.key)] != nil }
        return library
    }

    /// Every category, tag and word `library` hides, dated `time` (docs/PROFILES.md, "Migration").
    static func datingHidden(_ library: UserLibrary, at time: Date) -> UserLibrary {
        var dated = library
        for key in dated.hiddenCategoryKeys { dated.categoryHiddenAt[key] = time }
        for tag in dated.hiddenCategoryTags.keys { dated.hiddenCategoryTags[tag] = time }
        for word in dated.hiddenCategoryWords.keys { dated.hiddenCategoryWords[word] = time }
        return dated
    }

    /// The people of each list of `snapshot` that has any, by list ID: kept to be sent with the list again.
    static func listPeople(_ snapshot: SyncSnapshot) -> [String: [SyncPerson]] {
        Dictionary(snapshot.lists.filter { !$0.people.isEmpty }.map { ($0.id, $0.people) }, uniquingKeysWith: { first, _ in first })
    }

    /// The keychain's TMDb key, dated when it was saved (0 only before `loadSyncState` dated it), the subtitle services'
    /// fields (`AppModel.subtitleSyncKeys`), and the keys passed on for the services this app does not use.
    static func keys(tmdbKey: String?, subtitleKeys: [SyncKey] = [], state: SyncState) -> [SyncKey] {
        var keys = state.otherKeys.filter { !isOwnKey($0.service) }
        if let tmdbKey, !tmdbKey.isEmpty {
            keys.append(SyncKey(service: SyncKey.tmdb, value: tmdbKey, updatedAt: state.tmdbKeyUpdatedAt ?? 0))
        }
        keys += subtitleKeys
        return keys
    }

    /// A key this app keeps itself (TMDb's, the subtitle services'), never among those it only passes on.
    static func isOwnKey(_ service: String) -> Bool {
        service == SyncKey.tmdb || SyncKey.subtitleServices.contains(service)
    }

    /// A password from another device as it can be used: not empty, no control characters (spaces are part of it).
    static func isUsablePassword(_ value: String) -> Bool {
        !value.isEmpty && value.rangeOfCharacter(from: .controlCharacters) == nil
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

    /// `base` with My List and favourite channels, progress and lists replaced by the snapshot's, and its hidden categories,
    /// tags and words merged (`applyingHidden`). What sync does not carry stays: live channel history, hidden channels,
    /// reminders, the order of My List, the containers of episodes. Progress not later than "Clear Watch History" stays
    /// cleared.
    static func library(_ base: UserLibrary, applying snapshot: SyncSnapshot, keepingHides: Bool = false) -> UserLibrary {
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
                entry.addedAt = item.addedAt.map { Date(syncMilliseconds: $0) } ?? entry.addedAt
                entry.title = item.title
                entry.imageURL = image(item.image, keeping: entry.imageURL)
                entry.sortIndex = seenItems.count - 1
                return entry
            }
            // A merged list always states when it was renamed and put in order; a list of this device without those
            // times (an older version's) means its `updatedAt`. When that is what the record states, and the list was
            // not edited since, it stays as it is: applying its own snapshot changes nothing.
            let unchanged = listsByID[id].map { $0.updatedAt.syncMilliseconds == record.updatedAt } ?? false
            func time(_ value: Int64?, here: Date?) -> Date? {
                guard let value else { return nil }
                return unchanged && here == nil && value == record.updatedAt ? nil : Date(syncMilliseconds: value)
            }
            lists[id] = CustomList(id: id, name: record.name, items: items, updatedAt: updatedAt,
                                   renamedAt: time(record.renamedAt, here: listsByID[id]?.renamedAt),
                                   orderedAt: time(record.orderedAt, here: listsByID[id]?.orderedAt),
                                   placedAt: record.placedAt.map { Date(syncMilliseconds: $0) })
            order.append(id)
        }
        // The device's lists keep their place; new ones come after them.
        let kept = base.customLists.map(\.id).filter { lists[$0] != nil }
        library.customLists = (kept + order.filter { listsByID[$0] == nil }).compactMap { lists[$0] }
        return applyingHidden(library, snapshot, keepingHides: keepingHides)
    }

    /// `applied` with the entries of the items `playing` (by `playbackKey`) as they are in `local`; the entries received
    /// for them are returned as `parked`.
    static func keepingPlayback(_ applied: UserLibrary, local: UserLibrary, playing: Set<String>) -> (library: UserLibrary, parked: [String: HistoryEntry]) {
        var library = applied
        var parked: [String: HistoryEntry] = [:]
        for key in playing {
            let mine = local.history.first { $0.id == key }
            let received = library.history.first { $0.id == key }
            guard received != mine else { continue }
            if let received { parked[key] = received }
            library.history.removeAll { $0.id == key }
            if let mine { library.history.append(mine) }
            library.history.sort { ($0.lastWatchedAt, $0.id) > ($1.lastWatchedAt, $1.id) }
        }
        return (library, parked)
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
    /// The main profile's library, and the library of each other profile by ID (lowercase): the open one's as it is, the
    /// others' as saved (a switch writes the one left first).
    func syncLibraries() -> (main: UserLibrary, others: [String: UserLibrary]) {
        let opened = library
        let openedID = activeProfile.id
        // From memory: no library is read or decoded at a session (`otherLibraries`).
        func stored(_ profile: Profile) -> UserLibrary {
            profile.id == openedID ? opened : (otherLibraries[profile.id] ?? UserLibrary())
        }
        let main = profiles.first(where: \.isMain).map(stored) ?? (activeProfile.isMain ? opened : UserLibrary())
        var others: [String: UserLibrary] = [:]
        for profile in profiles where !profile.isMain { others[profile.id.uuidString.lowercased()] = stored(profile) }
        return (main, others)
    }

    /// The name other devices list this one under: the kind of device, never the name the owner gave it.
    static var syncDeviceName: String {
        #if os(iOS)
        return UIDevice.current.model
        #else
        return "Mac"
        #endif
    }

    /// `now` (the wall clock) on the sync clock: the later of it and the latest time sync dated or received.
    func syncSnapshot(now: Date = Date()) -> SyncSnapshot {
        let libraries = syncLibraries()
        return SyncMapping.snapshot(playlists: playlists, library: libraries.main, state: syncState, tmdbKey: secrets.get(Keychain.tmdbKey),
                                    subtitleKeys: subtitleSyncKeys(), deviceName: Self.syncDeviceName, now: syncNow(now), profiles: profiles,
                                    others: libraries.others, household: household)
    }

    /// The time of a change sync carries, made at `wall` (docs/SYNC.md, "Clocks"): later than everything this device
    /// dated or received before, whatever its wall clock says.
    func syncStamp(_ wall: Date = Date()) -> Date {
        Date(syncMilliseconds: syncClock.stamp(wall: wall.syncMilliseconds))
    }

    /// `wall` as sync dates it (`SyncClock.now`): to prune by and to note sessions, never earlier than a time it gave.
    func syncNow(_ wall: Date = Date()) -> Date {
        Date(syncMilliseconds: syncClock.now(wall: wall.syncMilliseconds))
    }

    /// The sync clock starts after every time this device holds (`sync.json`, and every profile's library): after a
    /// restart with its clock back at 1970, a change is still dated after the ones it made or received before.
    func advanceSyncClockToStoredData() {
        syncClock.advance(to: syncState.clock)
        syncClock.advance(to: syncState.tmdbKeyUpdatedAt)
        syncClock.advance(to: syncState.subtitleKeysUpdatedAt.values.max())
        syncClock.advance(to: syncState.rowOrders.map(\.updatedAt).max())
        syncClock.advance(to: profiles.map(\.updatedAt).max())
        let libraries = syncLibraries()
        syncClock.advance(to: SyncMapping.latestTime(playlists: playlists, library: libraries.main, state: syncState, profiles: profiles,
                                                     others: libraries.others, household: household))
    }

    /// The snapshot to send, or nil when this device does not sync now (not started).
    func syncSnapshotForSending() -> SyncSnapshot? {
        guard isReady else { return nil }
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

    /// Starts or stops looking for devices: only while sync is on and the app in the foreground (every profile syncs).
    func refreshLocalSync() {
        if isReady, settings.syncOnLocalNetwork, isInForeground {
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
        advanceSyncClockToStoredData()
        if syncState.tmdbKeyUpdatedAt == nil, secrets.get(Keychain.tmdbKey) != nil {
            syncState.tmdbKeyUpdatedAt = syncStamp(now).syncMilliseconds
            changed = true
        }
        // The subtitle services' fields saved before sync carried them, the same way (and Addic7ed once turned on).
        for field in SubtitleCredential.allCases where syncState.subtitleKeysUpdatedAt[field.syncService] == nil && subtitleCredential(field) != nil {
            syncState.subtitleKeysUpdatedAt[field.syncService] = syncStamp(now).syncMilliseconds
            changed = true
        }
        if settings.addic7edOptIn, syncState.subtitleKeysUpdatedAt[SyncKey.addic7ed] == nil {
            syncState.subtitleKeysUpdatedAt[SyncKey.addic7ed] = syncStamp(now).syncMilliseconds
            changed = true
        }
        // Subtitle keys an earlier version kept among those it only passed on (in sync.json): moved to the keychain once
        // (the later one kept), and gone from sync.json.
        let relayed = syncState.otherKeys.filter { SyncKey.subtitleServices.contains($0.service) }
        if !relayed.isEmpty {
            applySyncedSubtitleKeys(SyncSnapshot(device: syncState.deviceID, keys: relayed))
            syncState.otherKeys.removeAll { SyncKey.subtitleServices.contains($0.service) }
            changed = true
        }
        // A profile made before profiles synced has no time: it gets one now, so that it travels.
        if profiles.contains(where: { !$0.isMain && $0.updatedAt == 0 }) {
            profiles = profiles.map { profile in
                guard !profile.isMain, profile.updatedAt == 0 else { return profile }
                var stamped = profile
                stamped.updatedAt = syncStamp(now).syncMilliseconds
                return stamped
            }
            try? userStore.save(profiles, name: "profiles")
            changed = true
        }
        // The categories, languages and styles each profile hid before they synced are dated once, now, after everything
        // this device holds (their earlier dates, and the times some were shown again, included): at the first session two
        // devices' hidden sets add up, and nothing is shown again (docs/PROFILES.md, "Migration").
        if !syncState.hiddenDated {
            syncState.hiddenDated = true
            changed = true
            let libraries = [(activeProfile, library)] + profiles.filter { $0.id != activeProfile.id }.map { ($0, otherLibraries[$0.id] ?? UserLibrary()) }
            if libraries.contains(where: { !$0.1.hiddenCategoryKeys.isEmpty || !$0.1.hiddenCategoryTags.isEmpty || !$0.1.hiddenCategoryWords.isEmpty }) {
                let time = syncStamp(now)
                for (profile, stored) in libraries {
                    let dated = SyncMapping.datingHidden(stored, at: time)
                    guard dated != stored else { continue }
                    if profile.id == activeProfile.id { updateLibrary { $0 = dated } } else { otherLibraries[profile.id] = dated }
                    try? userStore.save(dated, name: profile.libraryDocumentName)
                }
            }
        }
        if changed { writeSyncState(now: now) }
    }

    /// Remembers for sync that `key` was deleted in the open profile, at `time` (docs/SYNC.md, "Profiles").
    func noteDeletedForSync(_ key: String, at time: Int64) {
        let id = activeProfile.id.uuidString.lowercased()
        if activeProfile.isMain {
            syncState.deleted[key] = max(syncState.deleted[key] ?? time, time)
        } else {
            var profile = syncState.profileStates[id] ?? ProfileSyncState()
            profile.deleted[key] = max(profile.deleted[key] ?? time, time)
            syncState.profileStates[id] = profile
        }
    }

    /// Not before `bootstrap()` has read `sync.json`: a new device ID and no deletions would replace this device's.
    func saveSyncState(now: Date = Date()) {
        guard isReady else { return }
        writeSyncState(now: now)
    }

    private func writeSyncState(now: Date) {
        syncState.clock = max(syncState.clock, syncClock.last)
        syncState = syncState.pruned(now: syncNow(now))
        try? userStore.save(syncState, name: "sync")
    }

    /// A playlist was added or its name, address, login or guide edited.
    func notePlaylistChangedForSync(_ id: UUID, now: Date = Date()) {
        syncState.playlistUpdatedAt[id.uuidString.lowercased()] = syncStamp(now).syncMilliseconds
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
        let time = syncStamp(now).syncMilliseconds
        for playlist in restored {
            syncState.playlistUpdatedAt[playlist.id.uuidString.lowercased()] = time
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
        syncState.deleted["playlist:" + id] = syncStamp(now).syncMilliseconds
        saveSyncState(now: now)
        syncSoon()
    }

    func noteListDeletedForSync(_ id: UUID, now: Date = Date()) {
        noteDeletedForSync("list:" + id.uuidString.lowercased(), at: syncStamp(now).syncMilliseconds)
        saveSyncState(now: now)
        syncSoon()
    }

    /// "Clear Watch History" leaves no deletion marker in the library: the movies and episodes it cleared are marked
    /// here, so other devices clear them too.
    func noteHistoryClearedForSync(_ entries: [HistoryEntry], now: Date = Date()) {
        let time = syncStamp(now).syncMilliseconds
        for entry in entries where entry.reference.type != .live {
            noteDeletedForSync("progress:" + SyncMapping.lowercasingPlaylistID(entry.id), at: time)
        }
        saveSyncState(now: now)
        syncSoon()
    }

    /// Keeps the merged snapshot a device answered with (docs/SYNC.md, end of "Merge"): new playlists are added (and
    /// their catalogue downloaded), changed ones updated, deleted ones removed, My List, favourite channels, lists and
    /// progress replaced, and the TMDb key saved or removed. Merged again with the current state first, so nothing
    /// changed during the session is lost (and an answer without keys removes none).
    /// Every profile's data is applied to that profile (docs/SYNC.md, "Profiles"). Returns whether the libraries were
    /// applied: not when this device's data kept changing during the merge (the next session merges then).
    @discardableResult
    func applySync(_ received: SyncSnapshot, now: Date = Date()) async -> Bool {
        guard isReady else { return false }
        // Without what is dated more than a day after this device's clock; the rest goes into its sync clock, so that a
        // change made here next is dated after it (docs/SYNC.md, "Clocks"). No time is moved. Merged off the main actor:
        // the merge that is kept is the one with the data as it is at the end (below), on the main actor.
        let taken = matchingProfilesOnce(syncClock.receive(received, wall: now.syncMilliseconds))
        let local = syncSnapshot(now: now)
        let merged = await Task.detached(priority: .utility) { SyncMerge.merge(local, taken) }.value
        let records = Dictionary(merged.playlists.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        await applySyncedPlaylistRecords(merged)

        // Playlists deleted elsewhere, or that turned out to be another one (the same account added twice).
        for local in playlists {
            let id = local.id.uuidString.lowercased()
            if case .local = local.source { continue }
            guard records[id] == nil, let deletedAt = merged.deleted["playlist:" + id] else { continue }
            // Edited here after the deletion (while sync was busy): the edit wins, as the merge would have it.
            if let edited = syncState.playlistUpdatedAt[id], edited > deletedAt { continue }
            let replacement = replacementID(of: id, in: merged).flatMap { newID in
                playlists.first { $0.id.uuidString.lowercased() == newID }
            }
            if let replacement {
                carryDeviceSettings(from: local.id, to: replacement.id)
                // The same account under another ID keeps its catalogue, guide and details: nothing is downloaded again.
                cacheStore.move(name: "content-\(local.id.uuidString)", to: "content-\(replacement.id.uuidString)")
                cacheStore.move(name: "epg-\(local.id.uuidString)", to: "epg-\(replacement.id.uuidString)")
                await storedDetailsWrite?.value
                cacheStore.move(name: DetailsArchive.documentName(for: local.id), to: DetailsArchive.documentName(for: replacement.id))
            }
            if let replacement, activePlaylist?.id == local.id {
                updateSettings { $0.activePlaylistID = replacement.id }
                await deletePlaylist(local, recordsSyncDeletion: false)
                // Nothing is reloaded under the player: the catalogue follows once playback ends (`endSession`).
                if sessions.isEmpty { await activate(replacement) } else { contentAfterPlayback = .activate }
            } else {
                await deletePlaylist(local, recordsSyncDeletion: false)
            }
        }

        // The libraries, merged again with what changed while the playlists were applied, off the main actor; kept only
        // if nothing they read changed meanwhile, else merged again (the next session merges after a few tries).
        var outcome: SyncOutcome?
        for _ in 0..<Self.syncMergeTries {
            let libraries = syncLibraries()
            let (base, bases, state, tmdbKey, playing, people) = (library, playlists, syncState, secrets.get(Keychain.tmdbKey), playingKeys, profiles)
            let subtitleKeys = subtitleSyncKeys()
            let shared = household
            let generation = otherLibrariesGeneration
            let activeID = activeProfile.isMain ? SyncProfile.main : activeProfile.id.uuidString.lowercased()
            let owners = playbackProfiles.mapValues { owner in owner == Profile.defaultID ? SyncProfile.main : owner.uuidString.lowercased() }
            let name = Self.syncDeviceName
            let syncTime = syncNow(now)
            // The Kids profiles of a device where a parent can unlock keep what they hide (docs/PROFILES.md, "Kids").
            let keepingHides = kidsKeepingHides()
            let result = await Task.detached(priority: .utility) { () -> SyncOutcome in
                let current = SyncMapping.snapshot(playlists: bases, library: libraries.main, state: state, tmdbKey: tmdbKey,
                                                   subtitleKeys: subtitleKeys, deviceName: name,
                                                   now: syncTime, profiles: people, others: libraries.others, household: shared)
                let latest = SyncMerge.merge(current, merged)
                var applied: [String: UserLibrary] = [
                    SyncProfile.main: SyncMapping.library(libraries.main, applying: latest, keepingHides: keepingHides.contains(SyncProfile.main)),
                ]
                for profile in latest.profiles where profile.id != SyncProfile.main {
                    let bucket = latest.profileData.first { $0.profile == profile.id } ?? SyncProfileData(profile: profile.id)
                    applied[profile.id] = SyncMapping.library(libraries.others[profile.id] ?? UserLibrary(), applying: bucket.records(),
                                                              keepingHides: keepingHides.contains(profile.id))
                }
                // The items playing here keep their entry as the player left it, in the profile each plays in.
                var parked: [String: HistoryEntry] = [:]
                for (id, library) in applied {
                    let keys = playing.filter { (owners[$0] ?? activeID) == id }
                    guard !keys.isEmpty else { continue }
                    let local = id == activeID ? base : (id == SyncProfile.main ? libraries.main : (libraries.others[id] ?? UserLibrary()))
                    let kept = SyncMapping.keepingPlayback(library, local: local, playing: keys)
                    applied[id] = kept.library
                    parked.merge(kept.parked) { _, received in received }
                }
                // The collections shared with every profile, by the same rules as another profile's.
                let household = SyncMapping.library(shared, applying: (latest.household ?? SyncHousehold()).records())
                return SyncOutcome(latest: latest, libraries: applied, parked: parked, household: household)
            }.value
            #if DEBUG
            if let change = afterSyncMerge {
                afterSyncMerge = nil
                change()
            }
            #endif
            // Kept only if nothing it read changed meanwhile: the open library, and the others too (review S4).
            if library == base, playlists == bases, syncState == state, playingKeys == playing, profiles == people,
               otherLibrariesGeneration == generation, household == shared {
                outcome = result
                break
            }
        }
        guard let outcome else {
            saveSyncState()
            return false
        }
        let latest = outcome.latest
        applySyncedProfiles(latest, libraries: outcome.libraries)
        // What came for the items playing here waits for playback to end.
        parkedProgress.merge(outcome.parked) { _, received in received }
        let activeID = activeProfile.id.uuidString.lowercased()
        if let opened = outcome.libraries[activeID], opened != library { updateLibrary { $0 = opened } }
        applyParkedProgress()
        applySyncedKeys(latest)
        if outcome.household != household {
            household = outcome.household
            try? userStore.save(household, name: Self.householdDocumentName)
        }
        let sharedLists = latest.household ?? SyncHousehold()
        syncState.household = ProfileSyncState(SyncProfileData(profile: "household", lists: sharedLists.lists, deleted: sharedLists.deleted),
                                               previous: syncState.household, now: syncNow(now).syncMilliseconds)
        syncState.people = latest.people
        syncState.rowOrders = latest.rows
        syncState.otherHidden = SyncMapping.otherHidden(latest.hiddenCategories)
        syncState.listPeople = SyncMapping.listPeople(latest)
        let time = syncNow(now).syncMilliseconds
        let previousStates = syncState.profileStates
        syncState.profileStates = Dictionary(latest.profileData.map { bucket in
            (bucket.profile, ProfileSyncState(bucket, previous: previousStates[bucket.profile], now: time))
        }, uniquingKeysWith: { first, _ in first })
        syncState.learned = latest.deleted.reduce(into: [:]) { learned, entry in
            if let known = syncState.learned[entry.key] {
                learned[entry.key] = known
            } else if syncState.deleted[entry.key] == nil {
                learned[entry.key] = time
            }
        }
        syncState.deleted = latest.deleted
        syncState.replaced = latest.replaced
        syncState.lastSyncedAt = now.syncMilliseconds
        saveSyncState()
        return true
    }

    /// The Kids profiles (by sync ID, the main one as `SyncProfile.main`) whose hidden categories, languages and styles a
    /// showing again from another device does not lift: all of them on a device where a parent set a PIN, as their Kids
    /// switch (docs/PROFILES.md, "Kids"); none elsewhere.
    func kidsKeepingHides() -> Set<String> {
        guard hasParentalPIN || profiles.contains(where: { !$0.isKids && $0.isProtected }) else { return [] }
        return Set(profiles.filter(\.isKids).map { $0.isMain ? SyncProfile.main : $0.id.uuidString.lowercased() })
    }

    /// The profiles of `merged` (docs/SYNC.md, "Profiles"): each keeps its PIN (it stays on this device); new ones are
    /// added without one; one deleted elsewhere goes with its library (never the main one). The libraries of the
    /// profiles not open are written here; the open one's by the caller.
    private func applySyncedProfiles(_ merged: SyncSnapshot, libraries: [String: UserLibrary]) {
        let records = Dictionary(merged.profiles.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        // Kids lifted elsewhere stays on where a parent can unlock (as that PIN stays here): docs/PROFILES.md, "Kids".
        let parentProtected = hasParentalPIN || profiles.contains { !$0.isKids && $0.isProtected }
        var kept: [Profile] = []
        var removed: [Profile] = []
        for profile in profiles {
            let id = profile.id.uuidString.lowercased()
            if let record = records[id] {
                var updated = profile
                if record.updatedAt >= profile.updatedAt {
                    updated.name = record.name
                    updated.colorIndex = record.colorIndex
                    updated.symbol = record.symbol
                    updated.isKids = SyncProfiles.keptKids(local: profile.isKids, received: record.kids, parentProtected: parentProtected)
                    updated.updatedAt = record.updatedAt
                }
                kept.append(updated)
            } else if !profile.isMain, let deletedAt = merged.deleted[SyncProfile.keyPrefix + id], deletedAt >= profile.updatedAt {
                removed.append(profile)
            } else {
                kept.append(profile)
            }
        }
        let known = Set(profiles.map { $0.id.uuidString.lowercased() })
        for record in merged.profiles where !known.contains(record.id) && record.id != SyncProfile.main {
            guard let id = UUID(uuidString: record.id) else { continue }
            kept.append(Profile(id: id, name: record.name, colorIndex: record.colorIndex, symbol: record.symbol, isKids: record.kids,
                                updatedAt: record.updatedAt))
        }
        let openedRemoved = removed.contains { $0.id == activeProfile.id }
        if kept != profiles {
            profiles = kept
            saveProfiles()
        }
        // Only the libraries whose data changed are written.
        for profile in kept where profile.id != activeProfile.id || openedRemoved {
            let id = profile.isMain ? SyncProfile.main : profile.id.uuidString.lowercased()
            guard let library = libraries[id], library != otherLibraries[profile.id] else { continue }
            otherLibraries[profile.id] = library
            try? userStore.save(library, name: profile.libraryDocumentName)
        }
        for profile in removed {
            libraryWriter.discard(name: profile.libraryDocumentName)
            userStore.remove(name: profile.libraryDocumentName)
            otherLibraries[profile.id] = nil
            if settings.launchProfileID == profile.id { updateSettings { $0.launchProfileID = nil } }
        }
        // The open profile was deleted elsewhere: the main one opens.
        if openedRemoved, let main = profiles.first(where: \.isMain) {
            let opened = otherLibraries.removeValue(forKey: main.id) ?? (libraries[SyncProfile.main] ?? UserLibrary())
            updateSettings { $0.activeProfileID = main.id }
            updateLibrary { $0 = opened }
            isParentalUnlocked = false
        }
    }

    /// The playlists of `merged` kept here: those edited elsewhere updated, then the new ones added.
    private func applySyncedPlaylistRecords(_ merged: SyncSnapshot) async {
        // Playlists edited elsewhere, then the new ones. The device's own are found by ID (a snapshot can hold tens of
        // thousands), and found again after an update, which waits and lets other changes in.
        func byID() -> [UUID: Playlist] { Dictionary(playlists.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first }) }
        var locals = byID()
        var added: [Playlist] = []
        for record in merged.playlists {
            guard let id = UUID(uuidString: record.id) else { continue }
            // Edited here while sync was busy with another playlist: the edit is later than the merged record (it is
            // dated after everything received), so it stays and goes to the other devices next.
            if let edited = syncState.playlistUpdatedAt[record.id], edited > record.updatedAt { continue }
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
    }

    /// Merges of the library in the background before giving up until the next session, when the data keeps changing.
    static let syncMergeTries = 3

    /// A session with `device` worked: deletions are kept until each device has had a session after them.
    func noteSyncSession(with device: String, now: Date = Date()) {
        syncState.peers[device.lowercased()] = syncNow(now).syncMilliseconds
        saveSyncState()
    }

    /// The movies and episodes playing now (`playbackKey`): sync leaves their entries alone until playback ends.
    var playingKeys: Set<String> {
        Set(sessions.values.compactMap { session in session.current.flatMap { $0.isLive ? nil : $0.reference.playbackKey } })
    }

    /// The positions received during playback for items no longer playing: each applies when it is later than the
    /// entry here (and than its removal).
    func applyParkedProgress() {
        let playing = playingKeys
        let done = parkedProgress.filter { !playing.contains($0.key) }
        guard !done.isEmpty else { return }
        for key in done.keys { parkedProgress[key] = nil }
        // Each in the profile its item played in (review S5).
        for (key, received) in done {
            updatePlaybackLibrary(of: key) { library in
                if let mine = library.history.first(where: { $0.id == key }), mine.lastWatchedAt >= received.lastWatchedAt { return }
                if let removed = library.historyTombstones[key], removed >= received.lastWatchedAt { return }
                if let cleared = library.historyClearedAt, received.lastWatchedAt <= cleared { return }
                library.history.removeAll { $0.id == key }
                library.history.append(received)
                library.history.sort { ($0.lastWatchedAt, $0.id) > ($1.lastWatchedAt, $1.id) }
            }
        }
    }

    /// The first time this device receives profiles (docs/PROFILES.md, "Sync"): a profile of its own made apart, before
    /// profiles synced, is one with a received profile of the same name (whatever its case and accents) and the same Kids
    /// switch. The smaller ID is kept on both devices, the other is deleted (dated), and its data goes into the one kept:
    /// a received one into this device's profile, or this device's profile renamed to the received ID. Once only.
    func matchingProfilesOnce(_ incoming: SyncSnapshot) -> SyncSnapshot {
        guard !syncState.profilesMatched, !incoming.profiles.isEmpty else { return incoming }
        syncState.profilesMatched = true
        let local = profiles.filter { !$0.isMain }.map {
            SyncProfile(id: $0.id.uuidString.lowercased(), name: $0.name, colorIndex: $0.colorIndex, symbol: $0.symbol, kids: $0.isKids,
                        updatedAt: $0.updatedAt)
        }
        let localIDs = Set(local.map(\.id))
        var taken = incoming
        for pair in SyncProfiles.matches(local: local, received: incoming.profiles) {
            syncState.deleted[SyncProfile.keyPrefix + pair.dropped] = syncStamp().syncMilliseconds
            if localIDs.contains(pair.kept) {
                taken.profiles.removeAll { $0.id == pair.dropped }
                taken.profileData = taken.profileData.map { bucket in
                    var bucket = bucket
                    if bucket.profile == pair.dropped { bucket.profile = pair.kept }
                    return bucket
                }
            } else if let from = UUID(uuidString: pair.dropped), let to = UUID(uuidString: pair.kept) {
                renameProfile(from, to: to)
            }
        }
        saveSyncState()
        return taken
    }

    /// This device's profile `from` under the ID `to`, with its library, PIN, sync data and the settings naming it.
    private func renameProfile(_ from: UUID, to: UUID) {
        guard let index = profiles.firstIndex(where: { $0.id == from }) else { return }
        flush()
        let old = profiles[index]
        var renamed = old
        renamed.id = to
        // Its data, from memory, else from its document: never an empty library saved over it.
        let data = old.id == activeProfile.id ? library : (otherLibraries[from] ?? userStore.load(UserLibrary.self, name: old.libraryDocumentName))
        libraryWriter.discard(name: old.libraryDocumentName)
        if let data {
            try? userStore.save(data, name: renamed.libraryDocumentName)
            userStore.remove(name: old.libraryDocumentName)
            otherLibraries[from] = old.id == activeProfile.id ? nil : data
        } else {
            // Unreadable: the document is moved as it is, whatever it holds.
            userStore.move(name: old.libraryDocumentName, to: renamed.libraryDocumentName)
        }
        if let moved = otherLibraries.removeValue(forKey: from) { otherLibraries[to] = moved }
        let fromKey = from.uuidString.lowercased()
        if let state = syncState.profileStates.removeValue(forKey: fromKey) { syncState.profileStates[to.uuidString.lowercased()] = state }
        for (key, owner) in playbackProfiles where owner == from { playbackProfiles[key] = to }
        profiles[index] = renamed
        saveProfiles()
        updateSettings { settings in
            if settings.activeProfileID == from { settings.activeProfileID = to }
            if settings.launchProfileID == from { settings.launchProfileID = to }
        }
    }

    /// The keys of the merged snapshot: TMDb's saved when it changed (TMDb checked it on the device where it was typed),
    /// removed when it was removed elsewhere since it was saved here; the subtitle services' the same way
    /// (`applySyncedSubtitleKeys`); those of other services kept to pass on, never used.
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
        syncState.otherKeys = snapshot.keys.filter { !SyncMapping.isOwnKey($0.service) }
        applySyncedSubtitleKeys(snapshot)
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

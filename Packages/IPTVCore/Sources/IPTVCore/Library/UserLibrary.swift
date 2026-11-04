import Foundation

/// A user made list of channels / movies / series ("list manager").
public struct CustomList: Identifiable, Codable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var items: [FavoriteEntry]
    /// Last edit (sync keeps a list while it is later than the list's deletion).
    public var updatedAt: Date
    /// When the name was set, and when the items were put in order: sync merges each on its own (nil: `updatedAt`).
    public var renamedAt: Date?
    public var orderedAt: Date?
    /// When it was moved into the space it is in, shared with every profile or taken back into a profile (docs/SYNC.md,
    /// "Shared lists"); nil for a list never moved.
    public var placedAt: Date?

    public init(id: UUID = UUID(), name: String, items: [FavoriteEntry] = [], updatedAt: Date = Date(), renamedAt: Date? = nil,
                orderedAt: Date? = nil, placedAt: Date? = nil) {
        self.id = id
        self.name = name
        self.items = items
        self.updatedAt = updatedAt
        self.renamedAt = renamedAt
        self.orderedAt = orderedAt
        self.placedAt = placedAt
    }

    enum CodingKeys: String, CodingKey { case id, name, items, updatedAt, renamedAt, orderedAt, placedAt }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        items = (try? c.decodeIfPresent([FavoriteEntry].self, forKey: .items)) ?? []
        updatedAt = (try? c.decodeIfPresent(Date.self, forKey: .updatedAt)) ?? Date(timeIntervalSince1970: 0)
        renamedAt = try? c.decodeIfPresent(Date.self, forKey: .renamedAt)
        orderedAt = try? c.decodeIfPresent(Date.self, forKey: .orderedAt)
        placedAt = try? c.decodeIfPresent(Date.self, forKey: .placedAt)
    }

    public func contains(_ reference: MediaReference) -> Bool {
        items.contains { $0.reference.isSameItem(as: reference) }
    }
}

/// Favourites, history, resume positions and per-category preferences.
public struct UserLibrary: Codable, Hashable, Sendable {
    public var favorites: [FavoriteEntry]
    public var history: [HistoryEntry]
    /// `playlistID|categoryID`
    public var hiddenCategoryKeys: Set<String>
    /// `playlistID|categoryID` -> when it was hidden, and when it was shown again: dated markers so that showing a
    /// category again reaches the other copy in a merge (a key hidden before these dates existed has no date).
    public var categoryHiddenAt: [String: Date]
    public var categoryShownAt: [String: Date]
    /// `playlistID|categoryID` locked behind the parental PIN.
    public var lockedCategoryKeys: Set<String>
    public var customLists: [CustomList]
    /// Deletion markers so removals propagate when merging with another device.
    public var favoriteTombstones: [String: Date]
    /// `listID|itemKey` -> when the item was taken out of the list: sync sends it as a `listitem:` deletion.
    public var listItemTombstones: [String: Date]
    public var historyTombstones: [String: Date]
    public var historyClearedAt: Date?
    public var deletedListIDs: Set<UUID>
    /// Programmes to be notified about, and deletion markers for sync.
    public var reminders: [ProgramReminder]
    public var reminderTombstones: [String: Date]
    /// `playlistID|channelID` -> when it was hidden; unhiding leaves a dated marker for sync.
    public var hiddenChannels: [String: Date]
    public var unhiddenChannels: [String: Date]
    /// Category tags ("PT", see `CategoryTags`) hidden in every playlist -> when; showing one again leaves a
    /// dated marker for sync.
    public var hiddenCategoryTags: [String: Date]
    public var shownCategoryTags: [String: Date]
    /// Styles and words ("manga", "bollywood", see `CategoryStyles`) whose categories are hidden in every playlist
    /// -> when; showing one again leaves a dated marker for sync.
    public var hiddenCategoryWords: [String: Date]
    public var shownCategoryWords: [String: Date]

    public static let maxHistory = 500

    public init(
        favorites: [FavoriteEntry] = [],
        history: [HistoryEntry] = [],
        hiddenCategoryKeys: Set<String> = [],
        lockedCategoryKeys: Set<String> = [],
        customLists: [CustomList] = []
    ) {
        self.favorites = favorites
        self.history = history
        self.hiddenCategoryKeys = hiddenCategoryKeys
        self.categoryHiddenAt = [:]
        self.categoryShownAt = [:]
        self.lockedCategoryKeys = lockedCategoryKeys
        self.customLists = customLists
        self.favoriteTombstones = [:]
        self.listItemTombstones = [:]
        self.historyTombstones = [:]
        self.historyClearedAt = nil
        self.deletedListIDs = []
        self.reminders = []
        self.reminderTombstones = [:]
        self.hiddenChannels = [:]
        self.unhiddenChannels = [:]
        self.hiddenCategoryTags = [:]
        self.shownCategoryTags = [:]
        self.hiddenCategoryWords = [:]
        self.shownCategoryWords = [:]
    }

    enum CodingKeys: String, CodingKey {
        case favorites, history, hiddenCategoryKeys, categoryHiddenAt, categoryShownAt, lockedCategoryKeys, customLists
        case favoriteTombstones, listItemTombstones, historyTombstones, historyClearedAt, deletedListIDs
        case reminders, reminderTombstones, hiddenChannels, unhiddenChannels
        case hiddenCategoryTags, shownCategoryTags, hiddenCategoryWords, shownCategoryWords
    }

    /// Tolerant decoding: data saved by older versions keeps working.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        favorites = (try? c.decodeIfPresent([FavoriteEntry].self, forKey: .favorites)) ?? []
        history = (try? c.decodeIfPresent([HistoryEntry].self, forKey: .history)) ?? []
        hiddenCategoryKeys = (try? c.decodeIfPresent(Set<String>.self, forKey: .hiddenCategoryKeys)) ?? []
        categoryHiddenAt = (try? c.decodeIfPresent([String: Date].self, forKey: .categoryHiddenAt)) ?? [:]
        categoryShownAt = (try? c.decodeIfPresent([String: Date].self, forKey: .categoryShownAt)) ?? [:]
        lockedCategoryKeys = (try? c.decodeIfPresent(Set<String>.self, forKey: .lockedCategoryKeys)) ?? []
        customLists = (try? c.decodeIfPresent([CustomList].self, forKey: .customLists)) ?? []
        favoriteTombstones = (try? c.decodeIfPresent([String: Date].self, forKey: .favoriteTombstones)) ?? [:]
        listItemTombstones = (try? c.decodeIfPresent([String: Date].self, forKey: .listItemTombstones)) ?? [:]
        historyTombstones = (try? c.decodeIfPresent([String: Date].self, forKey: .historyTombstones)) ?? [:]
        historyClearedAt = try? c.decodeIfPresent(Date.self, forKey: .historyClearedAt)
        deletedListIDs = (try? c.decodeIfPresent(Set<UUID>.self, forKey: .deletedListIDs)) ?? []
        reminders = (try? c.decodeIfPresent([ProgramReminder].self, forKey: .reminders)) ?? []
        reminderTombstones = (try? c.decodeIfPresent([String: Date].self, forKey: .reminderTombstones)) ?? [:]
        hiddenChannels = (try? c.decodeIfPresent([String: Date].self, forKey: .hiddenChannels)) ?? [:]
        unhiddenChannels = (try? c.decodeIfPresent([String: Date].self, forKey: .unhiddenChannels)) ?? [:]
        hiddenCategoryTags = (try? c.decodeIfPresent([String: Date].self, forKey: .hiddenCategoryTags)) ?? [:]
        shownCategoryTags = (try? c.decodeIfPresent([String: Date].self, forKey: .shownCategoryTags)) ?? [:]
        hiddenCategoryWords = (try? c.decodeIfPresent([String: Date].self, forKey: .hiddenCategoryWords)) ?? [:]
        shownCategoryWords = (try? c.decodeIfPresent([String: Date].self, forKey: .shownCategoryWords)) ?? [:]
    }

    public static func categoryKey(playlistID: UUID, categoryID: String) -> String {
        playlistID.uuidString + "|" + categoryID
    }

    // MARK: Favourites

    public func isFavorite(_ reference: MediaReference) -> Bool {
        favorites.contains { $0.reference.isSameItem(as: reference) }
    }

    /// Returns the new favourite state.
    @discardableResult
    public mutating func toggleFavorite(_ reference: MediaReference, title: String, imageURL: URL?, now: Date = Date()) -> Bool {
        if let index = favorites.firstIndex(where: { $0.reference.isSameItem(as: reference) }) {
            favoriteTombstones[favorites[index].id] = now
            favorites.remove(at: index)
            return false
        }
        favoriteTombstones[reference.itemKey] = nil
        var ref = reference
        ref.episodeID = nil
        let nextIndex = (favorites.map(\.sortIndex).max() ?? -1) + 1
        favorites.append(FavoriteEntry(reference: ref, title: title, imageURL: imageURL, addedAt: now, sortIndex: nextIndex))
        return true
    }

    public func favorites(playlistID: UUID, type: ContentType? = nil) -> [FavoriteEntry] {
        favorites
            .filter { $0.reference.playlistID == playlistID && (type == nil || $0.reference.type == type) }
            .sorted { $0.sortIndex < $1.sortIndex }
    }

    public mutating func moveFavorites(playlistID: UUID, type: ContentType?, fromOffsets source: IndexSet, toOffset destination: Int) {
        var ordered = favorites(playlistID: playlistID, type: type)
        let moving = source.sorted().map { ordered[$0] }
        for index in source.sorted(by: >) { ordered.remove(at: index) }
        let adjusted = destination - source.filter { $0 < destination }.count
        ordered.insert(contentsOf: moving, at: min(max(adjusted, 0), ordered.count))
        let baseIndex = ordered.map(\.sortIndex).min() ?? 0
        for (offset, entry) in ordered.enumerated() {
            if let index = favorites.firstIndex(where: { $0.id == entry.id }) {
                favorites[index].sortIndex = baseIndex + offset
            }
        }
    }

    // MARK: History & progress

    public mutating func recordPlayback(_ entry: HistoryEntry) {
        if let index = history.firstIndex(where: { $0.id == entry.id }) {
            var updated = entry
            // The file's chapters stay the same: keep where its end credits start.
            if updated.creditsStart == nil { updated.creditsStart = history[index].creditsStart }
            // Keep the known position when starting again without an explicit one.
            if updated.position == 0 && history[index].position > 0 && !history[index].isFinished {
                updated.position = history[index].position
                updated.duration = max(updated.duration, history[index].duration)
            }
            history.remove(at: index)
            history.insert(updated, at: 0)
        } else {
            history.insert(entry, at: 0)
        }
        if history.count > Self.maxHistory {
            history.removeLast(history.count - Self.maxHistory)
        }
    }

    /// `creditsStart`: where the end credits start, when the player found them in the file's chapters; nil keeps the
    /// one known (an engine that cannot read chapters plays the same file).
    public mutating func updateProgress(
        _ reference: MediaReference, position: Double, duration: Double, creditsStart: Double? = nil, now: Date = Date()
    ) {
        guard let index = history.firstIndex(where: { $0.reference.isSamePlayback(as: reference) }) else { return }
        // NaN or infinite (a clock not known yet) has no JSON form: the library could no longer be saved.
        if position.isFinite { history[index].position = max(position, 0) }
        if duration.isFinite && duration > 0 { history[index].duration = duration }
        if let creditsStart, creditsStart.isFinite, creditsStart > 0 { history[index].creditsStart = creditsStart }
        history[index].lastWatchedAt = now
    }

    public func progress(for reference: MediaReference) -> HistoryEntry? {
        history.first { $0.reference.isSamePlayback(as: reference) }
    }

    /// Movies and episodes in progress, most recent first, one entry per series.
    public func continueWatching(playlistID: UUID? = nil, limit: Int = 20) -> [HistoryEntry] {
        var seenItems = Set<String>()
        var result: [HistoryEntry] = []
        for entry in history.sorted(by: { $0.lastWatchedAt > $1.lastWatchedAt }) {
            guard entry.reference.type != .live,
                  playlistID == nil || entry.reference.playlistID == playlistID,
                  seenItems.insert(entry.reference.itemKey).inserted,
                  entry.isResumable else { continue }
            result.append(entry)
            if result.count >= limit { break }
        }
        return result
    }

    public func recentChannels(playlistID: UUID, limit: Int = 20) -> [HistoryEntry] {
        Array(history
            .filter { $0.reference.type == .live && $0.reference.playlistID == playlistID }
            .sorted { $0.lastWatchedAt > $1.lastWatchedAt }
            .prefix(limit))
    }

    public func lastChannel(playlistID: UUID) -> HistoryEntry? {
        recentChannels(playlistID: playlistID, limit: 1).first
    }

    public mutating func removeHistory(_ entry: HistoryEntry, now: Date = Date()) {
        history.removeAll { $0.id == entry.id }
        historyTombstones[entry.id] = now
    }

    public mutating func clearHistory(now: Date = Date()) {
        history.removeAll()
        historyTombstones.removeAll()
        historyClearedAt = now
    }

    // MARK: Categories

    public func isHidden(playlistID: UUID, categoryID: String) -> Bool {
        hiddenCategoryKeys.contains(Self.categoryKey(playlistID: playlistID, categoryID: categoryID))
    }

    /// Hides or shows a category, dated for a merge with another copy (`merged(with:)`).
    public mutating func setHidden(_ hidden: Bool, playlistID: UUID, categoryID: String, now: Date = Date()) {
        let key = Self.categoryKey(playlistID: playlistID, categoryID: categoryID)
        if hidden {
            hiddenCategoryKeys.insert(key)
            categoryHiddenAt[key] = now
            categoryShownAt[key] = nil
        } else if hiddenCategoryKeys.remove(key) != nil {
            categoryHiddenAt[key] = nil
            categoryShownAt[key] = now
        }
    }

    /// Hides or shows several categories at once (Hide All, Show All). More than `SyncHidden.showAllThreshold` of one type
    /// shown again leave one "shown again" marker for that type (`<playlist>|<type>-*` in `categoryShownAt`, docs/SYNC.md "Hidden categories") instead of
    /// one each; the categories of those types still hidden are dated `after`, so that the marker leaves them hidden on the
    /// other devices.
    public mutating func setHidden(_ hidden: Bool, playlistID: UUID, categoryIDs: [String], now: Date = Date(), after: Date? = nil) {
        let keys = categoryIDs.map { Self.categoryKey(playlistID: playlistID, categoryID: $0) }
        let shown = keys.filter { hiddenCategoryKeys.contains($0) }
        var counts: [String: Int] = [:]
        for key in shown { if let marker = Self.showAllMarker(key) { counts[marker, default: 0] += 1 } }
        let markers = Set(counts.filter { $0.value > SyncHidden.showAllThreshold }.keys)
        guard !hidden, !markers.isEmpty else {
            for id in categoryIDs { setHidden(hidden, playlistID: playlistID, categoryID: id, now: now) }
            return
        }
        for key in shown {
            hiddenCategoryKeys.remove(key)
            categoryHiddenAt[key] = nil
            categoryShownAt[key] = Self.showAllMarker(key).map { markers.contains($0) } == true ? nil : now
        }
        for marker in markers { categoryShownAt[marker] = now }
        let later = after ?? now.addingTimeInterval(0.001)
        for key in hiddenCategoryKeys where Self.showAllMarker(key).map({ markers.contains($0) }) == true {
            categoryHiddenAt[key] = later
        }
    }

    /// The "shown again" marker of the categories of the same playlist and type as `key` (`<playlist>|<type>-<id>`):
    /// `<playlist>|<type>-*`; nil when the category's ID has no type.
    public static func showAllMarker(_ key: String) -> String? {
        guard !key.hasSuffix("-*"), let bar = key.firstIndex(of: "|") else { return nil }
        let start = key.index(after: bar)
        guard let dash = key[start...].firstIndex(of: "-"), dash > start else { return nil }
        return String(key[..<dash]) + "-*"
    }

    /// Whether every category carrying this tag is hidden, in all playlists.
    public func isHidden(tag: String) -> Bool {
        hiddenCategoryTags[tag] != nil
    }

    public mutating func setHidden(_ hidden: Bool, tag: String, now: Date = Date()) {
        if hidden {
            hiddenCategoryTags[tag] = now
            shownCategoryTags[tag] = nil
        } else if hiddenCategoryTags.removeValue(forKey: tag) != nil {
            shownCategoryTags[tag] = now
        }
    }

    /// Whether the categories of this style or word are hidden, in all playlists.
    public func isHidden(word: String) -> Bool {
        hiddenCategoryWords[word] != nil
    }

    public mutating func setHidden(_ hidden: Bool, word: String, now: Date = Date()) {
        if hidden {
            hiddenCategoryWords[word] = now
            shownCategoryWords[word] = nil
        } else if hiddenCategoryWords.removeValue(forKey: word) != nil {
            shownCategoryWords[word] = now
        }
    }

    public func isHidden(playlistID: UUID, channelID: String) -> Bool {
        hiddenChannels[Self.categoryKey(playlistID: playlistID, categoryID: channelID)] != nil
    }

    public mutating func setHidden(_ hidden: Bool, playlistID: UUID, channelID: String, now: Date = Date()) {
        let key = Self.categoryKey(playlistID: playlistID, categoryID: channelID)
        if hidden {
            hiddenChannels[key] = now
            unhiddenChannels[key] = nil
        } else if hiddenChannels.removeValue(forKey: key) != nil {
            unhiddenChannels[key] = now
        }
    }

    /// Ids of the channels hidden in a playlist.
    public func hiddenChannelIDs(playlistID: UUID) -> Set<String> {
        let prefix = playlistID.uuidString + "|"
        return Set(hiddenChannels.keys.filter { $0.hasPrefix(prefix) }.map { String($0.dropFirst(prefix.count)) })
    }

    public func isLocked(playlistID: UUID, categoryID: String) -> Bool {
        lockedCategoryKeys.contains(Self.categoryKey(playlistID: playlistID, categoryID: categoryID))
    }

    public mutating func setLocked(_ locked: Bool, playlistID: UUID, categoryID: String) {
        let key = Self.categoryKey(playlistID: playlistID, categoryID: categoryID)
        if locked { lockedCategoryKeys.insert(key) } else { lockedCategoryKeys.remove(key) }
    }

    /// Locks every adult-looking category of a freshly loaded playlist.
    public mutating func lockAdultCategories(playlistID: UUID, content: PlaylistContent) {
        for category in content.liveCategories + content.movieCategories + content.seriesCategories where category.looksAdult {
            lockedCategoryKeys.insert(Self.categoryKey(playlistID: playlistID, categoryID: category.id))
        }
    }

    // MARK: Reminders

    public func hasReminder(id: String) -> Bool {
        reminders.contains { $0.id == id }
    }

    /// Adds the reminder, or removes it when it already exists. Returns whether it is now set.
    @discardableResult
    public mutating func toggleReminder(_ reminder: ProgramReminder, now: Date = Date()) -> Bool {
        if hasReminder(id: reminder.id) {
            removeReminder(id: reminder.id, now: now)
            return false
        }
        var added = reminder
        added.createdAt = now
        reminderTombstones[reminder.id] = nil
        reminders.append(added)
        reminders.sort { ($0.start, $0.id) < ($1.start, $1.id) }
        return true
    }

    public mutating func removeReminder(id: String, now: Date = Date()) {
        guard reminders.contains(where: { $0.id == id }) else { return }
        reminders.removeAll { $0.id == id }
        reminderTombstones[id] = now
    }

    /// Reminders of programmes that have not ended yet, soonest first.
    public func upcomingReminders(at date: Date = Date()) -> [ProgramReminder] {
        reminders.filter { $0.end > date }
    }

    /// Drops reminders of programmes that ended before `date`, and deletion markers over two weeks old: other
    /// devices have had time to sync the deletion, and the programme is over anyway.
    public mutating func pruneReminders(endedBefore date: Date) {
        reminders.removeAll { $0.end < date }
        let stale = date.addingTimeInterval(-14 * 86_400)
        reminderTombstones = reminderTombstones.filter { $0.value > stale }
    }

    // MARK: Sync

    /// Merges a copy coming from another device: favourites and lists are united, the most recent
    /// history entry wins, category preferences are united. Deterministic, so both sides converge.
    public func merged(with other: UserLibrary) -> UserLibrary {
        var result = self

        result.favoriteTombstones = favoriteTombstones.merging(other.favoriteTombstones) { max($0, $1) }
        result.listItemTombstones = listItemTombstones.merging(other.listItemTombstones) { max($0, $1) }
        result.historyTombstones = historyTombstones.merging(other.historyTombstones) { max($0, $1) }
        result.historyClearedAt = [historyClearedAt, other.historyClearedAt].compactMap { $0 }.max()

        var favoritesByID = Dictionary(favorites.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for favorite in other.favorites where favoritesByID[favorite.id] == nil {
            favoritesByID[favorite.id] = favorite
        }
        result.favorites = favoritesByID.values
            .filter { favorite in (result.favoriteTombstones[favorite.id].map { $0 < favorite.addedAt } ?? true) }
            .sorted { ($0.sortIndex, $0.addedAt, $0.id) < ($1.sortIndex, $1.addedAt, $1.id) }

        var historyByID = Dictionary(history.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for entry in other.history {
            if let existing = historyByID[entry.id], existing.lastWatchedAt >= entry.lastWatchedAt { continue }
            historyByID[entry.id] = entry
        }
        let clearedAt = result.historyClearedAt ?? .distantPast
        result.history = Array(historyByID.values
            .filter { entry in
                entry.lastWatchedAt > clearedAt && (result.historyTombstones[entry.id].map { $0 < entry.lastWatchedAt } ?? true)
            }
            .sorted { ($0.lastWatchedAt, $0.id) > ($1.lastWatchedAt, $1.id) }
            .prefix(Self.maxHistory))

        result.reminderTombstones = reminderTombstones.merging(other.reminderTombstones) { max($0, $1) }
        var remindersByID = Dictionary(reminders.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for reminder in other.reminders {
            if let existing = remindersByID[reminder.id], existing.createdAt >= reminder.createdAt { continue }
            remindersByID[reminder.id] = reminder
        }
        result.reminders = remindersByID.values
            .filter { reminder in result.reminderTombstones[reminder.id].map { $0 < reminder.createdAt } ?? true }
            .sorted { ($0.start, $0.id) < ($1.start, $1.id) }

        result.unhiddenChannels = unhiddenChannels.merging(other.unhiddenChannels) { max($0, $1) }
        result.hiddenChannels = hiddenChannels.merging(other.hiddenChannels) { max($0, $1) }
            .filter { key, hiddenAt in result.unhiddenChannels[key].map { $0 < hiddenAt } ?? true }

        result.shownCategoryTags = shownCategoryTags.merging(other.shownCategoryTags) { max($0, $1) }
        result.hiddenCategoryTags = hiddenCategoryTags.merging(other.hiddenCategoryTags) { max($0, $1) }
            .filter { tag, hiddenAt in result.shownCategoryTags[tag].map { $0 < hiddenAt } ?? true }
        result.shownCategoryWords = shownCategoryWords.merging(other.shownCategoryWords) { max($0, $1) }
        result.hiddenCategoryWords = hiddenCategoryWords.merging(other.hiddenCategoryWords) { max($0, $1) }
            .filter { word, hiddenAt in result.shownCategoryWords[word].map { $0 < hiddenAt } ?? true }

        // Hidden on either copy, unless shown again later (a key hidden without a date loses to any later showing).
        result.categoryShownAt = categoryShownAt.merging(other.categoryShownAt) { max($0, $1) }
        result.categoryHiddenAt = categoryHiddenAt.merging(other.categoryHiddenAt) { max($0, $1) }
        let shown = result.categoryShownAt
        let hiddenAt = result.categoryHiddenAt
        result.hiddenCategoryKeys = hiddenCategoryKeys.union(other.hiddenCategoryKeys).filter { key in
            shown[key].map { $0 < (hiddenAt[key] ?? .distantPast) } ?? true
        }
        result.categoryHiddenAt = hiddenAt.filter { result.hiddenCategoryKeys.contains($0.key) }
        result.categoryShownAt = shown.filter { !result.hiddenCategoryKeys.contains($0.key) }
        result.lockedCategoryKeys.formUnion(other.lockedCategoryKeys)

        result.deletedListIDs = deletedListIDs.union(other.deletedListIDs)
        for list in other.customLists {
            if let index = result.customLists.firstIndex(where: { $0.id == list.id }) {
                let mine = result.customLists[index]
                if (list.updatedAt, list.name) > (mine.updatedAt, mine.name) {
                    result.customLists[index] = list
                }
            } else {
                result.customLists.append(list)
            }
        }
        result.customLists.removeAll { result.deletedListIDs.contains($0.id) }
        result.customLists.sort { ($0.name.lowercased(), $0.id.uuidString) < ($1.name.lowercased(), $1.id.uuidString) }
        return result
    }

    // MARK: Custom lists

    @discardableResult
    public mutating func createList(named name: String, now: Date = Date()) -> UUID {
        let list = CustomList(name: name, updatedAt: now, renamedAt: now, orderedAt: now)
        customLists.append(list)
        return list.id
    }

    public mutating func renameList(_ id: UUID, to name: String, now: Date = Date()) {
        guard let index = customLists.firstIndex(where: { $0.id == id }) else { return }
        customLists[index].name = name
        customLists[index].updatedAt = now
        customLists[index].renamedAt = now
    }

    public mutating func deleteList(_ id: UUID) {
        customLists.removeAll { $0.id == id }
        deletedListIDs.insert(id)
    }

    /// Adds or removes an item; returns whether it is now in the list.
    @discardableResult
    public mutating func toggle(_ reference: MediaReference, title: String, imageURL: URL?, inList id: UUID, now: Date = Date()) -> Bool {
        guard let index = customLists.firstIndex(where: { $0.id == id }) else { return false }
        var ref = reference
        ref.episodeID = nil
        customLists[index].updatedAt = now
        let tombstone = id.uuidString + "|" + ref.itemKey
        if let position = customLists[index].items.firstIndex(where: { $0.reference.itemKey == ref.itemKey }) {
            customLists[index].items.remove(at: position)
            listItemTombstones[tombstone] = now
            return false
        }
        listItemTombstones[tombstone] = nil
        customLists[index].items.append(FavoriteEntry(reference: ref, title: title, imageURL: imageURL, addedAt: now, sortIndex: customLists[index].items.count))
        return true
    }

    public mutating func moveItems(inList id: UUID, fromOffsets source: IndexSet, toOffset destination: Int, now: Date = Date()) {
        guard let index = customLists.firstIndex(where: { $0.id == id }) else { return }
        var items = customLists[index].items
        let moving = source.sorted().map { items[$0] }
        for offset in source.sorted(by: >) { items.remove(at: offset) }
        let adjusted = destination - source.filter { $0 < destination }.count
        items.insert(contentsOf: moving, at: min(max(adjusted, 0), items.count))
        customLists[index].items = items
        customLists[index].updatedAt = now
        customLists[index].orderedAt = now
    }

    // MARK: Cleanup

    public mutating func removeData(for playlistID: UUID) {
        favorites.removeAll { $0.reference.playlistID == playlistID }
        history.removeAll { $0.reference.playlistID == playlistID }
        let prefix = playlistID.uuidString + "|"
        hiddenCategoryKeys = hiddenCategoryKeys.filter { !$0.hasPrefix(prefix) }
        categoryHiddenAt = categoryHiddenAt.filter { !$0.key.hasPrefix(prefix) }
        categoryShownAt = categoryShownAt.filter { !$0.key.hasPrefix(prefix) }
        lockedCategoryKeys = lockedCategoryKeys.filter { !$0.hasPrefix(prefix) }
        for index in customLists.indices {
            customLists[index].items.removeAll { $0.reference.playlistID == playlistID }
        }
        reminders.removeAll { $0.playlistID == playlistID }
        hiddenChannels = hiddenChannels.filter { !$0.key.hasPrefix(prefix) }
        unhiddenChannels = unhiddenChannels.filter { !$0.key.hasPrefix(prefix) }
    }
}

import Foundation
import IPTVCore

/// The order of the rows of the Home, Movies and Series tabs (Settings → Row order): the same keys and rules as on Android
/// (`RowOrder`, IPTVCore), kept in `sync.json` and synced (docs/SYNC.md, "Rows"). Each profile has its own: the main
/// one's is `SyncState.rowOrders` (the device's order before profiles had one each), the others' in their
/// `ProfileSyncState` (docs/PROFILES.md).
extension AppModel {
    /// The tab as sync names it (`SyncRows.tab`).
    static func rowTab(_ type: ContentType) -> String { type == .series ? SyncRows.series : SyncRows.movies }

    /// The order saved for a tab; nil: the default order.
    func savedRowOrder(_ type: ContentType) -> SyncRows? { savedRowOrder(tab: Self.rowTab(type)) }

    /// The order saved for a tab as sync names it (`movie`, `series`, `home`); nil: the default order.
    func savedRowOrder(tab: String) -> SyncRows? { profileRowOrders.first { $0.tab == tab } }

    /// The open profile's records.
    private var profileRowOrders: [SyncRows] {
        activeProfile.isMain ? syncState.rowOrders : (syncState.profileStates[activeProfile.id.uuidString.lowercased()]?.rowOrders ?? [])
    }

    /// The keys of the rows of a tab in their default order: the built-in rows, the `selections`' rows (`sel:<id>`),
    /// then `categories` as they come.
    static func tabRowKeys(_ type: ContentType, categories: [MediaCategory], selections: [String] = []) -> [String] {
        RowOrder.builtIns(series: type == .series) + selections + categories.map { RowOrder.categoryKey(id: $0.id, name: $0.name) }
    }

    /// Whether the open profile may change its order: not a kids profile.
    var canChangeRowOrder: Bool { !activeProfile.isKids }

    /// The rows of a tab in the viewer's order, hidden or not. The categories hidden in Settings (or by a hidden
    /// language) are not among them: they never show, whatever the order.
    func arrangedRows(_ type: ContentType, categories: [MediaCategory]? = nil) -> [RowOrder.Row] {
        let saved = savedRowOrder(type)
        return RowOrder.arrange(current: Self.tabRowKeys(type, categories: categories ?? self.categories(for: type),
                                                         selections: selectionRowKeys(in: Self.rowTab(type))),
                                order: saved?.order ?? [], hidden: saved?.hidden ?? [])
    }

    /// Saves the rows of a tab as the viewer set them: `arranged`, the keys of the tab's rows now, `hidden` those hidden.
    /// The rows the tab no longer has (a category gone from the playlist) keep their place and their choice; the record
    /// is dated for sync.
    func setRowOrder(_ type: ContentType, arranged: [String], hidden: [String]) {
        setRowOrder(tab: Self.rowTab(type), arranged: arranged, hidden: hidden)
    }

    /// The same for a tab as sync names it (`movie`, `series`, `home`).
    func setRowOrder(tab: String, arranged: [String], hidden: [String]) {
        guard canChangeRowOrder else { return }
        let previous = savedRowOrder(tab: tab)
        let record = SyncRows(
            tab: tab,
            order: RowOrder.saving(arranged: arranged, previous: previous?.order ?? []),
            hidden: RowOrder.savingHidden(current: arranged, hiddenNow: hidden, previous: previous?.hidden ?? []),
            updatedAt: 0
        ).normalized()
        if let previous, previous.order == record.order, previous.hidden == record.hidden { return }
        var dated = record
        dated.updatedAt = syncStamp().syncMilliseconds
        keepRowOrder(dated)
    }

    /// Default order: the rows of the tab in their default order again, those hidden staying hidden.
    func resetRowOrder(_ type: ContentType) { resetRowOrder(tab: Self.rowTab(type)) }

    /// The same for a tab as sync names it (`movie`, `series`, `home`).
    func resetRowOrder(tab: String) {
        guard canChangeRowOrder, var record = savedRowOrder(tab: tab), !record.order.isEmpty else { return }
        record.order = []
        record.updatedAt = syncStamp().syncMilliseconds
        keepRowOrder(record)
    }

    private func keepRowOrder(_ record: SyncRows) {
        keepRowOrderRecord(record)
        saveSyncState()
        syncSoon()
    }

    /// `record` kept for its tab in the open profile, without saving yet.
    func keepRowOrderRecord(_ record: SyncRows) {
        // Sorted by tab in UTF-16 code units, as a merge gives them: the same records compare equal.
        let records = (profileRowOrders.filter { $0.tab != record.tab } + [record])
            .sorted { $0.tab.utf16.lexicographicallyPrecedes($1.tab.utf16) }
        if activeProfile.isMain {
            syncState.rowOrders = records
        } else {
            let id = activeProfile.id.uuidString.lowercased()
            var profile = syncState.profileStates[id] ?? ProfileSyncState()
            profile.rowOrders = records
            syncState.profileStates[id] = profile
        }
    }

    // MARK: - Home

    /// Whether the playlist has movies or series to show: without, Home's channels come right after My List.
    var homeHasVOD: Bool { !content.movies.isEmpty || !content.series.isEmpty }

    /// The keys of Home's rows in their default order (`RowOrder.homeBuiltIns`): on Apple devices, with the collections;
    /// the `selections`' rows (`sel:<id>`) before the channels when they come last.
    static func homeRowKeys(hasVOD: Bool, selections: [String] = []) -> [String] {
        let builtIns = RowOrder.homeBuiltIns(hasVOD: hasVOD, collections: true)
        let at = hasVOD ? (builtIns.firstIndex(of: RowOrder.homeChannels) ?? builtIns.count) : builtIns.count
        return Array(builtIns[..<at]) + selections + Array(builtIns[at...])
    }

    /// Home's rows in the viewer's order, hidden or not (Settings → Row order). The carousel stays above them.
    func arrangedHomeRows() -> [RowOrder.Row] {
        let saved = savedRowOrder(tab: SyncRows.home)
        return RowOrder.arrange(current: Self.homeRowKeys(hasVOD: homeHasVOD, selections: selectionRowKeys(in: SyncRows.home)),
                                order: saved?.order ?? [], hidden: saved?.hidden ?? [])
    }

    /// The keys of the rows Home shows, in their order: those the viewer hid are left out on iPhone, iPad and Mac alike
    /// (one `HomeView`); an empty row shows nothing.
    func homeRowKeysShown() -> [String] { arrangedHomeRows().filter { !$0.hidden }.map(\.key) }

    /// All movies (or series): the `limit` latest additions, each film once, as the grid of the whole catalogue lists
    /// them (the same list, kept once per catalogue and order: no other copy of the catalogue).
    func allTitles(_ type: ContentType, limit: Int) -> [ShowcaseItem] {
        type == .movie ? movies(in: nil, sort: .recent).prefix(limit).map(ShowcaseItem.movie)
            : series(in: nil, sort: .recent).prefix(limit).map(ShowcaseItem.series)
    }

    /// How many titles the grid of the whole catalogue lists.
    func allCount(_ type: ContentType) -> Int {
        type == .movie ? movies(in: nil, sort: .recent).count : series(in: nil, sort: .recent).count
    }

    /// The trending titles of a tab.
    /// The titles of a row Ondo picks (`PseudoCategory.newReleases`, `.awardWinners`), in the row's order: the row shows
    /// the first `limit`, its "See All" grid every one. Hidden and locked categories, hidden languages and movies hidden
    /// as watched stay out of both.
    func rowTitles(_ row: String, type: ContentType, limit: Int = .max) -> [ShowcaseItem] {
        switch row {
        case PseudoCategory.newReleases: return type == .series ? newSeries(limit: limit) : newMovies(limit: limit)
        case PseudoCategory.awardWinners:
            return type == .movie ? awardWinners(limit: limit).map(ShowcaseItem.movie) : seriesAwardWinners(limit: limit).map(ShowcaseItem.series)
        case let id where id.hasPrefix(PseudoCategory.selectionPrefix):
            guard let picked = self.selection(String(id.dropFirst(PseudoCategory.selectionPrefix.count))) else { return [] }
            return selectionTitles(picked, tab: Self.selectionTab(type), limit: limit)
        default: return []
        }
    }

    func trending(_ type: ContentType) -> [ShowcaseItem] {
        trendingTitles().compactMap { match in
            switch match {
            case .movie(let movie, _): return type == .movie ? .movie(movie) : nil
            case .series(let series, _): return type == .series ? .series(series) : nil
            }
        }
    }
}

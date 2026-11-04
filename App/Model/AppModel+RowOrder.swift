import Foundation
import IPTVCore

/// The order of the rows of the Movies and Series tabs (Settings → Row order): the same keys and rules as on Android
/// (`RowOrder`, IPTVCore), kept in `sync.json` and synced (docs/SYNC.md, "Rows").
extension AppModel {
    /// The tab as sync names it (`SyncRows.tab`).
    static func rowTab(_ type: ContentType) -> String { type == .series ? SyncRows.series : SyncRows.movies }

    /// The order saved for a tab; nil: the default order.
    func savedRowOrder(_ type: ContentType) -> SyncRows? {
        let tab = Self.rowTab(type)
        return syncState.rowOrders.first { $0.tab == tab }
    }

    /// The keys of the rows of a tab in their default order: the built-in rows, then `categories` as they come.
    static func tabRowKeys(_ type: ContentType, categories: [MediaCategory]) -> [String] {
        RowOrder.builtIns(series: type == .series) + categories.map { RowOrder.categoryKey(id: $0.id, name: $0.name) }
    }

    /// Whether the open profile may change the order: not a kids profile, as the order is the device's (every profile's).
    var canChangeRowOrder: Bool { !activeProfile.isKids }

    /// The rows of a tab in the viewer's order, hidden or not. The categories hidden in Settings (or by a hidden
    /// language) are not among them: they never show, whatever the order.
    func arrangedRows(_ type: ContentType, categories: [MediaCategory]? = nil) -> [RowOrder.Row] {
        let saved = savedRowOrder(type)
        return RowOrder.arrange(current: Self.tabRowKeys(type, categories: categories ?? self.categories(for: type)),
                                order: saved?.order ?? [], hidden: saved?.hidden ?? [])
    }

    /// Saves the rows of a tab as the viewer set them: `arranged`, the keys of the tab's rows now, `hidden` those hidden.
    /// The rows the tab no longer has (a category gone from the playlist) keep their place and their choice; the record
    /// is dated for sync.
    func setRowOrder(_ type: ContentType, arranged: [String], hidden: [String]) {
        guard canChangeRowOrder else { return }
        let previous = savedRowOrder(type)
        let record = SyncRows(
            tab: Self.rowTab(type),
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
    func resetRowOrder(_ type: ContentType) {
        guard canChangeRowOrder, var record = savedRowOrder(type), !record.order.isEmpty else { return }
        record.order = []
        record.updatedAt = syncStamp().syncMilliseconds
        keepRowOrder(record)
    }

    private func keepRowOrder(_ record: SyncRows) {
        // Sorted by tab in UTF-16 code units, as a merge gives them: the same records compare equal.
        syncState.rowOrders = (syncState.rowOrders.filter { $0.tab != record.tab } + [record])
            .sorted { $0.tab.utf16.lexicographicallyPrecedes($1.tab.utf16) }
        saveSyncState()
        syncSoon()
    }

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
    func trending(_ type: ContentType) -> [ShowcaseItem] {
        trendingTitles().compactMap { match in
            switch match {
            case .movie(let movie, _): return type == .movie ? .movie(movie) : nil
            case .series(let series, _): return type == .series ? .series(series) : nil
            }
        }
    }
}

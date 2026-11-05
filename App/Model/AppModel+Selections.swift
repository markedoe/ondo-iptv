import Foundation
import IPTVCore

/// What each title is tagged with for the selections (`Selections.Facts`, by `ShowcaseItem.id`), and the names of the
/// genres by tag, for one catalogue and what is known of it (`key`).
struct SelectionTags {
    let key: String
    let facts: [String: Selections.Facts]
    let genreNames: [String: String]
}

/// Selections (docs/SYNC.md, "Selections"): rows the viewer makes of tags (genres, awards, decades, styles), shown in
/// Home, Movies or Series as a new pick every day. The same tags and rules as on Android (`Selections`, IPTVCore); kept
/// in `sync.json` with the order of the rows, each profile its own, and synced (docs/SYNC.md, "Selections").
extension AppModel {
    /// The open profile's selections.
    var profileSelections: [SyncSelection] {
        activeProfile.isMain ? syncState.selections : (syncState.profileStates[activeProfile.id.uuidString.lowercased()]?.selections ?? [])
    }

    /// The selection of `id` in the open profile.
    func selection(_ id: String) -> SyncSelection? { profileSelections.first { $0.id == id } }

    /// The selections that show in `tab` (`home`, `movie`, `series`).
    func selections(in tab: String) -> [SyncSelection] { profileSelections.filter { $0.tabs.contains(tab) } }

    /// The row keys (`sel:<id>`) of the selections that show in `tab`.
    func selectionRowKeys(in tab: String) -> [String] { selections(in: tab).map { Selections.rowKey($0.id) } }

    /// The tab a type's selections show in, as sync names it.
    static func selectionTab(_ type: ContentType) -> String { type == .series ? SyncRows.series : SyncRows.movies }

    // MARK: Tags

    /// The tags of every title of the catalogue: its genres (the provider's, from the list or the pages opened), the
    /// festivals and ceremonies of its awards, its year, and the styles of its category's name.
    func selectionTags() -> SelectionTags {
        // The genres of the pages opened count, not only how many there are: a page opened again can bring others.
        let movieGenres = movieDetailsCache.reduce(0) { $0 &+ ($1.key.hashValue ^ ($1.value.genre?.hashValue ?? 0)) }
        let seriesGenres = seriesDetailsCache.reduce(0) { $0 &+ ($1.key.hashValue ^ ($1.value.info.genre?.hashValue ?? 0)) }
        let key = [String(content.fetchedAt.timeIntervalSince1970), activePlaylist?.id.uuidString ?? "", String(content.movies.count),
                   String(content.series.count), String(movieDetailsCache.count), String(movieGenres), String(seriesDetailsCache.count),
                   String(seriesGenres), String(movieAwards.count), String(seriesAwards.count)].joined(separator: "|")
        if let cached = selectionTagsCache, cached.key == key { return cached }
        var names: [String: String] = [:]
        func genres(_ fields: [String?]) -> Set<String> {
            var result = Set<String>()
            for genre in fields.flatMap({ Self.genres(in: $0) }) {
                guard let tag = Selections.genre(genre) else { continue }
                if names[tag] == nil { names[tag] = genre }
                result.insert(String(tag.dropFirst(Selections.genrePrefix.count)))
            }
            return result
        }
        let catalogue = AwardsCatalogue.current
        func groups(_ entries: [AwardsCatalogue.Entry]?) -> Set<String> { Set((entries ?? []).compactMap { catalogue.award($0.awardID)?.group }) }
        var styles: [String: Set<String>] = [:]
        for category in content.movieCategories + content.seriesCategories where styles[category.id] == nil {
            styles[category.id] = Selections.styles(of: category.name)
        }
        var facts: [String: Selections.Facts] = [:]
        facts.reserveCapacity(content.movies.count + content.series.count)
        for movie in content.movies {
            facts["m-" + movie.id] = Selections.Facts(
                genres: genres([movieDetailsCache[movie.id]?.genre]), awardGroups: groups(movieAwards[movie.id]),
                year: movie.year, styles: styles[movie.categoryID] ?? []
            )
        }
        for show in content.series {
            facts["s-" + show.id] = Selections.Facts(
                genres: genres([show.genre, seriesDetailsCache[show.id]?.info.genre]), awardGroups: groups(seriesAwards[show.id]),
                year: show.year, styles: styles[show.categoryID] ?? []
            )
        }
        let tags = SelectionTags(key: key, facts: facts, genreNames: names)
        selectionTagsCache = tags
        return tags
    }

    /// The day a selection's pick is for: the device's own date in the Gregorian calendar ("2026-11-05"), whatever
    /// calendar the device shows (a Buddhist or Japanese one would give another year, and another pick than Android's).
    /// `calendar`: only its time zone counts.
    nonisolated static func selectionDay(_ date: Date, calendar: Calendar = .current) -> String {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let parts = gregorian.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The titles of `selection` in `tab`, the first `limit` of the day's order (`Selections.dailyOrder`): movies in
    /// Movies, series in Series, both on Home; each film once; hidden, locked and Kids-hidden categories, hidden languages
    /// and movies hidden as watched left out, as every row; the titles watched last.
    func selectionTitles(_ selection: SyncSelection, tab: String, limit: Int = .max, now: Date? = nil) -> [ShowcaseItem] {
        guard selection.tags.contains(where: Selections.isKnown) else { return [] }
        // Read for SwiftUI: a new day draws the rows again.
        _ = selectionToday
        let day = Self.selectionDay(now ?? Date())
        let tags = selectionTags()
        // Worked out once for what the row depends on, not at every redraw: the catalogue and what is known of it, the
        // selection, the day, what is hidden, what was watched, and TMDb's ratings known.
        let lastWatched = library.history.map(\.lastWatchedAt).max()?.timeIntervalSince1970 ?? 0
        let cacheKey = [tags.key, selection.id, String(selection.updatedAt), selection.tags.joined(separator: ","), selection.match, tab,
                        String(limit), day, excludedCategoryIDs.sorted().joined(separator: ","), hiddenTags.sorted().joined(separator: ","),
                        String(settings.hideWatchedMovies), String(settings.groupVersions), String(library.history.count), String(lastWatched),
                        String(tmdbMetadata.titles.count)].joined(separator: "|")
        if let cached = selectionTitlesCache[cacheKey] { return cached }
        let titles = pickSelectionTitles(selection, tab: tab, limit: limit, day: day, tags: tags)
        if selectionTitlesCache.count > 32 { selectionTitlesCache.removeAll() }
        selectionTitlesCache[cacheKey] = titles
        return titles
    }

    private func pickSelectionTitles(_ selection: SyncSelection, tab: String, limit: Int, day: String, tags: SelectionTags) -> [ShowcaseItem] {
        let all = selection.matchesAll
        func matches(_ id: String) -> Bool { tags.facts[id].map { Selections.matches(tags: selection.tags, all: all, facts: $0) } ?? false }
        let excluded = exclusions
        var candidates: [ShowcaseItem] = []
        if tab != SyncRows.series {
            let movies = hidingWatched(content.movies.filter { !excluded.contains($0) && matches("m-" + $0.id) })
            candidates += films.distinct(movies).map(ShowcaseItem.movie)
        }
        if tab != SyncRows.movies {
            candidates += content.series.filter { !excluded.contains($0) && matches("s-" + $0.id) }.map(ShowcaseItem.series)
        }
        var seen = Set<String>()
        candidates = candidates.filter { seen.insert($0.id).inserted }
        let playlist = activePlaylist?.id
        var finished = Set<String>()
        var started = Set<String>()
        for entry in library.history where entry.reference.playlistID == playlist {
            switch entry.reference.type {
            case .movie: if entry.isFinished { finished.insert(entry.reference.itemID) }
            case .series: started.insert(entry.reference.itemID)
            default: break
            }
        }
        let ordered = Selections.dailyOrder(
            candidates,
            selectionID: selection.id,
            day: day,
            key: { item in
                switch item {
                case .movie(let movie): return "movie:" + movie.id
                case .series(let series): return "series:" + series.id
                }
            },
            rating: { item in self.tmdbTitle(for: item)?.votes },
            watched: { item in
                switch item {
                case .movie(let movie): return finished.contains(movie.id)
                case .series(let series): return started.contains(series.id)
                }
            }
        )
        return Array(ordered.prefix(limit))
    }

    /// The tags a selection can be made of, from the playlist's visible titles: genres by name, festivals and
    /// ceremonies in the order of the awards file, decades the latest first, styles in their order.
    func selectionChoices() -> (genres: [(tag: String, name: String)], awardGroups: [String], decades: [Int], styles: [String]) {
        let tags = selectionTags()
        let excluded = exclusions
        let movies = content.movies.filter { !excluded.contains($0) }
        let series = content.series.filter { !excluded.contains($0) }
        let facts = movies.compactMap { tags.facts["m-" + $0.id] } + series.compactMap { tags.facts["s-" + $0.id] }
        let genres = Set(facts.flatMap(\.genres))
        let groups = Set(facts.flatMap(\.awardGroups))
        let styles = Set(facts.flatMap(\.styles))
        let catalogue = AwardsCatalogue.current
        var orderedGroups: [String] = []
        for award in catalogue.awards + catalogue.seriesAwards where groups.contains(award.group) && !orderedGroups.contains(award.group) {
            orderedGroups.append(award.group)
        }
        return (
            genres: genres.compactMap { genre in
                let tag = Selections.genrePrefix + genre
                return tags.genreNames[tag].map { (tag: tag, name: $0) }
            }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending },
            awardGroups: orderedGroups,
            decades: Selections.decades(movies.map(\.year) + series.map(\.year)),
            styles: CategoryStyles.order.filter { styles.contains($0) }
        )
    }

    /// The tags a selection's page offers, by group (genres, awards, decades, styles; the empty ones left out): the
    /// playlist's (`selectionChoices`), then those of `saved` (the selection's) it no longer offers (a category hidden,
    /// another playlist), so that they can be taken out.
    func selectionTagGroups(keeping saved: [String]) -> [(kind: String, tags: [String])] {
        let choices = selectionChoices()
        let offered: [(String, [String])] = [
            (Selections.genrePrefix, choices.genres.map(\.tag)),
            (Selections.awardPrefix, choices.awardGroups.map { Selections.award($0) }),
            (Selections.decadePrefix, choices.decades.map { Selections.decade($0) }),
            (Selections.stylePrefix, choices.styles.map { Selections.style($0) }),
        ]
        return offered.map { kind, list in
            (kind: kind, tags: list + saved.filter { $0.hasPrefix(kind) && Selections.isKnown($0) && !list.contains($0) })
        }.filter { !$0.tags.isEmpty }
    }

    /// A tag's name: a genre's as the titles name it, a festival's or ceremony's, a decade ("1990s"), a style's.
    func selectionTagName(_ tag: String) -> String {
        if tag.hasPrefix(Selections.genrePrefix) {
            return selectionTags().genreNames[tag] ?? String(tag.dropFirst(Selections.genrePrefix.count)).capitalized
        }
        if tag.hasPrefix(Selections.awardPrefix) { return AwardGroup.name(String(tag.dropFirst(Selections.awardPrefix.count))) }
        if tag.hasPrefix(Selections.decadePrefix) {
            let decade = String(tag.dropFirst(Selections.decadePrefix.count))
            return String(localized: "\(decade)s", comment: "A decade, as a selection's tag: 1990s")
        }
        if tag.hasPrefix(Selections.stylePrefix) { return CategoryStyleName.name(String(tag.dropFirst(Selections.stylePrefix.count))) }
        return tag
    }

    /// A selection's name: the one the viewer gave, else its tags' (`Selections.defaultName`).
    func selectionName(_ selection: SyncSelection) -> String { selectionName(name: selection.name, tags: selection.tags) }

    func selectionName(name: String, tags: [String]) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        let named = Selections.defaultName(tags: tags, name: { self.selectionTagName($0) })
        return named.isEmpty ? String(localized: "My Selection") : named
    }

    // MARK: Changes

    /// Saves a selection (Settings → Row Order → New Selection): `id` nil makes a new one, whose row goes to the top of
    /// each tab it shows in; an edited one that now shows in another tab goes to the top of that tab too. The record is
    /// dated for sync. Returns its ID; nil for a Kids profile, which cannot change its rows.
    @discardableResult
    func saveSelection(id: String?, name: String, tags: [String], all: Bool, tabs: [String]) -> String? {
        guard canChangeRowOrder else { return nil }
        let previous = id.flatMap { self.selection($0) }
        let draft = SyncSelection(id: previous?.id ?? UUID().uuidString.lowercased(), name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                                  tags: tags, match: all ? SyncSelection.all : SyncSelection.any, tabs: tabs, updatedAt: 0)
        guard var record = SyncSelection.normalized([draft]).first else { return nil }
        if var unchanged = previous {
            unchanged.updatedAt = 0
            if unchanged == record { return record.id }
        }
        let time = syncStamp().syncMilliseconds
        record.updatedAt = time
        let key = Selections.rowKey(record.id)
        var records = profileSelections.filter { $0.id != record.id } + [record]
        records.sort { $0.id.utf16.lexicographicallyPrecedes($1.id.utf16) }
        keepSelections(records)
        for tab in record.tabs where !(previous?.tabs.contains(tab) ?? false) {
            let rows = savedRowOrder(tab: tab)
            guard !(rows?.order.contains(key) ?? false) else { continue }
            keepRowOrderRecord(SyncRows(tab: tab, order: [key] + (rows?.order ?? []), hidden: rows?.hidden ?? [], updatedAt: time).normalized())
        }
        saveSyncState()
        syncSoon()
        return record.id
    }

    /// Deletes a selection, its row taken out of each tab's order; remembered for sync.
    func deleteSelection(_ id: String) {
        guard canChangeRowOrder, selection(id) != nil else { return }
        let time = syncStamp().syncMilliseconds
        let key = Selections.rowKey(id)
        keepSelections(profileSelections.filter { $0.id != id })
        noteDeletedForSync(SyncSelection.keyPrefix + id, at: time)
        for tab in [SyncRows.home, SyncRows.movies, SyncRows.series] {
            guard var rows = savedRowOrder(tab: tab), rows.order.contains(key) || rows.hidden.contains(key) else { continue }
            rows.order.removeAll { $0 == key }
            rows.hidden.removeAll { $0 == key }
            rows.updatedAt = time
            keepRowOrderRecord(rows)
        }
        saveSyncState()
        syncSoon()
    }

    private func keepSelections(_ records: [SyncSelection]) {
        if activeProfile.isMain {
            syncState.selections = records
        } else {
            let id = activeProfile.id.uuidString.lowercased()
            var profile = syncState.profileStates[id] ?? ProfileSyncState()
            profile.selections = records
            syncState.profileStates[id] = profile
        }
    }
}

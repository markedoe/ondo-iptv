import Foundation

/// A selection: a row the viewer makes of tags (docs/SYNC.md, "Selections"): genres, awards, decades and styles, a title
/// matching any of them (or all of them), shown in the Home, Movies or Series tab as a new pick every day. The same tags,
/// rules and tests as the Kotlin `Selections` (android/core); the selections sync (`SyncSelection`, docs/SYNC.md,
/// "Selections").
public enum Selections {
    /// A genre, by its name as `TextNormalizer.normalize` gives it: `genre:drama`.
    public static let genrePrefix = "genre:"
    /// A festival or ceremony of the awards file (`AwardsCatalogue.Award.group`): `award:cannes`.
    public static let awardPrefix = "award:"
    /// A decade, by its first year: `decade:1990`.
    public static let decadePrefix = "decade:"
    /// A style of `CategoryStyles` (the provider's category names): `style:manga`.
    public static let stylePrefix = "style:"
    /// The row key of a selection in a tab's order (`RowOrder`): `sel:<id>`.
    public static let rowPrefix = "sel:"
    /// The match modes (`SyncSelection.match`): a title with any of the tags, or with all of them.
    public static let any = "any"
    public static let all = "all"
    /// How far the daily pick moves a title from its rating: its key is its weighted rating plus up to this much, drawn
    /// anew each day, so a title rated a little lower often comes first, one rated far lower seldom does.
    public static let spread = 2.0

    private static let kinds = [genrePrefix, awardPrefix, decadePrefix, stylePrefix]

    public static func genre(_ name: String) -> String? {
        let normalized = TextNormalizer.normalize(name)
        return normalized.isEmpty ? nil : genrePrefix + normalized
    }

    public static func award(_ group: String) -> String { awardPrefix + group }

    public static func decade(_ year: Int) -> String { decadePrefix + String(year - ((year % 10) + 10) % 10) }

    public static func style(_ style: String) -> String { stylePrefix + style }

    public static func rowKey(_ id: String) -> String { rowPrefix + id }

    /// The selection ID of a row key; nil for another row.
    public static func id(ofRow key: String) -> String? {
        guard key.hasPrefix(rowPrefix) else { return nil }
        let id = String(key.dropFirst(rowPrefix.count))
        return id.isEmpty ? nil : id
    }

    /// Whether this version knows the kind of `tag`; a tag of another kind (a newer version's) is left out of the match.
    public static func isKnown(_ tag: String) -> Bool {
        guard let kind = kinds.first(where: { tag.hasPrefix($0) }) else { return false }
        return tag.utf16.count > kind.utf16.count
    }

    /// What a title is tagged with: its genres (normalised names), the festivals and ceremonies of its awards (won or
    /// selected), its year, and the styles of its category's name (`styles(of:)`).
    public struct Facts: Hashable, Sendable {
        public var genres: Set<String>
        public var awardGroups: Set<String>
        public var year: Int?
        public var styles: Set<String>

        public init(genres: Set<String> = [], awardGroups: Set<String> = [], year: Int? = nil, styles: Set<String> = []) {
            self.genres = genres
            self.awardGroups = awardGroups
            self.year = year
            self.styles = styles
        }
    }

    /// The styles of `CategoryStyles` a category name holds.
    public static func styles(of categoryName: String) -> Set<String> {
        let tokens = CategoryStyles.tokens(of: categoryName)
        return Set(CategoryStyles.order.filter { CategoryStyles.matches(tokens: tokens, rule: $0) })
    }

    /// Whether a title of `facts` has `tag`.
    public static func has(_ facts: Facts, tag: String) -> Bool {
        if tag.hasPrefix(genrePrefix) { return facts.genres.contains(String(tag.dropFirst(genrePrefix.count))) }
        if tag.hasPrefix(awardPrefix) { return facts.awardGroups.contains(String(tag.dropFirst(awardPrefix.count))) }
        if tag.hasPrefix(decadePrefix) {
            guard let start = Int(tag.dropFirst(decadePrefix.count)), let year = facts.year else { return false }
            return year >= start && year < start + 10
        }
        if tag.hasPrefix(stylePrefix) { return facts.styles.contains(String(tag.dropFirst(stylePrefix.count))) }
        return false
    }

    /// Whether a title of `facts` belongs to a selection of `tags`: one of them (`all` false), or each of them. Tags of a
    /// kind this version does not know are left out; with no tag left, no title matches.
    public static func matches(tags: [String], all: Bool, facts: Facts) -> Bool {
        let known = tags.filter(isKnown)
        guard !known.isEmpty else { return false }
        return all ? known.allSatisfy { has(facts, tag: $0) } : known.contains { has(facts, tag: $0) }
    }

    /// The day's order of a selection's titles: each title's key is its TMDb weighted rating (`WeightedRating.weighted`,
    /// at least `RecentReleases.minimumVotes` votes; else `WeightedRating.priorAverage`) plus `spread` times a number
    /// drawn from the selection, the `day` ("2026-11-05", the device's own date) and the title (`key`), the highest
    /// first; the titles `watched` last. The same titles give the same order all day, and another the next day; only
    /// additions and multiplications of exact numbers, so every platform gives the same order.
    public static func dailyOrder<Item>(
        _ items: [Item],
        selectionID: String,
        day: String,
        key: (Item) -> String,
        rating: (Item) -> (average: Double, count: Int)?,
        watched: (Item) -> Bool
    ) -> [Item] {
        let keyed = items.map { item -> (item: Item, key: String, score: Double, watched: Bool) in
            let id = key(item)
            return (item, id, score(rating(item)) + spread * draw("\(selectionID)|\(day)|\(id)"), watched(item))
        }
        return keyed.sorted { a, b in
            if a.watched != b.watched { return !a.watched }
            if a.score != b.score { return a.score > b.score }
            return a.key.isBefore(b.key)
        }.map(\.item)
    }

    /// A title's rating for the pick: its weighted rating with enough votes, else the prior average.
    public static func score(_ rating: (average: Double, count: Int)?) -> Double {
        guard let rating, rating.count >= RecentReleases.minimumVotes,
              let weighted = WeightedRating.weighted(average: rating.average, votes: rating.count) else { return WeightedRating.priorAverage }
        return weighted
    }

    /// A number in [0, 1) drawn from `seed`: `StableHash.fnv1a` mixed (SplitMix64's finaliser), its 53 top bits.
    public static func draw(_ seed: String) -> Double {
        var z = StableHash.fnv1a(seed)
        z = (z ^ (z >> 30)) &* 0xbf58_476d_1ce4_e5b9
        z = (z ^ (z >> 27)) &* 0x94d0_49bb_1331_11eb
        z ^= z >> 31
        return Double(z >> 11) * (1.0 / 9_007_199_254_740_992.0)
    }

    /// The name a selection takes when the viewer gives none: its tags' names, joined ("Drama · Cannes · 1990s").
    public static func defaultName(tags: [String], name: (String) -> String) -> String {
        tags.filter(isKnown).prefix(3).map(name).joined(separator: " · ")
    }

    /// The decades of `years`, the latest first.
    public static func decades(_ years: some Sequence<Int?>) -> [Int] {
        var seen = Set<Int>()
        return years.compactMap { $0 }.filter { (1870...2999).contains($0) }.map { $0 - $0 % 10 }
            .filter { seen.insert($0).inserted }.sorted(by: >)
    }
}

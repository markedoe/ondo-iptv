import Foundation

/// What the screen can show, for the version that plays best: high dynamic range, Dolby Vision, 4K.
public struct PictureSupport: Hashable, Sendable {
    public var hdr: Bool
    public var dolbyVision: Bool
    public var uhd: Bool

    public init(hdr: Bool = false, dolbyVision: Bool = false, uhd: Bool = false) {
        self.hdr = hdr
        self.dolbyVision = dolbyVision
        self.uhd = uhd
    }
}

/// A tag of a version, read from its name and its category's name; `label` is what its badge says (never translated).
public enum VersionTag: Int, CaseIterable, Hashable, Sendable, Comparable {
    case uhd, dolbyVision, hdr, hd, hevc, truefrench, vff, vfq, vf, vostfr, multi

    public var label: String {
        switch self {
        case .uhd: return "4K"
        case .dolbyVision: return "DV"
        case .hdr: return "HDR"
        case .hd: return "HD"
        case .hevc: return "HEVC"
        case .truefrench: return "TRUEFRENCH"
        case .vff: return "VFF"
        case .vfq: return "VFQ"
        case .vf: return "VF"
        case .vostfr: return "VOSTFR"
        case .multi: return "MULTI"
        }
    }

    public static func < (lhs: VersionTag, rhs: VersionTag) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A version's badge: its `tags` ("4K DV VFF"), and its `ordinal` among the versions of its film with the same tags
/// (1 for the first, 2 for the second…): two versions with the same tags are two badges, "4K" and "4K 2". No tag: the
/// app says "Version" in the viewer's language.
public struct VersionBadge: Hashable, Sendable {
    public var tags: [VersionTag]
    public var ordinal: Int

    public init(tags: [VersionTag], ordinal: Int) {
        self.tags = tags
        self.ordinal = ordinal
    }

    /// The tags' words, with the ordinal from the second on; nil when there is no tag (the app names it).
    public var label: String? {
        guard !tags.isEmpty else { return nil }
        let words = tags.map(\.label).joined(separator: " ")
        return ordinal > 1 ? "\(words) \(ordinal)" : words
    }
}

/// The same film's versions in one playlist (4K, Dolby Vision, HD, VOSTFR…), grouped so that lists show each film once
/// and its page offers the versions. Movies only. A derivation of the catalogue, built once per load off the main
/// thread; favourites, history and progress stay per stream. The same rules and tests as Android's `FilmVersions`
/// (android/core); docs/VERSIONS.md. When in doubt, streams are not grouped:
///
/// - **A, the provider's TMDb id** (`Movie.tmdbID`, a positive number only): streams of the same id are grouped when
///   their titles (`titleKey`) agree (`titlesAgree`); one whose title does not stays apart (counted in
///   `Diagnostics.idTitleMismatches`). Streams of two different ids are never grouped, whatever their titles.
/// - **B, no id on one side or both**: the same title (`titleKey`, exactly) and the same year (`Movie.year`, present on
///   both, no tolerance). A stream without an id joins a group with an id only when one id has that title and year:
///   when two do, it stays with the streams without an id (`Diagnostics.ambiguousTitles`).
///
/// Every stream is in exactly one group, in catalogue order; the groups are in the order of their first stream.
public struct FilmVersions: Sendable {
    /// Counts of a grouping, for tests and the diagnostic report.
    public struct Diagnostics: Hashable, Sendable {
        public var streams = 0
        public var groups = 0
        /// Groups of two streams or more.
        public var groupsWithVersions = 0
        /// Streams grouped with another by rule A (beyond the first of each group).
        public var groupedByID = 0
        /// Streams grouped by rule B.
        public var groupedByTitleAndYear = 0
        /// Streams kept apart from others of their TMDb id because the titles differ.
        public var idTitleMismatches = 0
        /// Streams without an id whose title and year two different ids have: not grouped with either.
        public var ambiguousTitles = 0
        /// Of `idTitleMismatches`, the streams whose title starts like another of their id's, kept apart for want of years.
        public var prefixWithoutYears = 0

        public init() {}
    }

    private let movies: [Movie]
    /// Stream index -> its group; nil when nothing is grouped (each stream alone).
    private let groupOf: [Int32]?
    /// Group -> where its streams start in `members`; one more entry than groups.
    private let starts: [Int32]
    /// Stream indices, group after group.
    private let members: [Int32]
    /// Stream id -> its rows (a panel can list one stream twice, in two categories), in catalogue order.
    private let indexByID: [String: [Int32]]
    public let diagnostics: Diagnostics

    /// Whether versions are grouped at all ("Group versions" on, a catalogue with movies).
    public var isGrouped: Bool { groupOf != nil }

    public var groupCount: Int { groupOf == nil ? movies.count : starts.count - 1 }

    /// Each stream alone: "Group versions" off, exactly as before.
    public static func none(_ movies: [Movie] = []) -> FilmVersions {
        var diagnostics = Diagnostics()
        diagnostics.streams = movies.count
        diagnostics.groups = movies.count
        return FilmVersions(movies: movies, groupOf: nil, starts: [0], members: [], indexByID: [:], diagnostics: diagnostics)
    }

    private init(movies: [Movie], groupOf: [Int32]?, starts: [Int32], members: [Int32], indexByID: [String: [Int32]], diagnostics: Diagnostics) {
        self.movies = movies
        self.groupOf = groupOf
        self.starts = starts
        self.members = members
        self.indexByID = indexByID
        self.diagnostics = diagnostics
    }

    /// The group of a stream (its first row), or nil when the catalogue does not list it (or nothing is grouped).
    public func group(of id: String) -> Int? {
        guard let groupOf, let index = indexByID[id]?.first else { return nil }
        return Int(groupOf[Int(index)])
    }

    /// The group of `movie`'s own row (a stream listed twice can be in two groups): the equal row, else its id's first.
    public func group(of movie: Movie) -> Int? {
        guard let groupOf, let rows = indexByID[movie.id], let first = rows.first else { return nil }
        let row = rows.count == 1 ? first : rows.first { movies[Int($0)] == movie } ?? first
        return Int(groupOf[Int(row)])
    }

    /// The streams of a group, in catalogue order.
    public func versions(group: Int) -> [Movie] {
        guard groupOf != nil, group >= 0, group < groupCount else { return [] }
        return (Int(starts[group])..<Int(starts[group + 1])).map { movies[Int(members[$0])] }
    }

    /// The versions of `movie`'s film, `movie` among them, in catalogue order; `movie` alone when it has none.
    public func versions(of movie: Movie) -> [Movie] {
        guard let group = group(of: movie) else { return [movie] }
        return versions(group: group)
    }

    /// Whether `movie`'s film has another version.
    public func hasVersions(_ movie: Movie) -> Bool {
        guard let group = group(of: movie) else { return false }
        return starts[group + 1] - starts[group] > 1
    }

    /// `list` with each film once: the first of its versions in `list` stands for it (a category's list keeps the first
    /// of that category, so a film is in every category one of its versions is in). Unchanged when nothing is grouped.
    public func distinct(_ list: [Movie]) -> [Movie] {
        guard groupOf != nil, list.count > 1 else { return list }
        var seen = [Bool](repeating: false, count: groupCount)
        var result: [Movie] = []
        result.reserveCapacity(list.count)
        for movie in list {
            if let group = group(of: movie) {
                guard !seen[group] else { continue }
                seen[group] = true
            }
            result.append(movie)
        }
        return result.count == list.count ? list : result
    }

    /// `ids` with the other versions of their films: a film is watched, or in My List, when one of its versions is.
    public func expand(_ ids: Set<String>) -> Set<String> {
        guard let groupOf, !ids.isEmpty else { return ids }
        var result = ids
        for id in ids {
            for row in indexByID[id] ?? [] {
                let group = Int(groupOf[Int(row)])
                for k in Int(starts[group])..<Int(starts[group + 1]) { result.insert(movies[Int(members[k])].id) }
            }
        }
        return result
    }

    // MARK: - Rules

    /// At most this many groups of one TMDb id compared with a stream: a panel giving one id to everything stays linear.
    static let maxClustersPerID = 16

    /// A provider's TMDb id when it is one: digits only (spaces around them allowed), above zero. "0", "", "abc", "-3"
    /// and "12.5" are none.
    public static func tmdbID(_ raw: String?) -> Int? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, text.utf8.count <= 10,
              text.utf8.allSatisfy({ (0x30...0x39).contains($0) }), let value = Int(text), value >= 1, value <= Int(Int32.max)
        else { return nil }
        return value
    }

    private static func validID(_ movie: Movie) -> Int {
        guard let id = movie.tmdbID, id > 0 else { return 0 }
        return id
    }

    /// The title the rules compare: the provider's tags left out (`CatalogueMatcher.displayTitle`), case and accents too.
    public static func titleKey(_ name: String) -> String {
        TextNormalizer.normalize(CatalogueMatcher.displayTitle(name))
    }

    /// What rule A makes of two titles of one TMDb id.
    public enum Agreement: Sendable {
        /// One film.
        case same
        /// Another film (another year, another number, another title).
        case different
        /// One title is the other's first words; years would decide, and one is missing: kept apart.
        case needsYears
    }

    /// Rule A's test of two `titleKey`s of one TMDb id with their years. Two years given and different: another film. A
    /// number of one that the other lacks (2, IV, deux, two…): another film. Then the same key, or the same words
    /// ("the matrix" and "matrix", short words such as "the" left out): one film. The words of one the first words of the
    /// other ("le transport" and "le transport heritage"): one film only with the same year given on both, and no word of
    /// a sequel among the others ("part", "chapter", "episode"…). Anything else: another film.
    public static func agreement(_ a: String, _ aYear: Int?, _ b: String, _ bYear: Int?) -> Agreement {
        guard groupable(a), groupable(b) else { return .different }
        if let aYear, let bYear, aYear != bYear { return .different }
        let first = a.split(separator: " ").map(String.init)
        let second = b.split(separator: " ").map(String.init)
        guard first.filter(isNumber).sorted() == second.filter(isNumber).sorted() else { return .different }
        if a == b || contentWords(a) == contentWords(b) { return .same }
        let (short, long) = first.count <= second.count ? (first, second) : (second, first)
        if long.count > short.count && Array(long.prefix(short.count)) == short {
            if long.dropFirst(short.count).contains(where: sequelWords.contains) { return .different }
            return aYear != nil && bYear != nil ? .same : .needsYears
        }
        return .different
    }

    /// `agreement` is `.same`.
    public static func titlesAgree(_ a: String, _ aYear: Int?, _ b: String, _ bYear: Int?) -> Bool {
        agreement(a, aYear, b, bYear) == .same
    }

    /// Roman numerals of sequels (II to XX; "I" is a word too often).
    static let roman: Set<String> = [
        "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii",
        "xix", "xx",
    ]

    /// Numbers written as words in the apps' Latin-script languages (English, French, Spanish, Portuguese).
    static let numberWords: Set<String> = [
        "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix",
        "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "dois", "duas", "quatro", "sete", "oito", "nove", "dez",
    ]

    /// Words of a sequel or a part, in those languages (accents already gone).
    static let sequelWords: Set<String> = [
        "part", "partie", "parte", "chapter", "chapitre", "capitulo", "episode", "episodio", "vol", "volume", "volumen",
        "tome", "tomo", "saga", "returns", "retour", "regreso", "volta",
    ]

    /// A number in a title key: digits (any script), a roman numeral, a number word.
    static func isNumber(_ word: String) -> Bool {
        isNumeral(word) || roman.contains(word) || numberWords.contains(word)
    }

    /// Chinese numerals (simplified), a sequel's number written as a word of its own ("流浪地球 二").
    static let cjkNumerals = Set("〇零一二三四五六七八九十百千两")

    /// Written in figures: decimal digits of any script (2, ٢, २), or Chinese numerals. The same characters as Android's
    /// `FilmVersions.isNumber` (Character.isDigit there).
    static func isNumeral(_ word: String) -> Bool {
        !word.isEmpty && (word.unicodeScalars.allSatisfy { $0.properties.numericType == .decimal } || word.allSatisfy(cjkNumerals.contains))
    }

    public static let latestYear = Calendar(identifier: .gregorian).component(.year, from: Date()) + 1

    /// A stream's title key and year: a year at the end of its name ("Dune 2021", "Dune - 2021") is its year, not a
    /// number of the title, when it is the stream's own year, or the stream has none and it is one (1900 to next year).
    /// A title that is a number only stays one ("1917", "2001").
    public static func keyAndYear(_ name: String, year: Int?, latest: Int = latestYear) -> (key: String, year: Int?) {
        let key = titleKey(name)
        guard let cut = key.lastIndex(of: " ") else { return (key, year) }
        let last = key[key.index(after: cut)...]
        guard last.utf8.count == 4, last.utf8.allSatisfy({ (0x30...0x39).contains($0) }), let number = Int(last) else { return (key, year) }
        if year == number { return (String(key[..<cut]), year) }
        if year == nil, (1900...latest).contains(number) { return (String(key[..<cut]), number) }
        return (key, year)
    }

    /// Whether a title key can stand for a film: not empty, and not made of the providers' tags only ("4k", "multi",
    /// "fr 4k"): such names say nothing of the film.
    public static func groupable(_ key: String) -> Bool {
        !key.isEmpty && !key.split(separator: " ").allSatisfy { CatalogueMatcher.isTagWord(String($0)) }
    }

    /// Rule B's titles: `groupable`, and more than a number ("FR - 2021" is "2021").
    static func groupableByTitle(_ key: String) -> Bool {
        groupable(key) && !key.split(separator: " ").allSatisfy { isNumeral(String($0)) }
    }

    /// Short words of many languages that say nothing of a film ("the", "le", "de"): "The Matrix" and "The Godfather"
    /// share none.
    static let stopWords: Set<String> = [
        "the", "a", "an", "of", "and", "le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "et", "el", "los",
        "las", "y", "il", "lo", "gli", "der", "die", "das", "und", "o", "os", "as", "e",
    ]

    /// A title key's distinct words without `stopWords` (all of them when nothing else is left).
    private static func contentWords(_ key: String) -> Set<String> {
        let all = Set(key.split(separator: " ").map(String.init))
        let content = all.subtracting(stopWords)
        return content.isEmpty ? all : content
    }

    private struct TitleYear: Hashable {
        var key: String
        var year: Int
    }

    private struct TitleEntry {
        var idCluster = -1
        var id = 0
        var ambiguous = false
        var noIDCluster = -1
    }

    /// The versions of `movies` (one playlist's). `grouped` false ("Group versions" off): each stream alone, the
    /// catalogue exactly as it is.
    public static func of(_ movies: [Movie], grouped: Bool = true) -> FilmVersions {
        guard grouped, !movies.isEmpty else { return none(movies) }
        let n = movies.count
        var keys = [String?](repeating: nil, count: n)
        // Each stream's year: its own, or the one its name ends with ("Dune 2021"), read with its key.
        var years = [Int?](repeating: nil, count: n)
        func key(_ i: Int) -> String {
            if let known = keys[i] { return known }
            let (value, year) = keyAndYear(movies[i].name, year: movies[i].year)
            keys[i] = value
            years[i] = year
            return value
        }
        func year(_ i: Int) -> Int? {
            _ = key(i)
            return years[i]
        }
        // A stream's cluster is the index of its cluster's first stream (an anchor).
        var cluster = Array(0..<n)
        // Rule A: per TMDb id, its clusters' anchors (a list through `nextAnchor`, its last in `lastAnchor`).
        var firstAnchor: [Int: Int] = [:]
        var lastAnchor: [Int: Int] = [:]
        var nextAnchor = [Int](repeating: -1, count: n)
        // A cluster's year: its first stream's, else the first one a stream joining it gave. Compared with every stream
        // joining, so one cluster never holds two years.
        var clusterYear = [Int?](repeating: nil, count: n)
        var diagnostics = Diagnostics()
        for i in 0..<n {
            let id = validID(movies[i])
            guard id != 0 else { continue }
            clusterYear[i] = year(i)
            guard var anchor = firstAnchor[id] else {
                firstAnchor[id] = i
                lastAnchor[id] = i
                continue
            }
            var compared = 0
            var joined = false
            var needsYears = false
            while anchor >= 0 && compared < maxClustersPerID {
                switch agreement(key(i), year(i), key(anchor), clusterYear[anchor]) {
                case .same:
                    cluster[i] = anchor
                    joined = true
                    if clusterYear[anchor] == nil { clusterYear[anchor] = year(i) }
                case .needsYears:
                    needsYears = true
                case .different:
                    break
                }
                if joined { break }
                compared += 1
                anchor = nextAnchor[anchor]
            }
            if joined {
                diagnostics.groupedByID += 1
            } else {
                diagnostics.idTitleMismatches += 1
                if needsYears { diagnostics.prefixWithoutYears += 1 }
                if let last = lastAnchor[id] { nextAnchor[last] = i }
                lastAnchor[id] = i
            }
        }
        // Rule B: by title and year. First the clusters with an id, then the streams without one join them.
        var titles: [TitleYear: TitleEntry] = [:]
        for i in 0..<n {
            let movie = movies[i]
            let id = validID(movie)
            guard id != 0 else { continue }
            let k = key(i)
            guard let year = year(i), groupableByTitle(k) else { continue }
            let title = TitleYear(key: k, year: year)
            if var entry = titles[title] {
                if entry.idCluster < 0 {
                    entry.idCluster = cluster[i]
                    entry.id = id
                } else if entry.id != id {
                    entry.ambiguous = true
                }
                titles[title] = entry
            } else {
                titles[title] = TitleEntry(idCluster: cluster[i], id: id)
            }
        }
        for i in 0..<n {
            let movie = movies[i]
            guard validID(movie) == 0 else { continue }
            let k = key(i)
            guard let year = year(i), groupableByTitle(k) else { continue }
            let title = TitleYear(key: k, year: year)
            var entry = titles[title] ?? TitleEntry()
            if entry.ambiguous { diagnostics.ambiguousTitles += 1 }
            let target = entry.idCluster >= 0 && !entry.ambiguous ? entry.idCluster : entry.noIDCluster
            if target >= 0 {
                cluster[i] = target
                diagnostics.groupedByTitleAndYear += 1
            } else {
                entry.noIDCluster = i
                titles[title] = entry
            }
        }
        // Groups numbered in the order of their first stream; streams in catalogue order within each.
        var number = [Int](repeating: -1, count: n)
        var groupOf = [Int32](repeating: 0, count: n)
        var groups = 0
        for i in 0..<n {
            let c = cluster[i]
            if number[c] < 0 {
                number[c] = groups
                groups += 1
            }
            groupOf[i] = Int32(number[c])
        }
        var starts = [Int32](repeating: 0, count: groups + 1)
        for i in 0..<n { starts[Int(groupOf[i]) + 1] += 1 }
        for g in 0..<groups { starts[g + 1] += starts[g] }
        var fill = Array(starts.prefix(groups))
        var members = [Int32](repeating: 0, count: n)
        for i in 0..<n {
            let g = Int(groupOf[i])
            members[Int(fill[g])] = Int32(i)
            fill[g] += 1
        }
        var indexByID: [String: [Int32]] = [:]
        indexByID.reserveCapacity(n)
        for (i, movie) in movies.enumerated() { indexByID[movie.id, default: []].append(Int32(i)) }
        diagnostics.streams = n
        diagnostics.groups = groups
        diagnostics.groupsWithVersions = (0..<groups).filter { starts[$0 + 1] - starts[$0] > 1 }.count
        return FilmVersions(movies: movies, groupOf: groupOf, starts: starts, members: members, indexByID: indexByID, diagnostics: diagnostics)
    }

    // MARK: - Badges and the version shown first

    private static let tagWords: [String: VersionTag] = [
        "4k": .uhd, "uhd": .uhd, "2160p": .uhd,
        "dv": .dolbyVision, "dovi": .dolbyVision,
        "hdr": .hdr, "hdr10": .hdr, "hdr10plus": .hdr,
        "hd": .hd, "fhd": .hd, "1080p": .hd, "720p": .hd,
        "hevc": .hevc, "h265": .hevc, "x265": .hevc,
        "truefrench": .truefrench, "vff": .vff, "vfq": .vfq, "vf": .vf,
        "vostfr": .vostfr, "stfr": .vostfr, "subfr": .vostfr, "frsub": .vostfr, "multi": .multi,
    ]

    /// The tags of a version, from its name and its category's name, in `VersionTag`'s order; 4K leaves HD out.
    public static func tags(name: String, categoryName: String?) -> [VersionTag] {
        var found = Set<VersionTag>()
        for text in [name, categoryName].compactMap({ $0 }) {
            let words = TextNormalizer.normalize(text).split(separator: " ").map(String.init)
            for (index, word) in words.enumerated() {
                if let tag = tagWords[word] { found.insert(tag) }
                let next = index + 1 < words.count ? words[index + 1] : nil
                if word == "dolby" && next == "vision" { found.insert(.dolbyVision) }
                if word == "h" && next == "265" { found.insert(.hevc) }
                // French subtitles in two words: "ST FR", "SUB FR", "FR SUB".
                if (word == "st" || word == "sub") && next == "fr" || word == "fr" && next == "sub" { found.insert(.vostfr) }
            }
        }
        if found.contains(.uhd) { found.remove(.hd) }
        return found.sorted()
    }

    /// The badges of a film's `versions`, in their order; `categoryName` names a stream's category.
    public static func badges(_ versions: [Movie], categoryName: (String) -> String?) -> [VersionBadge] {
        var seen: [[VersionTag]: Int] = [:]
        return versions.map { movie in
            let tags = tags(name: movie.name, categoryName: categoryName(movie.categoryID))
            let ordinal = (seen[tags] ?? 0) + 1
            seen[tags] = ordinal
            return VersionBadge(tags: tags, ordinal: ordinal)
        }
    }

    /// How well a version suits the screen: 4K on a 4K screen, HD; Dolby Vision or HDR only where the screen shows it
    /// (a Dolby Vision stream elsewhere can show wrong colours).
    public static func score(_ tags: [VersionTag], support: PictureSupport) -> Int {
        var score = 0
        if tags.contains(.dolbyVision) { score += support.dolbyVision ? 4 : -40 }
        if tags.contains(.hdr) { score += support.hdr ? 2 : -20 }
        if tags.contains(.uhd) { score += support.uhd ? 8 : 1 }
        if tags.contains(.hd) { score += 4 }
        return score
    }

    /// The version a film's card plays and its page selects first: the one in progress (`inProgress`); else the one
    /// the viewer chose last (`chosen`, most recent first); else the one that suits the screen best (`score`); else the
    /// first.
    public static func defaultVersion(
        _ versions: [Movie],
        inProgress: String?,
        chosen: [String],
        support: PictureSupport,
        categoryName: (String) -> String?
    ) -> Movie? {
        guard versions.count > 1 else { return versions.first }
        if let inProgress, let movie = versions.first(where: { $0.id == inProgress }) { return movie }
        if !chosen.isEmpty {
            let ids = Set(versions.map(\.id))
            if let id = chosen.first(where: { ids.contains($0) }), let movie = versions.first(where: { $0.id == id }) { return movie }
        }
        var best = versions[0]
        var bestScore = Int.min
        for movie in versions {
            let value = score(tags(name: movie.name, categoryName: categoryName(movie.categoryID)), support: support)
            if value > bestScore {
                best = movie
                bestScore = value
            }
        }
        return best
    }
}

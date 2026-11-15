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

/// Why a film's card plays the version it plays (`FilmVersions.defaultChoice`).
public enum DefaultReason: String, Hashable, Sendable {
    /// The film has one version.
    case only
    /// The viewer is watching it.
    case inProgress
    /// The viewer chose it last on this device.
    case chosen
    /// It suits the screen best (4K on a 4K screen, no Dolby Vision where the screen cannot show it).
    case bestForScreen
    /// Nothing sets one apart: the first in the catalogue.
    case first
}

/// What a version's tag says about it, in the order a version's chip names them (docs/VERSIONS.md).
public enum VersionAspect: Int, CaseIterable, Hashable, Sendable, Comparable {
    case quality, range, threeD, edition, codec, language, subtitles
    /// A copy filmed or leaked before the film's release (CAM, TS, TC, SCR, R5, WORKPRINT): named last, played last.
    case source

    public static func < (lhs: VersionAspect, rhs: VersionAspect) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A tag of a version, read from its name and its category's name. Its chip says it in words (`VersionWords`): "DV" is
/// "Dolby Vision", "VOSTFR" "Original, French subtitles". In the order of `VersionAspect`, the best quality first.
public enum VersionTag: Int, CaseIterable, Hashable, Sendable, Comparable {
    case uhd, fhd, hd, sd, dolbyVision, hdr, threeD, imax, hevc
    case multi, vff, vfq, vfi, vf, vo, english, spanish, german, italian, portuguese, arabic
    case vostfr, vost
    case cam, telesync, telecine, screener, r5, workprint

    public var aspect: VersionAspect {
        switch self {
        case .uhd, .fhd, .hd, .sd: return .quality
        case .dolbyVision, .hdr: return .range
        case .threeD: return .threeD
        case .imax: return .edition
        case .hevc: return .codec
        case .multi, .vff, .vfq, .vfi, .vf, .vo, .english, .spanish, .german, .italian, .portuguese, .arabic: return .language
        case .vostfr, .vost: return .subtitles
        case .cam, .telesync, .telecine, .screener, .r5, .workprint: return .source
        }
    }

    /// Said with the other picture tags without a dot ("4K Dolby Vision"); a language or subtitles apart ("4K · English").
    public var isPicture: Bool { aspect < .language }

    public static func < (lhs: VersionTag, rhs: VersionTag) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// The words of a version's chip in the viewer's language (the app's strings; English by default). The names of formats
/// ("4K", "Dolby Vision", "HDR", "3D", "IMAX", "HEVC") are the same in every language.
public struct VersionWords {
    /// A version without a tag next to versions with one: the provider's plain stream.
    public var standard = "Standard"
    /// Versions nothing tells apart, numbered: "Version 1", "Version 2".
    public var numbered: (Int) -> String = { "Version \($0)" }
    public var fullHD = "Full HD"
    public var hd = "HD"
    public var sd = "SD"
    public var multilingual = "Multilingual"
    public var french = "French"
    public var frenchFrance = "French (France)"
    public var frenchQuebec = "French (Quebec)"
    public var frenchInternational = "French (international)"
    public var original = "Original language"
    public var english = "English"
    public var spanish = "Spanish"
    public var german = "German"
    public var italian = "Italian"
    public var portuguese = "Portuguese"
    public var arabic = "Arabic"
    public var frenchSubtitles = "Original, French subtitles"
    public var subtitled = "Original, subtitled"
    /// A copy filmed or leaked before the film's release: "Cinema copy (CAM)" (the provider's marker).
    public var cinemaCopy: (String) -> String = { "Cinema copy (\($0))" }

    public init() {}

    public func word(_ tag: VersionTag) -> String {
        switch tag {
        case .uhd: return "4K"
        case .fhd: return fullHD
        case .hd: return hd
        case .sd: return sd
        case .dolbyVision: return "Dolby Vision"
        case .hdr: return "HDR"
        case .threeD: return "3D"
        case .imax: return "IMAX"
        case .hevc: return "HEVC"
        case .multi: return multilingual
        case .vff: return frenchFrance
        case .vfq: return frenchQuebec
        case .vfi: return frenchInternational
        case .vf: return french
        case .vo: return original
        case .english: return english
        case .spanish: return spanish
        case .german: return german
        case .italian: return italian
        case .portuguese: return portuguese
        case .arabic: return arabic
        case .vostfr: return frenchSubtitles
        case .vost: return subtitled
        case .cam: return cinemaCopy("CAM")
        case .telesync: return cinemaCopy("TS")
        case .telecine: return cinemaCopy("TC")
        case .screener: return cinemaCopy("SCR")
        case .r5: return cinemaCopy("R5")
        case .workprint: return cinemaCopy("Workprint")
        }
    }
}

/// One chip of a film's page (`FilmVersions.choices`): versions that play the same thing (the same chip tags, the same
/// container, the same name once normalised: a panel filing one stream under two categories) are one choice. The same as
/// Android's `VersionChoice`.
public struct VersionChoice: Hashable, Sendable {
    /// What its chip says.
    public var label: String
    /// Its versions, in catalogue order.
    public var members: [Movie]
    /// The member it plays (`FilmVersions.preferred`): the one in progress, else the one chosen last, else the first.
    public var preferred: Movie

    public init(label: String, members: [Movie], preferred: Movie) {
        self.label = label
        self.members = members
        self.preferred = preferred
    }

    /// Its first member's id: the same whichever member plays (the chip's identity, focus and tests).
    public var id: String { members.first?.id ?? preferred.id }

    /// `preferred`, then the others in catalogue order: the streams tried in turn when one fails to start.
    public var playOrder: [Movie] { [preferred] + members.filter { $0.id != preferred.id } }

    public func contains(_ id: String) -> Bool { members.contains { $0.id == id } }
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
        let first = a.spaceSplit().map(String.init)
        let second = b.spaceSplit().map(String.init)
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
        !key.isEmpty && !key.spaceSplit().allSatisfy { CatalogueMatcher.isTagWord(String($0)) }
    }

    /// Rule B's titles: `groupable`, and more than a number ("FR - 2021" is "2021").
    static func groupableByTitle(_ key: String) -> Bool {
        groupable(key) && !key.spaceSplit().allSatisfy { isNumeral(String($0)) }
    }

    /// Short words of many languages that say nothing of a film ("the", "le", "de"): "The Matrix" and "The Godfather"
    /// share none.
    static let stopWords: Set<String> = [
        "the", "a", "an", "of", "and", "le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "et", "el", "los",
        "las", "y", "il", "lo", "gli", "der", "die", "das", "und", "o", "os", "as", "e",
    ]

    /// A title key's distinct words without `stopWords` (all of them when nothing else is left).
    private static func contentWords(_ key: String) -> Set<String> {
        let all = Set(key.spaceSplit().map(String.init))
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
        "fhd": .fhd, "1080p": .fhd, "hd": .hd, "720p": .hd,
        "sd": .sd, "480p": .sd, "576p": .sd,
        "dv": .dolbyVision, "dovi": .dolbyVision,
        "hdr": .hdr, "hdr10": .hdr, "hdr10plus": .hdr,
        "3d": .threeD, "sbs": .threeD, "hsbs": .threeD, "imax": .imax,
        "hevc": .hevc, "h265": .hevc, "x265": .hevc,
        "multi": .multi, "dual": .multi,
        "truefrench": .vff, "vff": .vff, "vfq": .vfq, "vfi": .vfi,
        "vf": .vf, "french": .vf, "vo": .vo, "english": .english,
        "spanish": .spanish, "espanol": .spanish, "castellano": .spanish,
        "german": .german, "deutsch": .german, "italian": .italian,
        "italiano": .italian, "portuguese": .portuguese, "portugues": .portuguese,
        "arabic": .arabic,
        "vostfr": .vostfr, "stfr": .vostfr, "subfr": .vostfr, "frsub": .vostfr,
        "vost": .vost, "vosta": .vost, "vosten": .vost, "subbed": .vost,
    ]

    /// A language's code: a tag only in capitals, at either end of a name or a category ("FR - Sandstorm", "EN | Movies",
    /// "Sandstorm (DE)"), where "It" and "De" are words of titles.
    private static let edgeCodes: [String: VersionTag] = [
        "fr": .vf, "en": .english, "eng": .english, "es": .spanish,
        "esp": .spanish, "de": .german, "ger": .german, "it": .italian,
        "ita": .italian, "pt": .portuguese, "br": .portuguese, "ar": .arabic,
    ]

    /// Words of a provider's tag in two words ("Dolby Vision", "H 265", "ST FR"): never a word of a version's own.
    private static let tagParts: Set<String> = ["dolby", "vision", "h", "265", "st", "sub", "fr"]

    private static let nameSeparators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "|[](){}:·_,/"))

    /// Tag words that are also ordinary words of titles ("The English Orchard", "French Lantern", "Dual Harbor"): in a
    /// version's name, tags only outside the title its versions share (`labelTags` of a film's versions).
    private static let titleLike: Set<String> = [
        "english", "french", "spanish", "espanol", "castellano", "german", "deutsch", "italian", "italiano", "portuguese",
        "portugues", "arabic", "dual", "vo", "sd",
    ]

    /// A language's code before a name, set apart by a provider's delimiter: "FR - …", "|EN| …", "[IT] …", "DE: …"; not a
    /// title's first word ("IT WAITS BELOW").
    // swiftlint:disable:next force_try
    private static let prefixCode = try! NSRegularExpression(pattern: #"^\s*[\[(|]?\s*([A-Z]{2,3})\s*[\])|:\-–·]"#)
    /// A language's code after a name, set apart the same way: "… (DE)", "… - EN", "… |FR|".
    // swiftlint:disable:next force_try
    private static let suffixCode = try! NSRegularExpression(pattern: #"[\[(|\-–:·]\s*([A-Z]{2,3})\s*[\])|]?\s*$"#)

    /// The tags of a version, from its name and its category's name, in `VersionTag`'s order; 4K leaves HD out.
    public static func tags(name: String, categoryName: String?) -> [VersionTag] {
        tags(name: name, categoryName: categoryName, titleWords: [])
    }

    /// `tags`, the words of `titleWords` that are also title words (`titleLike`) not read as tags in the name.
    private static func tags(name: String, categoryName: String?, titleWords: Set<String>) -> [VersionTag] {
        var found = Set<VersionTag>()
        for (which, text) in [name, categoryName].enumerated() {
            guard let text else { continue }
            let words = TextNormalizer.normalize(text).split(separator: " ").map(String.init)
            for (index, word) in words.enumerated() {
                if which == 0 && titleWords.contains(word) && titleLike.contains(word) { continue }
                if let tag = tagWords[word] { found.insert(tag) }
                let next = index + 1 < words.count ? words[index + 1] : nil
                if word == "dolby" && next == "vision" { found.insert(.dolbyVision) }
                if word == "h" && next == "265" { found.insert(.hevc) }
                // French subtitles in two words: "ST FR", "SUB FR", "FR SUB".
                if (word == "st" || word == "sub") && next == "fr" || word == "fr" && next == "sub" { found.insert(.vostfr) }
            }
            // A copy filmed or leaked before release, in the name or the category ("Cinema CAM"), as written.
            for word in writtenWords(text) { if let tag = sourceTag(word) { found.insert(tag) } }
        }
        return tidy(found)
    }

    /// A copy filmed or leaked before the film's release, in any letter case ("HDCAM", "Telesync", "DVDScr").
    private static let sourceWords: [String: VersionTag] = [
        "hdcam": .cam, "hdts": .telesync, "telesync": .telesync, "telecine": .telecine, "dvdscr": .screener, "workprint": .workprint,
    ]

    /// The same, also words or letters of titles ("Cam", "Ts", "Screener"): a tag only in capitals ("CAM", "TS").
    private static let sourceCaps: [String: VersionTag] = [
        "cam": .cam, "ts": .telesync, "tc": .telecine, "scr": .screener, "screener": .screener, "r5": .r5,
    ]

    /// A provider's word of a copy filmed or leaked before release (`sourceWords`, `sourceCaps`), or nil. The same as
    /// Android's.
    static func sourceTag(_ word: String) -> VersionTag? {
        let lower = word.lowercased()
        if let tag = sourceWords[lower] { return tag }
        guard let tag = sourceCaps[lower], word == word.uppercased() else { return nil }
        return tag
    }

    /// `text`'s words as written, split at every character that is no letter or digit (no regular expression).
    private static func writtenWords(_ text: String) -> [String] {
        text.split(whereSeparator: { !($0.isLetter || $0.isNumber) }).map(String.init)
    }

    /// A copy filmed or leaked before the film's release (CAM, TS, TC, SCR, R5, WORKPRINT).
    public static func isEarlyCopy(_ tags: [VersionTag]) -> Bool { tags.contains { $0.aspect == .source } }

    /// One quality (the best) and one French; subtitles mean the original language, not a dub.
    private static func tidy(_ tags: Set<VersionTag>) -> [VersionTag] {
        var found = tags
        if found.contains(.uhd) { found.subtract([.fhd, .hd, .sd]) }
        if found.contains(.fhd) { found.subtract([.hd, .sd]) }
        if found.contains(.hd) { found.remove(.sd) }
        if found.contains(.vostfr) { found.remove(.vost) }
        if found.contains(.vostfr) || found.contains(.vost) { found.subtract([.vo, .vf]) }
        if found.contains(.vff) || found.contains(.vfq) || found.contains(.vfi) { found.remove(.vf) }
        return found.sorted()
    }

    private static func nameWords(_ text: String, trimming: String) -> [String] {
        let edges = CharacterSet(charactersIn: trimming)
        return text.components(separatedBy: nameSeparators).map { $0.trimmingCharacters(in: edges) }.filter { !$0.isEmpty }
    }

    /// `tags`, with a language's code in capitals at either end of the name or the category, set apart by a provider's
    /// delimiter (`prefixCode`, `suffixCode`, `edgeCodes`): a chip's tags. `titleWords`: see `tags`.
    public static func labelTags(name: String, categoryName: String?, titleWords: Set<String> = []) -> [VersionTag] {
        var found = Set(tags(name: name, categoryName: categoryName, titleWords: titleWords))
        for text in [name, categoryName].compactMap({ $0 }) {
            for pattern in [prefixCode, suffixCode] {
                guard let match = pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                      let range = Range(match.range(at: 1), in: text),
                      let tag = edgeCodes[text[range].lowercased()] else { continue }
                found.insert(tag)
            }
        }
        return tidy(found)
    }

    /// The chip tags of a film's `versions`, in their order: a title word that is also a tag ("English", "French",
    /// "Dual") counts only outside the words every version's name shares ("The English Orchard" and "The English
    /// Orchard MULTI" differ by Multilingual only).
    public static func labelTags(_ versions: [Movie], categoryName: (String) -> String?) -> [[VersionTag]] {
        let shared: Set<String> = versions.count < 2 ? [] : versions
            .map { Set(TextNormalizer.normalize($0.name).split(separator: " ").map(String.init)) }
            .reduce(nil as Set<String>?) { $0?.intersection($1) ?? $1 } ?? []
        return versions.map { labelTags(name: $0.name, categoryName: categoryName($0.categoryID), titleWords: shared) }
    }

    /// Apostrophes as providers write them: "Director's", "Director’s" and "Directors" are one name.
    private static let apostrophes: Set<Character> = ["'", "’", "‘", "ʼ", "`", "´"]

    /// A version's name as `choices` compares it: case, accents, apostrophes and punctuation left out, and the words its
    /// chip tags come from (the provider's prefix "|FR|", "4K", "Dolby Vision") and years. The words the chips have no
    /// name for ("DUB") and those of an edition ("Extended Cut") stay: they tell versions apart. The same as Android's.
    public static func nameKey(_ name: String) -> String {
        // A copy's marker ("CAM") is its tag, not a word of its name.
        let written = writtenWords(String(name.filter { !apostrophes.contains($0) })).filter { sourceTag($0) == nil }.joined(separator: " ")
        let words = TextNormalizer.normalize(written).split(separator: " ").map(String.init)
        // A language's code at an end only where a provider's delimiter sets it apart, as `labelTags` reads it.
        let range = NSRange(name.startIndex..., in: name)
        let prefix = prefixCode.firstMatch(in: name, range: range) != nil
        let suffix = suffixCode.firstMatch(in: name, range: range) != nil
        var kept: [String] = []
        var i = 0
        while i < words.count {
            let word = words[i]
            let next = i + 1 < words.count ? words[i + 1] : nil
            let pair = (word == "dolby" && next == "vision") || (word == "h" && next == "265")
                || ((word == "st" || word == "sub") && next == "fr") || (word == "fr" && next == "sub")
            if pair {
                i += 2
                continue
            }
            let edge = (i == 0 && prefix) || (i == words.count - 1 && suffix)
            if tagWords[word] == nil && !isYear(word) && !(edge && edgeCodes[word] != nil) { kept.append(word) }
            i += 1
        }
        return kept.joined(separator: " ")
    }

    /// The member of a choice that plays: the one in progress (`inProgress`, most recent first), else the one the viewer
    /// chose last (`chosen`, most recent first), else the first in catalogue order. The rules of `defaultChoice`.
    public static func preferred(_ members: [Movie], inProgress: [String], chosen: [String]) -> Movie? {
        for id in inProgress { if let movie = members.first(where: { $0.id == id }) { return movie } }
        for id in chosen { if let movie = members.first(where: { $0.id == id }) { return movie } }
        return members.first
    }

    /// A film's chips: its `versions` (catalogue order) with the same chip tags (`labelTags`), the same container
    /// (`container(of:)`) and the same `nameKey` are one choice, labelled as one version ("Standard", "4K Dolby Vision"),
    /// in the order of its first member; a category's name never sets versions apart. Each plays its `preferred` member
    /// (`inProgress`, `chosen`); the others are its fallbacks (`VersionChoice.playOrder`). One choice alone reads
    /// "Standard". Empty without versions. The same rules as Android's.
    ///
    /// `specs` gives what is known of a stream's picture and sound (`StreamSpecs`: measured by the player, else the
    /// provider's): a chip says them ("1080p · 5.1", "4K Dolby Vision · Atmos"), and versions the rules above make one
    /// stay apart when their known specs differ (`specKey`); a version whose specs are unknown stays with the first of
    /// them. Without specs, the chips are the same as before.
    public static func choices(
        _ versions: [Movie],
        categoryName: (String) -> String?,
        words: VersionWords = VersionWords(),
        inProgress: [String] = [],
        chosen: [String] = [],
        specs: (String) -> StreamSpecs? = { _ in nil }
    ) -> [VersionChoice] {
        guard !versions.isEmpty else { return [] }
        let tags = labelTags(versions, categoryName: categoryName)
        let known: [StreamSpecs?] = versions.map { movie in specs(movie.id).flatMap { $0.isEmpty ? nil : $0 } }
        struct Key: Hashable {
            var tags: [VersionTag]
            var container: String?
            var name: String
        }
        var order: [Key] = []
        var groups: [Key: [Int]] = [:]
        for (i, movie) in versions.enumerated() {
            let key = Key(tags: tags[i], container: container(of: movie), name: nameKey(movie.name))
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(i)
        }
        // Versions whose known specs differ (4K measured, 1080p measured) are two chips, whatever their names.
        let all = order.flatMap { splitBySpecs(groups[$0] ?? [], known: known) }.sorted { $0[0] < $1[0] }
        // A proper copy's chips before those of a copy filmed or leaked before release, each in catalogue order.
        let members = all.filter { !isEarlyCopy(tags[$0[0]]) } + all.filter { isEarlyCopy(tags[$0[0]]) }
        // A chip's specs: those of its first member that has some.
        let chipSpecs: [StreamSpecs?] = members.map { group in group.lazy.compactMap { known[$0] }.first }
        let texts = members.count < 2
            ? [words.standard]
            : labels(members.map { versions[$0[0]] }, tags: members.map { tags[$0[0]] }, specs: chipSpecs, words: words)
        return members.enumerated().map { k, group in
            let movies = group.map { versions[$0] }
            return VersionChoice(label: texts[k], members: movies, preferred: preferred(movies, inProgress: inProgress, chosen: chosen) ?? movies[0])
        }
    }

    /// What a film's `versions` say on their chips, in their order, in `words`. Each names what sets it apart from the
    /// others, in `VersionAspect`'s order ("4K Dolby Vision", "HD · Original, French subtitles"): a tag they all share is
    /// left out. Two that would read the same add the words of their names the others lack ("Extended Cut"); a version
    /// without a tag next to tagged ones is "Standard". Labels still the same add, in turn, their container ("Standard ·
    /// MKV", "Standard · MP4"), then the provider's words the chips have no word for ("DUB"); labels still the same are
    /// numbered ("4K 1", "4K 2"; "Version 1", "Version 2"): no two versions read the same. A category's name is never one
    /// of those words (only its tags count). `categoryName` names a stream's category. The page's chips are `choices`:
    /// versions that play the same thing are one. The same rules as Android's.
    public static func labels(
        _ versions: [Movie],
        categoryName: (String) -> String?,
        words: VersionWords = VersionWords(),
        specs: (String) -> StreamSpecs? = { _ in nil }
    ) -> [String] {
        labels(
            versions,
            tags: labelTags(versions, categoryName: categoryName),
            specs: versions.map { movie in specs(movie.id).flatMap { $0.isEmpty ? nil : $0 } },
            words: words
        )
    }

    /// A word of a chip and its place: `VersionAspect`'s order, its sound after its picture (`soundSlot`).
    private struct Part: Hashable {
        var slot: Int
        var word: String
    }

    /// Where a chip's sound goes: after its picture's words, before its language.
    private static let soundSlot = VersionAspect.codec.rawValue * 2 + 1

    /// The last place a chip's word can take.
    private static let lastSlot = (VersionAspect.allCases.last?.rawValue ?? 0) * 2

    private static func slot(of aspect: VersionAspect) -> Int { aspect.rawValue * 2 }

    /// A chip's words, place by place (`VersionAspect`'s order, its sound after its picture): its specs' where they are
    /// known ("1080p" for the tag "4K" of a stream measured 1080p, nothing for "HDR" in the name of one measured SDR), its
    /// tags' elsewhere. Its video codec only sets apart chips that read the same (`labels`).
    private static func chipParts(_ tags: [VersionTag], specs: StreamSpecs?, words: VersionWords) -> [Part] {
        let resolution = specs?.resolution
        var parts: [Part] = []
        for place in 0...lastSlot {
            for tag in tags where slot(of: tag.aspect) == place {
                let known: Bool
                switch tag.aspect {
                case .quality: known = resolution != nil
                case .range: known = specs?.range != nil
                case .codec: known = specs?.videoCodec != nil
                default: known = false
                }
                if !known { parts.append(Part(slot: place, word: words.word(tag))) }
            }
            if place == slot(of: .quality), let resolution { parts.append(Part(slot: place, word: resolution)) }
            if place == slot(of: .range), let range = specs?.rangeLabel { parts.append(Part(slot: place, word: range)) }
            if place == soundSlot, let sound = specs?.chipAudio { parts.append(Part(slot: place, word: sound)) }
        }
        return parts
    }

    /// Picture words with a space between them, the others after them with a dot: "4K Dolby Vision · Atmos · English".
    private static func joined(_ parts: [Part]) -> String {
        let picture = parts.filter { $0.slot < soundSlot }.map(\.word).joined(separator: " ")
        return ([picture].filter { !$0.isEmpty } + parts.filter { $0.slot >= soundSlot }.map(\.word)).joined(separator: " · ")
    }

    /// What a chip says of its specs alone ("1080p · 5.1"), when it would say nothing else; nil when none are known.
    private static func specText(_ specs: StreamSpecs?) -> String? {
        guard let specs else { return nil }
        let parts = [
            specs.resolution.map { Part(slot: 0, word: $0) },
            specs.rangeLabel.map { Part(slot: slot(of: .range), word: $0) },
            specs.chipAudio.map { Part(slot: soundSlot, word: $0) },
        ].compactMap { $0 }
        let text = joined(parts)
        return text.isEmpty ? nil : text
    }

    /// What `choices` compares of two versions' specs: their picture's class, range and codec, their sound. Nil when none
    /// is known.
    public static func specKey(_ specs: StreamSpecs?) -> [String?]? {
        guard let specs, !specs.isEmpty else { return nil }
        return [specs.resolution, specs.rangeLabel, specs.videoCodec, specs.audioLabel]
    }

    /// `group`'s versions (catalogue order) apart by their known specs (`specKey`), in the order of each one's first
    /// version; the versions whose specs are unknown stay with the first. One group when the known ones agree.
    private static func splitBySpecs(_ group: [Int], known: [StreamSpecs?]) -> [[Int]] {
        var keys: [[String?]] = []
        for i in group {
            if let key = specKey(known[i]), !keys.contains(key) { keys.append(key) }
        }
        guard keys.count > 1 else { return [group] }
        var parts = Array(repeating: [Int](), count: keys.count)
        for i in group {
            let index = specKey(known[i]).flatMap { keys.firstIndex(of: $0) } ?? 0
            parts[index].append(i)
        }
        return parts.map { $0.sorted() }
    }

    private static func labels(_ versions: [Movie], tags: [[VersionTag]], specs: [StreamSpecs?], words: VersionWords) -> [String] {
        let parts = tags.indices.map { chipParts(tags[$0], specs: specs[$0], words: words) }
        // The places the versions differ in: a word all of them have, or none, tells none apart.
        let differing = Set((0...lastSlot).filter { place in
            Set(parts.map { list in list.filter { $0.slot == place }.map(\.word) }).count > 1
        })
        var texts = parts.map { list in joined(list.filter { differing.contains($0.slot) }) }
        // Versions that would read the same: the words of their names the others lack.
        let extras = extraWords(versions)
        for same in Dictionary(grouping: texts.indices, by: { texts[$0] }).values {
            guard same.count > 1, Set(same.map { extras[$0] }).count > 1 else { continue }
            for i in same where !extras[i].isEmpty {
                texts[i] = [texts[i], extras[i]].filter { !$0.isEmpty }.joined(separator: " · ")
            }
        }
        let anyTagged = texts.contains { !$0.isEmpty }
        // "Standard" only for a version nothing is known of: one with specs says them ("1080p · 5.1").
        var labels = texts.indices.map { i -> String in
            if !texts[i].isEmpty { return texts[i] }
            return specText(specs[i]) ?? (anyTagged ? words.standard : "")
        }
        // Still the same: what else sets them apart, in this order: their video codec, their sound's codec ("HEVC",
        // "AAC"), their container ("MKV", "MP4"), then the provider's words the chips have no name for ("DUB"). Never
        // their categories' names: viewers see no category on a film's page, and a panel files one stream under several.
        setApart(&labels) { same in same.map { specs[$0]?.videoCodec ?? "" } }
        setApart(&labels) { same in same.map { specs[$0]?.audioCodec ?? "" } }
        setApart(&labels) { same in same.map { container(of: versions[$0]) ?? "" } }
        setApart(&labels) { same in differingWords(same.map { versions[$0].name }, tagsOnly: true) }
        if labels.contains(where: { !$0.isEmpty }) {
            labels = labels.indices.map { labels[$0].isEmpty ? (specText(specs[$0]) ?? words.standard) : labels[$0] }
        }
        // Still the same: numbered, each of them.
        let counts = labels.reduce(into: [String: Int]()) { $0[$1, default: 0] += 1 }
        var seen: [String: Int] = [:]
        let numbered = labels.map { label -> String in
            guard counts[label, default: 0] > 1 else { return label.isEmpty ? words.numbered(1) : label }
            let n = seen[label, default: 0] + 1
            seen[label] = n
            return label.isEmpty ? words.numbered(n) : "\(label) \(n)"
        }
        // A number that made another version's own label ("4K 2" next to two "4K"): their positions tell them apart.
        guard Set(numbered).count != numbered.count else { return numbered }
        return numbered.enumerated().map { "\($1) (\($0 + 1))" }
    }

    /// The words of each version's name that are not in every other one's, tags and years left out ("Extended Cut").
    private static func extraWords(_ versions: [Movie]) -> [String] {
        let split = versions.map { nameWords($0.name, trimming: "-.*+\"") }
        let common = split.map { words in Set(words.flatMap { TextNormalizer.normalize($0).split(separator: " ").map(String.init) }) }
            .reduce(nil as Set<String>?) { $0?.intersection($1) ?? $1 } ?? []
        return split.map { words in
            words.filter { word in
                let parts = TextNormalizer.normalize(word).split(separator: " ").map(String.init)
                return !parts.isEmpty && !parts.allSatisfy { common.contains($0) || isNoise($0) }
            }.joined(separator: " ")
        }
    }

    /// A provider's tag, a part of one, or a year: never a word that tells a version apart.
    private static func isNoise(_ word: String) -> Bool {
        tagWords[word] != nil || edgeCodes[word] != nil || tagParts.contains(word) || sourceWords[word] != nil || sourceCaps[word] != nil
            || CatalogueMatcher.isTagWord(word) || isYear(word)
    }

    /// How well a version suits the screen: 4K on a 4K screen, HD; Dolby Vision or HDR only where the screen shows it
    /// (a Dolby Vision stream elsewhere can show wrong colours).
    public static func score(_ tags: [VersionTag], support: PictureSupport) -> Int {
        // A copy filmed or leaked before release below every other.
        var score = isEarlyCopy(tags) ? -1_000 : 0
        if tags.contains(.dolbyVision) { score += support.dolbyVision ? 4 : -40 }
        if tags.contains(.hdr) { score += support.hdr ? 2 : -20 }
        if tags.contains(.uhd) { score += support.uhd ? 8 : 1 }
        if tags.contains(.hd) || tags.contains(.fhd) { score += 4 }
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
        defaultChoice(versions, inProgress: inProgress, chosen: chosen, support: support, categoryName: categoryName)?.movie
    }

    /// `defaultVersion` and why it is that one; nil without versions.
    public static func defaultChoice(
        _ versions: [Movie],
        inProgress: String?,
        chosen: [String],
        support: PictureSupport,
        categoryName: (String) -> String?
    ) -> (movie: Movie, reason: DefaultReason)? {
        guard versions.count > 1 else { return versions.first.map { ($0, .only) } }
        if let inProgress, let movie = versions.first(where: { $0.id == inProgress }) { return (movie, .inProgress) }
        if !chosen.isEmpty {
            let ids = Set(versions.map(\.id))
            if let id = chosen.first(where: { ids.contains($0) }), let movie = versions.first(where: { $0.id == id }) { return (movie, .chosen) }
        }
        var best = versions[0]
        var bestScore = Int.min
        var scores = Set<Int>()
        for movie in versions {
            let value = score(tags(name: movie.name, categoryName: categoryName(movie.categoryID)), support: support)
            scores.insert(value)
            if value > bestScore {
                best = movie
                bestScore = value
            }
        }
        return (best, scores.count > 1 ? .bestForScreen : .first)
    }
}

/// What tells apart the versions whose chips would read the same (`FilmVersions.labels`).
extension FilmVersions {
    /// Labels that read the same get what `values` gives each of them (in the order of the group's indices), when those
    /// differ: "Standard · MKV" next to "Standard · MP4".
    private static func setApart(_ labels: inout [String], _ values: ([Int]) -> [String]) {
        var groups: [String: [Int]] = [:]
        var order: [String] = []
        for (index, label) in labels.enumerated() {
            if groups[label] == nil { order.append(label) }
            groups[label, default: []].append(index)
        }
        for label in order {
            guard let same = groups[label], same.count > 1 else { continue }
            let found = values(same)
            guard Set(found).count > 1 else { continue }
            for (k, i) in same.enumerated() where !found[k].isEmpty {
                labels[i] = [labels[i], found[k]].filter { !$0.isEmpty }.joined(separator: " · ")
            }
        }
    }

    /// The words of each text that are not in every other one, years left out. The providers' tags are left out too, or,
    /// with `tagsOnly`, they are the only words kept: those of a name the chips have no word for ("DUB", "TR").
    private static func differingWords(_ texts: [String], tagsOnly: Bool = false) -> [String] {
        let split = texts.map { nameWords($0, trimming: "-.*+\"") }
        let common = split.map { words in Set(words.flatMap { TextNormalizer.normalize($0).split(separator: " ").map(String.init) }) }
            .reduce(nil as Set<String>?) { $0?.intersection($1) ?? $1 } ?? []
        return split.map { words in
            words.filter { word in
                let parts = TextNormalizer.normalize(word).split(separator: " ").map(String.init)
                guard !parts.isEmpty, !parts.allSatisfy({ common.contains($0) || isYear($0) || (!tagsOnly && isNoise($0)) }) else { return false }
                return !tagsOnly || parts.allSatisfy { common.contains($0) || isNoise($0) }
            }.joined(separator: " ")
        }
    }

    /// A version's container in capitals ("MKV", "MP4", "HLS"): the provider's extension, else its address's.
    public static func container(of movie: Movie) -> String? {
        if let ext = movie.containerExtension, let format = MediaFormat.label(forExtension: ext) { return format }
        return MediaFormat.label(for: movie.streamURL)
    }

    private static func isYear(_ word: String) -> Bool {
        guard word.count == 4, word.allSatisfy({ $0.isASCII && $0.isNumber }), let year = Int(word) else { return false }
        return (1900...2099).contains(year)
    }
}

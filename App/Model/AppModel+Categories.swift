import Foundation
import IPTVCore

/// Tags shared by several categories of the catalogue ("PT" in "|PT| Filmes"), built with the catalogue.
struct CategoryTagIndex: Sendable {
    /// Tag -> the categories carrying it (movies, series and channels together).
    private(set) var categories: [String: [MediaCategory]] = [:]
    /// Category id -> its tag.
    private(set) var tagOfCategory: [String: String] = [:]
    /// Styles found in the category names ("manga", see `CategoryStyles`) -> their categories.
    private(set) var styles: [String: [MediaCategory]] = [:]
    /// Category id -> the words of its name, for the styles and words the viewer hides.
    private(set) var words: [String: [Substring]] = [:]

    init() {}

    init(content: PlaylistContent) {
        let all = content.liveCategories + content.movieCategories + content.seriesCategories
        categories = CategoryTags.detect(in: all)
        for (tag, list) in categories {
            for category in list { tagOfCategory[category.id] = tag }
        }
        styles = CategoryStyles.detect(in: all)
        for category in all { words[category.id] = CategoryStyles.tokens(of: category.name) }
    }

    var isEmpty: Bool { categories.isEmpty }

    /// Languages and countries first (by name), other tags after.
    func sortedTags(locale: Locale = .current) -> [String] {
        categories.keys.sorted { lhs, rhs in
            let left = CategoryTagName.name(of: lhs, locale: locale)
            let right = CategoryTagName.name(of: rhs, locale: locale)
            switch (left, right) {
            case let (left?, right?): return left.localizedStandardCompare(right) == .orderedAscending
            case (.some, nil): return true
            case (nil, .some): return false
            case (nil, nil): return lhs < rhs
            }
        }
    }
}

/// The language or country a tag stands for, in the viewer's language.
enum CategoryTagName {
    /// Codes providers use for a language rather than for a country ("AR" is Arabic, not Argentina).
    static let languageCodes: Set<String> = [
        "EN", "FR", "ES", "PT", "DE", "IT", "NL", "AR", "TR", "PL", "RU", "HI", "ZH", "JA", "KO", "EL", "RO",
        "HU", "CS", "FA", "UR", "HE", "TH", "VI", "BN", "TA", "TE", "PA", "SQ", "SR", "HR", "BG",
    ]
    /// Codes providers use for a country that is not its ISO code.
    static let countryAliases: [String: String] = ["UK": "GB"]

    /// Regions providers tag that are no country ("QC", Quebec): the app names them.
    static func regionName(of tag: String) -> String? {
        tag == "QC" ? String(localized: "Quebec", comment: "The region providers tag \"QC\", in the categories screen (\"QC · Quebec\").") : nil
    }

    /// `includingRegions` false: languages and countries only (the suggestion leaves the other regions alone).
    static func name(of tag: String, locale: Locale = .current, includingRegions: Bool = true) -> String? {
        if includingRegions, let region = regionName(of: tag) { return region }
        let name: String?
        if languageCodes.contains(tag) {
            name = locale.localizedString(forLanguageCode: tag.lowercased())
        } else if tag.count == 2 {
            name = locale.localizedString(forRegionCode: countryAliases[tag] ?? tag)
        } else {
            name = nil
        }
        // Foundation can answer with the code itself when it does not know it.
        guard let name, !name.isEmpty, name.uppercased() != tag else { return nil }
        return name.prefix(1).uppercased(with: locale) + name.dropFirst()
    }

    /// Language and country codes of the viewer ("fr-FR", "en-US" -> FR, EN, US), as tags.
    static func viewerCodes(preferredLanguages: [String] = Locale.preferredLanguages, current: Locale = .current) -> Set<String> {
        var codes = Set<String>()
        for identifier in preferredLanguages {
            let language = Locale.Language(identifier: identifier)
            if let code = language.languageCode?.identifier { codes.insert(code.uppercased()) }
            if let region = language.region?.identifier { codes.insert(region.uppercased()) }
        }
        if let region = current.region?.identifier { codes.insert(region.uppercased()) }
        if codes.contains("GB") { codes.insert("UK") }
        return codes
    }
}

/// "Show only your languages?" offered once after a playlist with many language or country tags is opened.
struct TagSuggestion: Equatable {
    /// Tags of the viewer's languages and country, kept.
    let kept: [String]
    /// Other language and country tags, hidden if the viewer accepts.
    let hidden: [String]
}

extension AppModel {
    /// Hidden by the viewer, on its own, with its tag ("PT") or with a style or word of its name ("manga").
    func isHidden(_ category: MediaCategory) -> Bool {
        isHiddenOnItsOwn(category) || hiddenTag(of: category) != nil || hiddenWord(of: category) != nil
    }

    /// The style or word hiding this category, if one of the hidden ones is in its name.
    func hiddenWord(of category: MediaCategory) -> String? {
        let rules = library.hiddenCategoryWords.keys
        guard !rules.isEmpty else { return nil }
        let words = categoryTags.words[category.id] ?? CategoryStyles.tokens(of: category.name)
        return CategoryStyles.rule(hidingTokens: words, among: rules)
    }

    func isHidden(word: String) -> Bool {
        library.isHidden(word: word)
    }

    func setHidden(_ hidden: Bool, word: String) {
        updateLibrary { $0.setHidden(hidden, word: word) }
    }

    /// Words the viewer hid that are not one of the known styles, alphabetically.
    var hiddenCustomWords: [String] {
        library.hiddenCategoryWords.keys.filter { CategoryStyles.words[$0] == nil }.sorted()
    }

    /// Categories of the current playlist whose name holds this style or word.
    func categoryCount(word: String) -> Int {
        let all = content.liveCategories + content.movieCategories + content.seriesCategories
        return all.filter { category in
            CategoryStyles.rule(hidingTokens: categoryTags.words[category.id] ?? CategoryStyles.tokens(of: category.name), among: [word]) != nil
        }.count
    }

    func isHiddenOnItsOwn(_ category: MediaCategory) -> Bool {
        guard let playlist = activePlaylist else { return false }
        return library.isHidden(playlistID: playlist.id, categoryID: category.id)
    }

    /// The tag of a category, when several categories share it.
    func tag(of category: MediaCategory) -> String? {
        categoryTags.tagOfCategory[category.id]
    }

    /// The tag hiding this category, if its tag is hidden.
    func hiddenTag(of category: MediaCategory) -> String? {
        guard let tag = tag(of: category), library.isHidden(tag: tag) else { return nil }
        return tag
    }

    func isHidden(tag: String) -> Bool {
        library.isHidden(tag: tag)
    }

    func setHidden(_ hidden: Bool, tag: String) {
        updateLibrary { $0.setHidden(hidden, tag: tag) }
    }

    func setHidden(_ hidden: Bool, tags: [String]) {
        updateLibrary { library in
            for tag in tags { library.setHidden(hidden, tag: tag) }
        }
    }

    /// Categories of this type hidden by the viewer (on their own or with their tag).
    func hiddenCategoryCount(_ type: ContentType) -> Int {
        content.categories(for: type).filter { isHidden($0) }.count
    }

    /// The suggestion to keep only the viewer's languages, when it makes sense and was neither answered nor dismissed.
    func tagSuggestion(viewerCodes: Set<String> = CategoryTagName.viewerCodes()) -> TagSuggestion? {
        guard let playlist = activePlaylist, !settings.dismissedTagSuggestions.contains(playlist.id) else { return nil }
        let tags = categoryTags.sortedTags()
        // A choice was already made about one of these tags, here or on another playlist.
        guard !tags.contains(where: { library.hiddenCategoryTags[$0] != nil || library.shownCategoryTags[$0] != nil }) else { return nil }
        // Languages and countries only: a region such as Quebec is not hidden for a viewer of its country's language.
        let known = tags.filter { CategoryTagName.name(of: $0, includingRegions: false) != nil }
        let kept = known.filter { viewerCodes.contains($0) }
        let hidden = known.filter { !viewerCodes.contains($0) }
        guard known.count >= 3, !kept.isEmpty, !hidden.isEmpty else { return nil }
        return TagSuggestion(kept: kept, hidden: hidden)
    }

    func accept(_ suggestion: TagSuggestion) {
        setHidden(true, tags: suggestion.hidden)
        dismissTagSuggestion()
    }

    func dismissTagSuggestion() {
        guard let playlist = activePlaylist else { return }
        updateSettings { $0.dismissedTagSuggestions.insert(playlist.id) }
    }
}

/// The styles, as the categories screen names them.
enum CategoryStyleName {
    static func name(_ style: String) -> String {
        switch style {
        case "manga": return String(localized: "Manga & Anime")
        case "kids": return String(localized: "Children")
        case "animation": return String(localized: "Animation")
        case "documentary": return String(localized: "Documentaries")
        case "sport": return String(localized: "Sport")
        case "horror": return String(localized: "Horror")
        case "music": return String(localized: "Music")
        default: return style
        }
    }
}

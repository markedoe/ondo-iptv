import Foundation
import IPTVCore

/// Tags shared by several categories of the catalogue ("PT" in "|PT| Filmes"), built with the catalogue.
struct CategoryTagIndex: Sendable {
    /// Tag -> the categories carrying it (movies, series and channels together).
    private(set) var categories: [String: [MediaCategory]] = [:]
    /// Category id -> its tag.
    private(set) var tagOfCategory: [String: String] = [:]

    init() {}

    init(content: PlaylistContent) {
        categories = CategoryTags.detect(in: content.liveCategories + content.movieCategories + content.seriesCategories)
        for (tag, list) in categories {
            for category in list { tagOfCategory[category.id] = tag }
        }
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

    static func name(of tag: String, locale: Locale = .current) -> String? {
        let name: String?
        if languageCodes.contains(tag) {
            name = locale.localizedString(forLanguageCode: tag.lowercased())
        } else if tag.count == 2 {
            name = locale.localizedString(forRegionCode: countryAliases[tag] ?? tag)
        } else {
            name = nil
        }
        guard let name, !name.isEmpty else { return nil }
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
        let known = tags.filter { CategoryTagName.name(of: $0) != nil }
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

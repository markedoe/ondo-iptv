import Foundation

/// The movies and series of a catalogue by the language of their provider's description (`PlotLanguage.detect`), for
/// hidden languages: those of categories with no language tag (`CategoryTags.readsPlots`) only, and never English
/// (`PlotLanguage.neverHidden`). Built once per catalogue, off the main actor, and only once a hidden language needs it;
/// hiding or showing a language then only gathers sets (`hidden(_:)`). Same rules as the Kotlin `PlotLanguageIndex`.
public struct PlotLanguageIndex: Sendable {
    public private(set) var movies: [String: Set<String>] = [:]
    public private(set) var series: [String: Set<String>] = [:]

    public init() {}

    public init(content: PlaylistContent) {
        var readsPlots = Set<String>()
        for category in content.movieCategories + content.seriesCategories
        where CategoryTags.readsPlots(categoryTag: CategoryTags.tag(of: category.name)) {
            readsPlots.insert(category.id)
        }
        for movie in content.movies where movie.plot != nil && readsPlots.contains(movie.categoryID) {
            guard let language = PlotLanguage.detect(movie.plot), !PlotLanguage.neverHidden.contains(language) else { continue }
            movies[language, default: []].insert(movie.id)
        }
        for show in content.series where show.plot != nil && readsPlots.contains(show.categoryID) {
            guard let language = PlotLanguage.detect(show.plot), !PlotLanguage.neverHidden.contains(language) else { continue }
            series[language, default: []].insert(show.id)
        }
    }

    /// The movies and the series described in one of these languages (`PlotLanguage.hiddenLanguages`).
    public func hidden(_ languages: Set<String>) -> HiddenTitles {
        var hidden = HiddenTitles()
        for language in languages {
            hidden.movieIDs.formUnion(movies[language] ?? [])
            hidden.seriesIDs.formUnion(series[language] ?? [])
        }
        return hidden
    }
}

import SwiftUI
import IPTVCore

/// The titles of the playlist sharing a value of a movie's or a series' page: an award, a festival, a year, a genre.
struct MetadataView: View {
    @Environment(AppModel.self) private var model
    let route: MetadataRoute

    var body: some View {
        let results = model.titles(for: route)
        List {
            if case .award(let id) = route, let group = AwardsCatalogue.current.award(id)?.group {
                Section {
                    NavigationLink(value: MetadataRoute.festival(group)) {
                        Label(AwardGroup.name(group), systemImage: "trophy")
                    }
                }
            }
            if !results.movies.isEmpty {
                Section("Movies (\(results.movies.count))") {
                    ForEach(results.movies) { movie in
                        NavigationLink(value: movie) {
                            SearchResultRow(title: movie.name, subtitle: subtitle(of: movie), imageURL: movie.posterURL, icon: "film", watched: model.isWatched(.movie(movie)))
                        }
                    }
                }
            }
            if !results.series.isEmpty {
                Section("Series (\(results.series.count))") {
                    ForEach(results.series) { series in
                        NavigationLink(value: series) {
                            SearchResultRow(title: series.name, subtitle: subtitle(of: series), imageURL: series.coverURL,
                                            icon: "play.rectangle.on.rectangle", watched: model.isWatched(.series(series)))
                        }
                    }
                }
            }
            if case .genre = route, !results.isEmpty {
                Section {} footer: { genreHint }
            }
        }
        .overlay {
            if results.movies.isEmpty && results.series.isEmpty {
                ContentUnavailableView {
                    Label("Nothing in this playlist", systemImage: "rectangle.stack")
                } description: {
                    if case .genre = route { genreHint }
                }
            }
        }
        .navigationTitle(title)
    }

    private var title: String {
        switch route {
        case .award(let id): return AwardsCatalogue.current.award(id)?.name(language: Bundle.main.preferredLocalizations.first ?? "en") ?? id
        case .festival(let group): return AwardGroup.name(group)
        case .year(let year): return String(localized: "Released in \(String(year))")
        case .genre(let genre): return genre
        case .selection(let id): return model.selection(id).map { model.selectionName($0) } ?? String(localized: "My Selection")
        }
    }

    /// The award's year and outcome for an award, the release year otherwise.
    private func subtitle(of movie: Movie) -> String? {
        guard let entry = model.entry(of: movie, for: route) else { return movie.year.map(String.init) }
        return outcome(entry)
    }

    /// The award's year and outcome for an award, the year first aired otherwise.
    private func subtitle(of series: Series) -> String? {
        guard let entry = model.entry(of: series, for: route) else { return series.year.map(String.init) }
        return outcome(entry)
    }

    private func outcome(_ entry: AwardsCatalogue.Entry) -> String {
        let year = String(entry.year)
        if entry.won { return String(localized: "Winner, \(year)") }
        let isFestival = AwardsCatalogue.current.award(entry.awardID).map { AwardsCatalogue.festivals.contains($0.group) } ?? false
        return isFestival ? String(localized: "In competition, \(year)") : String(localized: "Nominated, \(year)")
    }

    /// Providers send a movie's genre with its page only.
    private var genreHint: Text {
        Text("Movies are listed once their page has been opened; series always.")
    }
}

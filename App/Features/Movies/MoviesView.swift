import SwiftUI
import IPTVCore

struct MoviesView: View {
    var body: some View {
        VODBrowser(type: .movie)
    }
}

struct SortMenu: View {
    @Environment(AppModel.self) private var model
    /// Movie grids also offer to hide the movies already watched.
    var offersHideWatched = false
    /// The grid of a row Ondo picks (New, Award Winners): its own order, this page's only, the row's by default
    /// ("Default order" in place of the playlist's). nil: the order chosen for every category's grid.
    var rowSort: Binding<PosterSort>?

    var body: some View {
        Menu {
            Picker("Sort by", selection: rowSort ?? Binding(
                get: { model.settings.posterSort },
                set: { value in model.updateSettings { $0.posterSort = value } }
            )) {
                ForEach(PosterSort.allCases) { sort in
                    if rowSort != nil && sort == .playlist {
                        Text("Default order").tag(sort)
                    } else {
                        Text(sort.label).tag(sort)
                    }
                }
            }
            if offersHideWatched {
                Divider()
                Toggle("Hide watched movies", isOn: Binding(
                    get: { model.settings.hideWatchedMovies },
                    set: { value in model.updateSettings { $0.hideWatchedMovies = value } }
                ))
            }
        } label: {
            Label("Sort", systemImage: "arrow.up.arrow.down")
        }
    }
}

let posterColumns = [GridItem(.adaptive(minimum: Platform.isMac ? 150 : 108, maximum: 200), spacing: 14, alignment: .top)]

struct MovieGridView: View {
    @Environment(AppModel.self) private var model
    let category: MediaCategory
    @State private var filter = ""
    /// The order of a row's grid (New, Award Winners): the row's own until another is chosen here.
    @State private var rowSort = PosterSort.playlist

    /// The movies of `category`'s grid: a category, All, My List, or every title of a row Ondo picks, in the row's order
    /// (`rowSort`, `.playlist` keeping it).
    @MainActor static func movies(_ category: MediaCategory, model: AppModel, rowSort: PosterSort = .playlist) -> [Movie] {
        switch category.id {
        case PseudoCategory.all: return model.movies(in: nil, sort: model.settings.posterSort)
        case PseudoCategory.favorites: return model.favorites(.movie).compactMap { model.index.moviesByID[$0.reference.itemID] }
        case let id where PseudoCategory.isRow(id):
            let row = model.rowTitles(id, type: .movie).compactMap { item -> Movie? in
                if case .movie(let movie) = item { return movie }
                return nil
            }
            return AppModel.sorted(row, by: rowSort, votes: model.ratingSortVotes(rowSort))
        default: return model.movies(in: category, sort: model.settings.posterSort)
        }
    }

    private var movies: [Movie] {
        let base = Self.movies(category, model: model, rowSort: rowSort)
        guard !filter.isEmpty else { return base }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return base.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    /// The order this grid shows: a row's own (`rowSort`), else the one chosen for every category.
    private var sort: PosterSort { PseudoCategory.isRow(category.id) ? rowSort : model.settings.posterSort }

    var body: some View {
        let list = movies
        // One set for the whole grid: each poster's watched badge is a lookup.
        let watched = model.watchedKeys
        ScrollView {
            LazyVGrid(columns: posterColumns, spacing: 18) {
                ForEach(list) { movie in
                    NavigationLink(value: movie) {
                        PosterCard(
                            title: movie.name,
                            imageURL: movie.posterURL,
                            subtitle: movie.year.map(String.init),
                            rating: movie.rating,
                            progress: model.progress(movie: movie)?.progress,
                            watched: watched.contains(ShowcaseItem.movie(movie).id)
                        )
                    }
                    .cardButtonStyle()
                    .contextMenu { MovieContextMenu(movie: movie) }
                }
            }
            .padding()
        }
        .overlay {
            if list.isEmpty {
                if !filter.isEmpty {
                    ContentUnavailableView.search(text: filter)
                } else {
                    ContentUnavailableView("No movies", systemImage: "film")
                }
            }
        }
        .searchable(text: $filter, prompt: Text("Search movies"))
        .navigationTitle(category.id == PseudoCategory.all ? String(localized: "All Movies") : category.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                SortMenu(offersHideWatched: category.id != PseudoCategory.favorites, rowSort: PseudoCategory.isRow(category.id) ? $rowSort : nil)
            }
        }
        // Sorted by rating: TMDb's ratings the grid lacks are fetched in the background (`AppModel.fillRatingSort`).
        .task(id: model.ratingFillID(category: category, sort: sort)) {
            guard sort == .rating, category.id != PseudoCategory.favorites else { return }
            await model.fillRatingSort(Self.movies(category, model: model, rowSort: rowSort).map(ShowcaseItem.movie))
        }
    }
}

struct MovieDetailView: View {
    @Environment(AppModel.self) private var model
    let movie: Movie
    @State private var details: MediaDetails?
    @State private var isLoading = true
    /// The version picked among the film's badges; nil: its default version (`AppModel.defaultVersion`).
    @State private var picked: String?

    var body: some View {
        let versions = model.visibleVersions(of: movie)
        let version = picked.flatMap { id in versions.first { $0.id == id } } ?? model.defaultVersion(of: movie, among: versions)
        let progress = model.progress(movie: version)
        let resumable = progress?.isResumable == true
        // Watched once one of the film's versions is (`AppModel.watchedKeys`).
        let watched = model.isWatched(.movie(movie))
        DetailScaffold(
            title: ShowcaseItem.movie(movie).displayTitle,
            posterURL: details?.posterURL ?? movie.posterURL,
            backdropURL: model.backdropURL(for: .movie(movie)),
            facts: facts,
            // MKV, MP4…: the format the provider sends (MKV plays differently on each device), of the version Play plays.
            badges: MediaFormat.label(for: version.streamURL).map { [$0] } ?? [],
            watched: watched,
            onBackdropFailure: { model.backdropFailed($0, for: .movie(movie), permanently: $1) }
        ) { metrics in
            metrics.actionsLayout {
                PrimaryPlayButton(
                    title: resumable ? "Resume" : "Play",
                    subtitle: progress?.timeLeftText,
                    progress: resumable ? progress?.progress : nil
                ) {
                    model.play(version)
                }
                HStack(alignment: .top, spacing: 14) {
                    MyListAction(item: .movie(movie), version: version)
                    CollectionAction(item: .movie(movie))
                    WatchedAction(watched: watched) {
                        model.setWatched(!watched, movie: movie, version: version, duration: details?.durationSeconds)
                    }
                    if resumable {
                        Button {
                            model.play(version, fromStart: true)
                        } label: {
                            CircleAction("From Start", systemImage: "backward.end.fill")
                        }
                        .buttonStyle(.plain)
                    }
                    TrailerAction(youTubeID: model.trailerYouTubeID(for: .movie(movie), provider: details?.trailerYouTubeID))
                    #if TESTFLIGHT_DOWNLOADS || DEBUG
                    DownloadAction(item: model.downloadItem(for: movie))
                    #endif
                }
            }
        } content: { metrics in
            if versions.count > 1 {
                VersionChips(versions: versions, labels: model.versionLabels(versions), selected: version.id) { chosen in
                    picked = chosen.id
                    model.rememberVersion(chosen)
                }
                .padding(.horizontal, metrics.gutter)
            }
            VStack(alignment: .leading, spacing: 16) {
                if isLoading && details == nil && movie.xtreamStreamID != nil {
                    ProgressView()
                }
                if let plot = details?.plot ?? movie.plot, !plot.isEmpty {
                    ExpandablePlot(text: plot)
                }
                InfoLines(details: details, year: movie.year)
                AwardsList(awards: model.awards(for: movie))
            }
            .frame(maxWidth: metrics.textWidth, alignment: .leading)
            .padding(.horizontal, metrics.gutter)
            CastShelf(item: .movie(movie), details: details, metrics: metrics)
                .padding(.top, 8)
            let similar = model.similarMovies(to: movie)
            if !similar.isEmpty {
                Shelf(title: "More Like This", metrics: metrics) {
                    ForEach(similar) { other in
                        ShowcasePoster(item: .movie(other), width: metrics.posterWidth)
                    }
                }
                .padding(.top, 8)
            }
        }
        .task {
            // The details kept from an earlier visit at once, then the provider's if they are old.
            if let stored = model.storedDetails(for: movie) {
                details = stored
                isLoading = false
            }
            // What TMDb said at an earlier visit (backdrop, trailer, votes), from the device.
            await model.showStoredMetadata(for: .movie(movie))
            details = await model.details(for: movie)
            isLoading = false
            await model.loadTMDbMetadata(for: .movie(movie))
        }
    }

    private var facts: [String] {
        var items: [String] = []
        if let year = movie.year { items.append(String(year)) } else if let date = details?.releaseDate { items.append(String(date.prefix(4))) }
        if let seconds = details?.durationSeconds, seconds > 0 { items.append(Formatting.duration(seconds)) }
        if let rating = model.ratingFact(for: .movie(movie), provider: details?.rating ?? movie.rating) { items.append(rating) }
        if let genre = details?.genre { items.append(genre) }
        return items
    }
}

/// Credits: the label in a narrow column, the value right next to it (not pushed to the far edge). Each name of
/// the director and cast, each genre and the release year opens the titles of the playlist sharing it, through a
/// navigation link like the posters of the page (text links did not open anything in a pushed page on the Mac).
struct InfoLines: View {
    let details: MediaDetails?
    /// From the catalogue when the page gave none.
    var year: Int?
    var genre: String?

    /// Where a link goes.
    private enum Target {
        case person(String)
        case metadata(MetadataRoute)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let director = details?.director {
                linked("Director", field: director, People.names(in: director).map { ($0, Target.person($0)) })
            }
            if let cast = details?.cast {
                linked("Cast", field: cast, People.names(in: cast).map { ($0, Target.person($0)) })
            }
            if let genre = genre ?? details?.genre {
                linked("Genre", field: genre, AppModel.genres(in: genre).map { ($0, Target.metadata(.genre($0))) })
            }
            if let original = details?.originalTitle, original != details?.title {
                line("Original title", original)
            }
            if let date = details?.releaseDate {
                let releaseYear = Int(date.prefix(4)) ?? year
                linked("Release", field: date, releaseYear.map { [(date, Target.metadata(.year($0)))] } ?? [])
            } else if let year {
                linked("Year", field: String(year), [(String(year), Target.metadata(.year(year)))])
            }
        }
        .font(.callout)
    }

    /// Values as links, wrapping like text, separated by commas.
    @ViewBuilder
    private func linked(_ label: LocalizedStringKey, field: String, _ items: [(String, Target)]) -> some View {
        if items.isEmpty {
            line(label, field)
        } else {
            HStack(alignment: .top, spacing: 12) {
                Text(label)
                    .foregroundStyle(.secondary)
                    .frame(width: 104, alignment: .leading)
                FlowLayout {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        HStack(spacing: 0) {
                            link(item.0, to: item.1)
                            if index < items.count - 1 { Text(verbatim: ",") }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func link(_ text: String, to target: Target) -> some View {
        Group {
            switch target {
            case .person(let name): NavigationLink(value: PersonRoute(name: name)) { Text(verbatim: text) }
            case .metadata(let route): NavigationLink(value: route) { Text(verbatim: text) }
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(OndoColor.selected)
        .accessibilityIdentifier("credit-\(text)")
    }

    private func line(_ label: LocalizedStringKey, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .foregroundStyle(.secondary)
                .frame(width: 104, alignment: .leading)
            Text(value)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Awards and festival selections of a movie (wins first), the first few then "and N more".
struct AwardsList: View {
    let awards: [MovieAward]
    @State private var showsAll = false
    private static let shortLength = 4

    var body: some View {
        if !awards.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Awards").font(.headline)
                ForEach(showsAll ? awards : Array(awards.prefix(Self.shortLength))) { award in
                    // An award opens the other movies of the playlist that won it or competed for it.
                    NavigationLink(value: MetadataRoute.award(award.awardID)) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Image(systemName: award.won ? "trophy.fill" : "rosette")
                                .foregroundStyle(award.won ? Color.yellow : Color.secondary)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(verbatim: award.name).foregroundStyle(OndoColor.selected)
                                Text(status(award)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("award-\(award.awardID)")
                }
                if !showsAll && awards.count > Self.shortLength {
                    Button("And \(awards.count - Self.shortLength) more") { showsAll = true }
                        .buttonStyle(.borderless)
                        .font(.callout)
                }
            }
            .font(.callout)
        }
    }

    private func status(_ award: MovieAward) -> String {
        let year = String(award.year)
        if award.won { return String(localized: "Winner, \(year)") }
        return award.isFestival ? String(localized: "In competition, \(year)") : String(localized: "Nominated, \(year)")
    }
}

/// A film's versions as chips, each saying what sets it apart ("4K Dolby Vision", "Original, French subtitles",
/// "Standard": `FilmVersions.labels`): the one selected plays; a tap, a click or the remote picks another.
struct VersionChips: View {
    let versions: [Movie]
    let labels: [String]
    let selected: String
    let onPick: (Movie) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(versions.indices, id: \.self) { index in
                    let movie = versions[index]
                    let label = index < labels.count ? labels[index] : String(localized: "Version \(index + 1)")
                    let isSelected = movie.id == selected
                    Button {
                        onPick(movie)
                    } label: {
                        Text(label)
                            .font(.subheadline.weight(isSelected ? .bold : .medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .foregroundStyle(isSelected ? Color.black : Color.primary)
                            .background(isSelected ? Color.white : Color.primary.opacity(0.12), in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    // A screen reader hears what the row is: the film's versions.
                    .accessibilityLabel(String(localized: "Version: \(label)"))
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                    .accessibilityIdentifier("version-\(movie.id)")
                }
            }
        }
        .accessibilityIdentifier("versions")
    }
}

import SwiftUI
import IPTVCore

struct MoviesView: View {
    var body: some View {
        VODBrowser(type: .movie)
    }
}

struct SortMenu: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Menu {
            Picker("Sort by", selection: Binding(
                get: { model.settings.posterSort },
                set: { value in model.updateSettings { $0.posterSort = value } }
            )) {
                ForEach(PosterSort.allCases) { sort in
                    Text(sort.label).tag(sort)
                }
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

    private var movies: [Movie] {
        let base: [Movie]
        switch category.id {
        case PseudoCategory.all: base = model.movies(in: nil, sort: model.settings.posterSort)
        case PseudoCategory.favorites: base = model.favorites(.movie).compactMap { model.index.moviesByID[$0.reference.itemID] }
        default: base = model.movies(in: category, sort: model.settings.posterSort)
        }
        guard !filter.isEmpty else { return base }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return base.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    var body: some View {
        let list = movies
        ScrollView {
            LazyVGrid(columns: posterColumns, spacing: 18) {
                ForEach(list) { movie in
                    NavigationLink(value: movie) {
                        PosterCard(
                            title: movie.name,
                            imageURL: movie.posterURL,
                            subtitle: movie.year.map(String.init),
                            rating: movie.rating,
                            progress: model.progress(movie: movie)?.progress
                        )
                    }
                    .buttonStyle(.plain)
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
        .toolbar { ToolbarItem(placement: .primaryAction) { SortMenu() } }
    }
}

struct MovieDetailView: View {
    @Environment(AppModel.self) private var model
    let movie: Movie
    @State private var details: MediaDetails?
    @State private var isLoading = true

    var body: some View {
        let progress = model.progress(movie: movie)
        let resumable = progress?.isResumable == true
        DetailScaffold(
            title: movie.name,
            posterURL: details?.posterURL ?? movie.posterURL,
            backdropURL: model.backdropURL(for: .movie(movie)),
            facts: facts,
            onBackdropFailure: { model.backdropFailed($0, for: .movie(movie)) }
        ) { metrics in
            metrics.actionsLayout {
                PrimaryPlayButton(
                    title: resumable ? "Resume" : "Play",
                    subtitle: progress?.timeLeftText,
                    progress: resumable ? progress?.progress : nil
                ) {
                    model.play(movie)
                }
                HStack(alignment: .top, spacing: 14) {
                    MyListAction(item: .movie(movie))
                    CollectionAction(item: .movie(movie))
                    if resumable {
                        Button {
                            model.play(movie, fromStart: true)
                        } label: {
                            CircleAction("From Start", systemImage: "backward.end.fill")
                        }
                        .buttonStyle(.plain)
                    }
                    TrailerAction(youTubeID: details?.trailerYouTubeID)
                    #if TESTFLIGHT_DOWNLOADS || DEBUG
                    DownloadAction(item: model.downloadItem(for: movie))
                    #endif
                }
            }
        } content: { metrics in
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
            details = await model.details(for: movie)
            isLoading = false
            await model.loadTMDbBackdropIfNeeded(for: .movie(movie))
        }
    }

    private var facts: [String] {
        var items: [String] = []
        if let year = movie.year { items.append(String(year)) } else if let date = details?.releaseDate { items.append(String(date.prefix(4))) }
        if let seconds = details?.durationSeconds, seconds > 0 { items.append(Formatting.duration(seconds)) }
        if let rating = details?.rating ?? movie.rating, rating > 0 { items.append("★ " + Formatting.rating(rating)) }
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
        .foregroundStyle(Color.accentColor)
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
                                Text(verbatim: award.name).foregroundStyle(Color.accentColor)
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

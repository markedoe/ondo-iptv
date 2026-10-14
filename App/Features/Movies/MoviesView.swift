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
            backdropURL: details?.backdropURL ?? model.backdropURL(for: .movie(movie)),
            facts: facts
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
                    DownloadAction(item: model.downloadItem(for: movie))
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
                InfoLines(details: details)
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
/// the director and cast is a link to the movies and series of the playlist with that person.
struct InfoLines: View {
    @Environment(NavigationRouter.self) private var router: NavigationRouter?
    let details: MediaDetails?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let director = details?.director {
                people("Director", director)
            }
            if let cast = details?.cast {
                people("Cast", cast)
            }
            if let original = details?.originalTitle, original != details?.title {
                line("Original title", original)
            }
            if let date = details?.releaseDate {
                line("Release", date)
            }
        }
        .font(.callout)
    }

    /// Names as links (`ondo-person://<index>`), opened in this navigation stack.
    @ViewBuilder
    private func people(_ label: LocalizedStringKey, _ field: String) -> some View {
        let names = People.names(in: field)
        if names.isEmpty || router == nil {
            line(label, field)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(label)
                    .foregroundStyle(.secondary)
                    .frame(width: 104, alignment: .leading)
                Text(Self.links(names))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .environment(\.openURL, OpenURLAction { url in
                        guard url.scheme == "ondo-person", let index = Int(url.host() ?? ""), names.indices.contains(index) else {
                            return .systemAction
                        }
                        router?.push(PersonRoute(name: names[index]))
                        return .handled
                    })
            }
        }
    }

    private static func links(_ names: [String]) -> AttributedString {
        var text = AttributedString()
        for (index, name) in names.enumerated() {
            if index > 0 { text += AttributedString(", ") }
            var part = AttributedString(name)
            part.link = URL(string: "ondo-person://\(index)")
            text += part
        }
        return text
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

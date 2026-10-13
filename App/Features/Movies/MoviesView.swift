import SwiftUI
import IPTVCore

struct MoviesView: View {
    var body: some View {
        CategoryBrowser(type: .movie, pseudoCategories: [PseudoCategory.all(.movie), PseudoCategory.favorites(.movie)]) { category in
            MovieGridView(category: category)
        }
        .navigationTitle("Movies")
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
                    .contextMenu {
                        Button {
                            model.play(movie)
                        } label: {
                            Label("Play", systemImage: "play.fill")
                        }
                        Button {
                            model.toggleFavorite(movie)
                        } label: {
                            Label(model.isFavorite(.movie, id: movie.id) ? "Remove from Favorites" : "Add to Favorites",
                                  systemImage: model.isFavorite(.movie, id: movie.id) ? "star.slash" : "star")
                        }
                        AddToListMenu(item: .movie(movie))
                    }
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
        .navigationTitle(category.name)
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
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                DetailHeader(
                    title: details?.title.isEmpty == false ? details!.title : movie.name,
                    posterURL: details?.posterURL ?? movie.posterURL,
                    backdropURL: details?.backdropURL,
                    facts: facts
                )

                HStack(spacing: 12) {
                    Button {
                        model.play(movie)
                    } label: {
                        Label(progress?.isResumable == true ? "Resume \(Formatting.clock(progress!.position))" : "Play",
                              systemImage: "play.fill")
                            .frame(minWidth: 140)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                    if progress?.isResumable == true {
                        Button {
                            model.play(movie, fromStart: true)
                        } label: {
                            Label("From Start", systemImage: "backward.end.fill")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }

                    Button {
                        model.toggleFavorite(movie)
                    } label: {
                        Image(systemName: model.isFavorite(.movie, id: movie.id) ? "star.fill" : "star")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .help("Favorite")

                    if let trailer = details?.trailerYouTubeID, let url = URL(string: "https://www.youtube.com/watch?v=\(trailer)") {
                        Link(destination: url) {
                            Label("Trailer", systemImage: "play.rectangle")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }

                    DownloadButton(item: model.downloadItem(for: movie), compact: true)
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                }

                if isLoading && details == nil && movie.xtreamStreamID != nil {
                    ProgressView()
                }

                if let plot = details?.plot ?? movie.plot {
                    Text(plot).font(.body)
                }
                InfoLines(details: details)
            }
            .padding()
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(movie.name)
        .inlineNavigationTitle()
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

/// Backdrop + poster + title + facts line shared by movie and series screens.
struct DetailHeader: View {
    let title: String
    let posterURL: URL?
    let backdropURL: URL?
    let facts: [String]

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.secondary.opacity(0.1)
                .frame(height: 230)
                .overlay {
                    RemoteImage(url: backdropURL ?? posterURL, placeholderText: title, contentMode: .fill, maxPixelSize: 1200)
                        .blur(radius: backdropURL == nil ? 24 : 0)
                }
                .overlay {
                    LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16))

            HStack(alignment: .bottom, spacing: 16) {
                Color.secondary.opacity(0.2)
                    .frame(width: 110, height: 165)
                    .overlay {
                        RemoteImage(url: posterURL, placeholderText: title, contentMode: .fill, maxPixelSize: 400)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .shadow(radius: 10)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title)
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                        .lineLimit(3)
                    if !facts.isEmpty {
                        Text(facts.joined(separator: " · "))
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.85))
                            .lineLimit(2)
                    }
                }
            }
            .padding(16)
        }
    }
}

struct InfoLines: View {
    let details: MediaDetails?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let director = details?.director {
                LabeledContent("Director", value: director)
            }
            if let cast = details?.cast {
                LabeledContent("Cast", value: cast)
            }
            if let original = details?.originalTitle, original != details?.title {
                LabeledContent("Original title", value: original)
            }
            if let date = details?.releaseDate {
                LabeledContent("Release", value: date)
            }
        }
        .font(.callout)
    }
}

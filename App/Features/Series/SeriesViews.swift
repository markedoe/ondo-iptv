import SwiftUI
import IPTVCore

struct SeriesListView: View {
    var body: some View {
        CategoryBrowser(type: .series, pseudoCategories: [PseudoCategory.all(.series), PseudoCategory.favorites(.series)]) { category in
            SeriesGridView(category: category)
        }
        .navigationTitle("Series")
    }
}

struct SeriesGridView: View {
    @Environment(AppModel.self) private var model
    let category: MediaCategory
    @State private var filter = ""

    private var series: [Series] {
        let base: [Series]
        switch category.id {
        case PseudoCategory.all: base = model.series(in: nil, sort: model.settings.posterSort)
        case PseudoCategory.favorites: base = model.favorites(.series).compactMap { model.index.seriesByID[$0.reference.itemID] }
        default: base = model.series(in: category, sort: model.settings.posterSort)
        }
        guard !filter.isEmpty else { return base }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return base.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    var body: some View {
        let list = series
        ScrollView {
            LazyVGrid(columns: posterColumns, spacing: 18) {
                ForEach(list) { item in
                    NavigationLink(value: item) {
                        PosterCard(title: item.name, imageURL: item.coverURL, subtitle: item.year.map(String.init), rating: item.rating)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            model.toggleFavorite(item)
                        } label: {
                            Label(model.isFavorite(.series, id: item.id) ? "Remove from Favorites" : "Add to Favorites",
                                  systemImage: model.isFavorite(.series, id: item.id) ? "star.slash" : "star")
                        }
                        AddToListMenu(item: .series(item))
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
                    ContentUnavailableView("No series", systemImage: "play.rectangle.on.rectangle")
                }
            }
        }
        .searchable(text: $filter, prompt: Text("Search series"))
        .navigationTitle(category.name)
        .toolbar { ToolbarItem(placement: .primaryAction) { SortMenu() } }
    }
}

struct SeriesDetailView: View {
    @Environment(AppModel.self) private var model
    let series: Series
    @State private var details: SeriesDetails?
    @State private var errorMessage: String?
    @State private var selectedSeason: Int?

    private var season: Season? {
        guard let details else { return nil }
        return details.seasons.first { $0.number == selectedSeason } ?? details.seasons.first
    }

    /// Next episode to watch: the one in progress, else the one after the last finished.
    private var nextUp: (Episode, Season)? {
        guard let details else { return nil }
        var lastWatched: (Episode, Season, HistoryEntry)?
        for season in details.seasons {
            for episode in season.episodes {
                if let entry = model.progress(episode: episode, in: series),
                   lastWatched == nil || entry.lastWatchedAt > lastWatched!.2.lastWatchedAt {
                    lastWatched = (episode, season, entry)
                }
            }
        }
        guard let last = lastWatched else {
            guard let first = details.seasons.first, let episode = first.episodes.first else { return nil }
            return (episode, first)
        }
        let (episode, season, entry) = last
        if entry.isFinished, let index = season.episodes.firstIndex(of: episode) {
            if index + 1 < season.episodes.count { return (season.episodes[index + 1], season) }
            if let seasonIndex = details.seasons.firstIndex(of: season), seasonIndex + 1 < details.seasons.count,
               let next = details.seasons[seasonIndex + 1].episodes.first {
                return (next, details.seasons[seasonIndex + 1])
            }
        }
        return (episode, season)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                DetailHeader(
                    title: series.name,
                    posterURL: details?.info.posterURL ?? series.coverURL,
                    backdropURL: details?.info.backdropURL,
                    facts: facts
                )

                HStack(spacing: 12) {
                    if let next = nextUp {
                        Button {
                            model.play(next.0, in: series, season: next.1.episodes)
                        } label: {
                            Label(model.progress(episode: next.0, in: series) != nil ? "Resume \(next.0.code)" : "Play \(next.0.code)",
                                  systemImage: "play.fill")
                                .frame(minWidth: 140)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }
                    Button {
                        model.toggleFavorite(series)
                    } label: {
                        Image(systemName: model.isFavorite(.series, id: series.id) ? "star.fill" : "star")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    if let trailer = details?.info.trailerYouTubeID, let url = URL(string: "https://www.youtube.com/watch?v=\(trailer)") {
                        Link(destination: url) {
                            Label("Trailer", systemImage: "play.rectangle")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }
                }

                if let plot = details?.info.plot ?? series.plot {
                    Text(plot)
                }
                InfoLines(details: details?.info)

                if let details {
                    if details.seasons.count > 1 {
                        Picker("Season", selection: Binding(get: { season?.number ?? 1 }, set: { selectedSeason = $0 })) {
                            ForEach(details.seasons) { season in
                                Text(season.name).tag(season.number)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                    if let season {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(season.episodes) { episode in
                                HStack(alignment: .top) {
                                    Button {
                                        model.play(episode, in: series, season: season.episodes)
                                    } label: {
                                        EpisodeRow(episode: episode, progress: model.progress(episode: episode, in: series))
                                    }
                                    .buttonStyle(.plain)
                                    DownloadButton(item: model.downloadItem(for: episode, in: series), compact: true)
                                        .buttonStyle(.borderless)
                                        .padding(.top, 4)
                                }
                            }
                        }
                    }
                } else if let errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding()
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(series.name)
        .inlineNavigationTitle()
        .task {
            do {
                details = try await model.details(for: series)
                if selectedSeason == nil { selectedSeason = nextUp?.1.number }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private var facts: [String] {
        var items: [String] = []
        if let year = series.year { items.append(String(year)) }
        if let seasons = details?.seasons, !seasons.isEmpty {
            items.append(seasons.count == 1 ? String(localized: "1 season") : String(localized: "\(seasons.count) seasons"))
        }
        if let rating = details?.info.rating ?? series.rating, rating > 0 { items.append("★ " + Formatting.rating(rating)) }
        if let genre = details?.info.genre ?? series.genre { items.append(genre) }
        return items
    }
}

struct EpisodeRow: View {
    let episode: Episode
    let progress: HistoryEntry?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Color.secondary.opacity(0.12)
                .frame(width: 140, height: 79)
                .overlay {
                    RemoteImage(url: episode.posterURL, placeholderText: episode.code, contentMode: .fill, maxPixelSize: 300)
                }
                .overlay(alignment: .bottom) {
                    if let progress, progress.progress > 0 {
                        ProgressView(value: progress.progress)
                            .tint(.accentColor)
                            .padding(6)
                    }
                }
                .overlay {
                    Image(systemName: "play.circle.fill")
                        .font(.title)
                        .foregroundStyle(.white.opacity(0.85))
                        .shadow(radius: 4)
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("\(episode.number). \(episode.title)")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                    if progress?.isFinished == true {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.caption)
                    }
                }
                if let seconds = episode.durationSeconds, seconds > 0 {
                    Text(Formatting.duration(seconds)).font(.caption).foregroundStyle(.secondary)
                }
                if let plot = episode.plot {
                    Text(plot).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}

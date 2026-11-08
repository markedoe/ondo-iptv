import SwiftUI
import IPTVCore

struct SeriesListView: View {
    var body: some View {
        VODBrowser(type: .series)
    }
}

struct SeriesGridView: View {
    @Environment(AppModel.self) private var model
    let category: MediaCategory
    @State private var filter = ""
    /// The order of a row's grid (New): the row's own until another is chosen here.
    @State private var rowSort = PosterSort.playlist

    /// The series of `category`'s grid: a category, All, My List, or every title of a row Ondo picks, in the row's order
    /// (`rowSort`, `.playlist` keeping it).
    @MainActor static func series(_ category: MediaCategory, model: AppModel, rowSort: PosterSort = .playlist) -> [Series] {
        switch category.id {
        case PseudoCategory.all: return model.series(in: nil, sort: model.settings.posterSort)
        case PseudoCategory.favorites: return model.favorites(.series).compactMap { model.index.seriesByID[$0.reference.itemID] }
        case let id where PseudoCategory.isRow(id):
            let row = model.rowTitles(id, type: .series).compactMap { item -> Series? in
                if case .series(let series) = item { return series }
                return nil
            }
            return AppModel.sorted(row, by: rowSort, votes: model.ratingSortVotes(rowSort))
        default: return model.series(in: category, sort: model.settings.posterSort)
        }
    }

    private var series: [Series] {
        let base = Self.series(category, model: model, rowSort: rowSort)
        guard !filter.isEmpty else { return base }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return base.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    /// The order this grid shows: a row's own (`rowSort`), else the one chosen for every category.
    private var sort: PosterSort { PseudoCategory.isRow(category.id) ? rowSort : model.settings.posterSort }

    var body: some View {
        let list = series
        // One set for the whole grid: each poster's watched badge is a lookup.
        let watched = model.watchedKeys
        ScrollView {
            LazyVGrid(columns: posterColumns, spacing: 18) {
                ForEach(list) { item in
                    NavigationLink(value: item) {
                        PosterCard(title: item.name, imageURL: item.coverURL, subtitle: item.year.map(String.init), rating: item.rating,
                                   watched: watched.contains(ShowcaseItem.series(item).id))
                    }
                    .cardButtonStyle()
                    .contextMenu { SeriesContextMenu(series: item) }
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
        .navigationTitle(category.id == PseudoCategory.all ? String(localized: "All Series") : category.name)
        .toolbar { ToolbarItem(placement: .primaryAction) { SortMenu(rowSort: PseudoCategory.isRow(category.id) ? $rowSort : nil) } }
        // Sorted by rating: TMDb's ratings the grid lacks are fetched in the background (`AppModel.fillRatingSort`).
        .task(id: model.ratingFillID(category: category, sort: sort)) {
            guard sort == .rating, category.id != PseudoCategory.favorites else { return }
            await model.fillRatingSort(Self.series(category, model: model, rowSort: rowSort).map(ShowcaseItem.series))
        }
    }
}

struct SeriesDetailView: View {
    @Environment(AppModel.self) private var model
    let series: Series
    @State private var details: SeriesDetails?
    @State private var errorMessage: String?
    @State private var selectedSeason: Int?

    /// The episodes as they show: their own titles, TMDb's stills where the provider has none (`AppModel.shown`).
    private var shownDetails: SeriesDetails? {
        details.map { model.shown($0, of: series) }
    }

    private var season: Season? {
        guard let details = shownDetails else { return nil }
        return details.seasons.first { $0.number == selectedSeason } ?? details.seasons.first
    }

    var body: some View {
        let next = shownDetails.flatMap { model.nextEpisode(in: series, details: $0) }
        let nextProgress = next.flatMap { model.progress(episode: $0.episode, in: series) }
        // Watched once every episode of its seasons is.
        let watched = details.map { model.isWatched(series: series, details: $0) } ?? false
        DetailScaffold(
            title: ShowcaseItem.series(series).displayTitle,
            posterURL: details?.info.posterURL ?? series.coverURL,
            backdropURL: model.backdropURL(for: .series(series)),
            facts: facts,
            badges: badges,
            watched: watched,
            onBackdropFailure: { model.backdropFailed($0, for: .series(series), permanently: $1) }
        ) { metrics in
            metrics.actionsLayout {
                if let next {
                    let code = next.episode.code
                    PrimaryPlayButton(
                        title: nextProgress?.isResumable == true ? "Resume \(code)" : "Play \(code)",
                        subtitle: nextProgress?.timeLeftText,
                        progress: nextProgress?.isResumable == true ? nextProgress?.progress : nil
                    ) {
                        model.play(next.episode, in: series, season: next.season.episodes)
                    }
                } else if details == nil && errorMessage == nil {
                    ProgressView()
                }
                HStack(alignment: .top, spacing: 14) {
                    MyListAction(item: .series(series))
                    CollectionAction(item: .series(series))
                    // Its episodes known: all of them watched, or all unwatched.
                    if let details, !details.seasons.flatMap(\.episodes).isEmpty {
                        WatchedAction(watched: watched) {
                            model.setWatched(!watched, series: series, details: details)
                        }
                    }
                    TrailerAction(youTubeID: model.trailerYouTubeID(for: .series(series), provider: details?.info.trailerYouTubeID))
                }
            }
        } content: { metrics in
            VStack(alignment: .leading, spacing: 16) {
                if let plot = details?.info.plot ?? series.plot, !plot.isEmpty {
                    ExpandablePlot(text: plot)
                }
                InfoLines(details: details?.info, year: series.year, genre: details?.info.genre ?? series.genre)
                AwardsList(awards: model.awards(for: series))
                if let errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: metrics.textWidth, alignment: .leading)
            .padding(.horizontal, metrics.gutter)
            if let details = shownDetails, !details.seasons.isEmpty {
                episodes(details, metrics: metrics)
            }
            CastShelf(item: .series(series), details: details?.info, metrics: metrics)
                .padding(.top, 8)
            let similar = model.similarSeries(to: series)
            if !similar.isEmpty {
                Shelf(title: "More Like This", metrics: metrics) {
                    ForEach(similar) { other in
                        ShowcasePoster(item: .series(other), width: metrics.posterWidth)
                    }
                }
                .padding(.top, 8)
            }
        }
        .task {
            // The episodes kept from an earlier visit at once, then the provider's if they are old.
            var suggested: Int?
            if let stored = model.storedDetails(for: series) {
                details = stored
                suggested = model.nextEpisode(in: series, details: stored)?.season.number
                if selectedSeason == nil { selectedSeason = suggested }
            }
            // What TMDb said at an earlier visit (backdrop, trailer, votes), from the device.
            await model.showStoredMetadata(for: .series(series))
            do {
                let loaded = try await model.details(for: series)
                details = loaded
                // New episodes can move the next one to another season, unless the viewer picked one.
                if selectedSeason == nil || selectedSeason == suggested {
                    selectedSeason = model.nextEpisode(in: series, details: loaded)?.season.number
                }
            } catch {
                errorMessage = error.localizedDescription
            }
            await model.loadTMDbMetadata(for: .series(series))
        }
        // The season shown: TMDb's stills and names for the episodes its provider leaves without them.
        .task(id: season?.number) {
            if let number = season?.number { await model.loadTMDbSeason(of: series, season: number) }
        }
    }

    /// Season chips (or an "Episodes" title) and the episodes of the selected season.
    private func episodes(_ details: SeriesDetails, metrics: ShelfMetrics) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if details.seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(details.seasons) { item in
                            let selected = item.number == season?.number
                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) { selectedSeason = item.number }
                            } label: {
                                Text(item.displayName())
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .foregroundStyle(selected ? Color.black : Color.white)
                                    .background(selected ? OndoColor.selected : OndoColor.surfaceHigh, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, metrics.gutter)
                }
                .scrollClipDisabled()
            } else {
                Text("Episodes")
                    .font(.title3.bold())
                    .padding(.horizontal, metrics.gutter)
            }
            if let season {
                LazyVStack(alignment: .leading, spacing: 16) {
                    ForEach(season.episodes) { episode in
                        EpisodeRow(
                            episode: episode,
                            progress: model.progress(episode: episode, in: series),
                            compact: metrics.compact,
                            downloadItem: model.downloadItem(for: episode, in: series),
                            playFromStart: { model.play(episode, in: series, season: season.episodes, fromStart: true) },
                            toggleWatched: { watched in model.setWatched(watched, episodes: [episode], in: series) },
                            markSeasonWatched: { watched in model.setWatched(watched, episodes: season.episodes, in: series) }
                        ) {
                            model.play(episode, in: series, season: season.episodes)
                        }
                    }
                }
                .padding(.horizontal, metrics.gutter)
                .frame(maxWidth: 980, alignment: .leading)
            }
        }
    }

    private var facts: [String] {
        var items: [String] = []
        if let year = series.year { items.append(String(year)) }
        if let seasons = details?.seasons, !seasons.isEmpty {
            items.append(String(localized: "\(seasons.count) seasons"))
        }
        if let rating = model.ratingFact(for: .series(series), provider: details?.info.rating ?? series.rating) { items.append(rating) }
        if let genre = details?.info.genre ?? series.genre { items.append(genre) }
        return items
    }

    /// The format of the episodes, when they all share one.
    private var badges: [String] {
        guard let episodes = details?.seasons.flatMap(\.episodes), let format = MediaFormat.label(for: episodes.map(\.streamURL)) else { return [] }
        return [format]
    }
}

/// Episode with its 16:9 still, progress, title, length and plot; tapping it plays it.
struct EpisodeRow: View {
    let episode: Episode
    let progress: HistoryEntry?
    var compact = true
    var downloadItem: PlaybackItem?
    var playFromStart: (() -> Void)?
    /// "Mark as Watched" (true) or "Mark as Unwatched" (false) for this episode.
    var toggleWatched: ((Bool) -> Void)?
    /// The same for every episode of its season.
    var markSeasonWatched: ((Bool) -> Void)?
    let play: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Button(action: play) {
                HStack(alignment: .top, spacing: 12) {
                    still
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: "\(episode.number). \(episode.title)")
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(2)
                        HStack(spacing: 6) {
                            if let seconds = episode.durationSeconds, seconds > 0 {
                                Text(Formatting.duration(seconds))
                            }
                            if progress?.isFinished == true {
                                Label("Watched", systemImage: "checkmark.circle.fill")
                                    .labelStyle(.iconOnly)
                                    .foregroundStyle(.white)
                            }
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        if let plot = episode.plot {
                            Text(plot)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(compact ? 2 : 3)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .contextMenu {
                Button("Play", systemImage: "play.fill", action: play)
                if progress?.isResumable == true, let playFromStart {
                    Button("From Start", systemImage: "backward.end", action: playFromStart)
                }
                if let toggleWatched {
                    let watched = progress?.isFinished == true
                    Button(watched ? LocalizedStringKey("Mark as Unwatched") : LocalizedStringKey("Mark as Watched"), systemImage: watched ? "checkmark.circle.fill" : "checkmark.circle") {
                        toggleWatched(!watched)
                    }
                }
                if let markSeasonWatched {
                    Button("Mark Season as Watched", systemImage: "checkmark.circle") { markSeasonWatched(true) }
                    Button("Mark Season as Unwatched", systemImage: "circle") { markSeasonWatched(false) }
                }
            }
            .accessibilityValue(progress?.isFinished == true ? Text("Watched") : Text(verbatim: ""))
            if let toggleWatched {
                // In sight on every row: the check mark marks the episode watched, or unwatched again.
                let watched = progress?.isFinished == true
                Button {
                    toggleWatched(!watched)
                } label: {
                    Image(systemName: watched ? "checkmark.circle.fill" : "checkmark.circle")
                        .font(.title3)
                        .foregroundStyle(watched ? OndoColor.selected : Color.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(watched ? Text("Mark as Unwatched") : Text("Mark as Watched"))
                .accessibilityIdentifier("episodeWatched-\(episode.id)")
            }
            #if TESTFLIGHT_DOWNLOADS || DEBUG
            if let downloadItem {
                DownloadButton(item: downloadItem, compact: true)
                    .buttonStyle(.borderless)
                    .padding(.top, 4)
            }
            #endif
        }
    }

    private var still: some View {
        let width: CGFloat = compact ? 150 : 220
        return OndoColor.surface
            .frame(width: width, height: width * 9 / 16)
            .overlay {
                RemoteImage(url: episode.posterURL, placeholderText: episode.code, contentMode: .fill, maxPixelSize: 440)
            }
            .overlay(alignment: .bottomLeading) {
                Image(systemName: "play.fill")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(7)
                    .background(.black.opacity(0.45), in: Circle())
                    .padding(6)
            }
            .overlay(alignment: .bottom) {
                if let progress, progress.progress > 0, !progress.isFinished {
                    ProgressBar(value: progress.progress).padding(.horizontal, 6).padding(.bottom, 3)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

extension Season {
    /// The name on the season's button: the provider's, except a bare "Season 2" (the provider's default, and the
    /// English name IPTVCore gives a season the provider does not name), which reads in the app's language.
    func displayName(bundle: Bundle = .main) -> String {
        name == "Season \(number)" ? String(localized: "Season \(number)", bundle: bundle) : name
    }
}

import SwiftUI
import IPTVCore

/// Sizes of posters and cards, tuned per device class.
struct ShelfMetrics {
    let compact: Bool

    var posterWidth: CGFloat { compact ? 116 : 150 }
    var wideCardWidth: CGFloat { compact ? 264 : 320 }
    var gutter: CGFloat { compact ? 16 : 24 }
}

/// Value pushed to open the poster grid of a movie or series category.
struct PosterCategoryRoute: Hashable {
    let category: MediaCategory
}

/// A titled horizontal row ("shelf") with an optional "See All" link.
struct Shelf<Content: View>: View {
    private let title: Text
    private let seeAll: AnyHashableRoute?
    private let metrics: ShelfMetrics
    private let content: () -> Content

    init(title: LocalizedStringKey, seeAll: AppSection? = nil, metrics: ShelfMetrics, @ViewBuilder content: @escaping () -> Content) {
        self.title = Text(title)
        self.seeAll = seeAll.map(AnyHashableRoute.section)
        self.metrics = metrics
        self.content = content
    }

    /// "See All" opening a route other than a section (My List → the favourite movies or series); nil: none.
    init(title: LocalizedStringKey, route: AnyHashableRoute?, metrics: ShelfMetrics, @ViewBuilder content: @escaping () -> Content) {
        self.title = Text(title)
        self.seeAll = route
        self.metrics = metrics
        self.content = content
    }

    /// A row named by the viewer (a selection): its name shown as is.
    init(verbatim title: String, route: AnyHashableRoute?, metrics: ShelfMetrics, @ViewBuilder content: @escaping () -> Content) {
        self.title = Text(verbatim: title)
        self.seeAll = route
        self.metrics = metrics
        self.content = content
    }

    /// A provider category: its name is shown as is and "See All" opens its grid.
    init(category: MediaCategory, metrics: ShelfMetrics, @ViewBuilder content: @escaping () -> Content) {
        self.title = Text(verbatim: category.name)
        self.seeAll = .category(PosterCategoryRoute(category: category))
        self.metrics = metrics
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                title
                    .font(.title3.bold())
                    .lineLimit(1)
                Spacer()
                if let seeAll {
                    seeAllLink(seeAll)
                }
            }
            .padding(.horizontal, metrics.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    content()
                }
                .padding(.horizontal, metrics.gutter)
            }
            .scrollClipDisabled()
        }
    }

    @ViewBuilder
    private func seeAllLink(_ route: AnyHashableRoute) -> some View {
        let label = HStack(spacing: 3) {
            Text("See All")
            Image(systemName: "chevron.forward").font(.caption.weight(.bold))
        }
        .font(.subheadline.weight(.semibold))
        Group {
            switch route {
            case .section(let section): NavigationLink(value: section) { label }
            case .category(let category): NavigationLink(value: category) { label }
            case .favorites(let favorites): NavigationLink(value: favorites) { label }
            case .metadata(let metadata): NavigationLink(value: metadata) { label }
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(OndoColor.selected)
    }
}

/// Where a shelf's "See All" leads.
enum AnyHashableRoute: Hashable {
    case section(AppSection)
    case category(PosterCategoryRoute)
    case favorites(FavoritesRoute)
    /// Titles of every type (a selection's on Home).
    case metadata(MetadataRoute)
}

/// Poster of a movie or series opening its detail page.
struct ShowcasePoster: View {
    @Environment(AppModel.self) private var model
    let item: ShowcaseItem
    /// Fixed width in rows; `nil` fills a grid cell.
    let width: CGFloat?
    var showsTitle = false

    var body: some View {
        switch item {
        case .movie(let movie):
            NavigationLink(value: movie) { card }
                .cardButtonStyle()
                .contextMenu { MovieContextMenu(movie: movie) }
        case .series(let series):
            NavigationLink(value: series) { card }
                .cardButtonStyle()
                .contextMenu { SeriesContextMenu(series: series) }
        }
    }

    private var watched: Bool { model.isWatched(item) }

    private var card: some View {
        let progress: Double? = {
            if case .movie(let movie) = item { return model.progress(movie: movie)?.progress }
            return nil
        }()
        return VStack(alignment: .leading, spacing: 6) {
            PosterArtwork(url: item.posterURL, title: item.title, progress: progress, watched: watched, scoreKey: item.id)
            if showsTitle {
                Text(item.title)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
            }
        }
        .frame(width: width)
        // The title, then TMDb's score ("TMDb rating 7.8").
        .posterScoreLabel(title: item.title, key: item.id)
        .accessibilityValue(watched ? Text("Watched") : Text(verbatim: ""))
        // What TMDb knows of it is fetched in the background as its row appears (with a TMDb token).
        .onAppear { model.prefetchMetadata([item]) }
    }
}

/// 2:3 artwork with soft corners, a hairline border, an optional progress bar, the watched badge and TMDb's score.
struct PosterArtwork: View {
    let url: URL?
    let title: String
    var progress: Double?
    /// Watched (`AppModel.isWatched`): a check mark in the top leading corner, its full bar left out.
    var watched = false
    /// The title whose TMDb score shows in the bottom leading corner (`ShowcaseItem.id`, `AppModel.posterScores`); nil:
    /// none (a title page's poster, whose facts give the rating).
    var scoreKey: String?

    var body: some View {
        OndoColor.surface
            .aspectRatio(2 / 3, contentMode: .fit)
            .overlay {
                RemoteImage(url: url, placeholderText: title, contentMode: .fill, maxPixelSize: 480)
            }
            .overlay(alignment: .bottom) {
                if let progress, progress > 0, !watched {
                    ProgressBar(value: progress).padding(8)
                }
            }
            .overlay(alignment: .topLeading) {
                if watched { WatchedBadge().padding(6) }
            }
            .overlay(alignment: .bottomLeading) {
                // Above the progress bar when there is one (4 pt high, 8 pt from the bottom).
                if let scoreKey {
                    PosterScoreBadge(key: scoreKey)
                        .padding(.leading, 6)
                        .padding(.bottom, (progress ?? 0) > 0 && !watched ? 18 : 6)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(.white.opacity(0.08), lineWidth: 1)
            }
            .cardLift()
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// The check mark of a watched title on its thumbnail: white on a dark disc, legible on any poster, in the top leading
/// corner (TMDb's score is in the bottom one), never over the title below. VoiceOver says "Watched".
struct WatchedBadge: View {
    var size: CGFloat = 22

    var body: some View {
        Image(systemName: "checkmark")
            .font(.system(size: size * 0.5, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(.black.opacity(0.6), in: Circle())
            .overlay { Circle().strokeBorder(.white.opacity(0.85), lineWidth: 1.2) }
            .accessibilityElement()
            .accessibilityLabel(Text("Watched"))
            .accessibilityIdentifier("watchedBadge")
    }
}

private struct PosterScoresKey: EnvironmentKey {
    static let defaultValue: [String: String] = [:]
}

extension EnvironmentValues {
    /// TMDb's score of each title, by `ShowcaseItem.id`, as written ("7.8"): `AppModel.posterScores`, given by
    /// `PosterScoresProvider`. A card reads it with one lookup.
    var posterScores: [String: String] {
        get { self[PosterScoresKey.self] }
        set { self[PosterScoresKey.self] = newValue }
    }
}

/// Gives the cards below it TMDb's scores (`AppModel.posterScores`): only this view and the badges read the map, so a
/// new one redraws the badges, not the screens.
struct PosterScoresProvider<Content: View>: View {
    @Environment(AppModel.self) private var model
    @ViewBuilder let content: () -> Content

    var body: some View {
        content().environment(\.posterScores, model.posterScores)
    }
}

/// TMDb's score of a title on its thumbnail (`PosterScorePill`), when it is known; nothing otherwise: no placeholder,
/// never the provider's rating.
struct PosterScoreBadge: View {
    @Environment(\.posterScores) private var scores
    let key: String?

    var body: some View {
        if let key, let score = scores[key] {
            PosterScorePill(score: score)
        }
    }
}

/// A white star and the score on a dark pill, legible on any picture; a little larger on iPad and Mac. VoiceOver reads
/// it with the card's title (`posterScoreLabel`), not on its own.
struct PosterScorePill: View {
    let score: String
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var large: Bool { sizeClass == .regular }
    #else
    private var large: Bool { true }
    #endif

    var body: some View {
        HStack(spacing: large ? 3 : 2) {
            Image(systemName: "star.fill")
                .font(.system(size: large ? 10 : 9, weight: .bold))
            Text(verbatim: score)
                .font(.system(size: large ? 12.5 : 11, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, large ? 6 : 5)
        .padding(.vertical, 2)
        .background(.black.opacity(0.65), in: Capsule())
        .fixedSize()
        .accessibilityHidden(true)
    }
}

/// A card's accessibility label: its title, then TMDb's score when known ("Northern Lights, TMDb rating 7.8").
struct PosterScoreLabel: ViewModifier {
    @Environment(\.posterScores) private var scores
    let title: String
    let key: String?

    func body(content: Content) -> some View {
        content.accessibilityLabel(Self.label(title: title, score: key.flatMap { scores[$0] }))
    }

    static func label(title: String, score: String?) -> Text {
        guard let score else { return Text(verbatim: title) }
        return Text(verbatim: title + ", ") + Text("TMDb rating \(score)")
    }
}

extension View {
    /// The card's title, then TMDb's score (`PosterScoreLabel`).
    func posterScoreLabel(title: String, key: String?) -> some View {
        modifier(PosterScoreLabel(title: title, key: key))
    }
}

/// Thin rounded progress bar drawn over artwork.
struct ProgressBar: View {
    let value: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25))
                Capsule().fill(OndoColor.selected).frame(width: proxy.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 4)
    }
}

/// 16:9 "Continue watching" card: artwork, title, what is left and progress.
struct ContinueCard: View {
    @Environment(AppModel.self) private var model
    let entry: HistoryEntry
    let width: CGFloat

    var body: some View {
        let item = model.showcaseItem(for: entry)
        let artwork = item.flatMap { model.backdropURL(for: $0) } ?? entry.imageURL
        // A series going on with its next episode: from its start, never the finished one named as if to resume it.
        let upNext = model.isUpNext(entry)
        Button {
            model.resume(entry)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                OndoColor.surface
                    .frame(width: width, height: width * 9 / 16)
                    .overlay {
                        RemoteImage(url: artwork, placeholderText: entry.title, contentMode: .fill, maxPixelSize: 720)
                    }
                    .overlay {
                        LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                    }
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 6) {
                                Image(systemName: "play.fill").font(.caption)
                                if upNext {
                                    Text("Play").font(.caption.weight(.semibold))
                                } else {
                                    Text(entry.timeLeftText).font(.caption.weight(.semibold))
                                }
                                // The play icon, then TMDb's score of the title, above the progress bar.
                                PosterScoreBadge(key: item?.id)
                                    .padding(.leading, 2)
                            }
                            .foregroundStyle(.white)
                            if !upNext { ProgressBar(value: entry.progress) }
                        }
                        .padding(10)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .cardLift()
                Text(entry.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    // The title, then TMDb's score ("TMDb rating 7.8").
                    .posterScoreLabel(title: entry.title, key: item?.id)
                // A film of several versions: the one in progress.
                // An episode: its line as its series' page shows it; none for a finished one standing for its series.
                if let subtitle = (upNext && entry.isFinished ? nil : model.episodeLine(of: entry)) ?? model.versionLabel(for: entry) {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(width: width)
            .contentShape(Rectangle())
        }
        .cardButtonStyle()
        .contextMenu {
            if !upNext {
                Button(role: .destructive) {
                    model.removeFromHistory(entry)
                } label: {
                    Label("Remove from History", systemImage: "trash")
                }
            }
        }
    }

}

struct MovieContextMenu: View {
    @Environment(AppModel.self) private var model
    let movie: Movie

    var body: some View {
        Button {
            model.playFilm(movie)
        } label: {
            Label("Play", systemImage: "play.fill")
        }
        Button {
            model.toggleFavoriteFilm(movie)
        } label: {
            MyListLabel(inList: model.isFavoriteFilm(movie))
        }
        AddToListMenu(item: .movie(movie))
    }
}

struct SeriesContextMenu: View {
    @Environment(AppModel.self) private var model
    let series: Series

    var body: some View {
        Button {
            Task { await model.playShowcase(.series(series)) }
        } label: {
            Label("Play", systemImage: "play.fill")
        }
        Button {
            model.toggleFavorite(series)
        } label: {
            MyListLabel(inList: model.isFavorite(.series, id: series.id))
        }
        AddToListMenu(item: .series(series))
    }
}

/// "Add to My List" / "Remove from My List".
struct MyListLabel: View {
    let inList: Bool

    var body: some View {
        if inList {
            Label("Remove from My List", systemImage: MyListSymbol.name(inList: true))
        } else {
            Label("Add to My List", systemImage: MyListSymbol.name(inList: false))
        }
    }
}

extension HistoryEntry {
    /// "42 min left", or "Resume" for the last minute.
    var timeLeftText: String {
        let seconds = max(duration - position, 0)
        guard seconds > 60 else { return String(localized: "Resume") }
        // A duration from another device or a backup can be past Int's range.
        return String(localized: "\(Int(min(seconds / 60, 1e12))) min left")
    }
}

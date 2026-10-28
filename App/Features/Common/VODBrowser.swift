import SwiftUI
import IPTVCore

/// Movies and Series tabs: category chips, continue watching and My List, then one row per category
/// ("See All" opens its grid). Searching shows a grid of matches. Locked categories only appear as chips
/// and ask for the PIN.
struct VODBrowser: View {
    @Environment(AppModel.self) private var model
    @Environment(NavigationRouter.self) private var router: NavigationRouter?
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    let type: ContentType
    @State private var query = ""
    @State private var pinRequest: MediaCategory?

    /// Rows show the first titles of a category; "See All" has the rest.
    private static let rowLength = 20

    private var compact: Bool {
        #if os(iOS)
        sizeClass == .compact
        #else
        false
        #endif
    }

    var body: some View {
        let metrics = ShelfMetrics(compact: compact)
        ScrollView {
            if query.isEmpty {
                shelves(metrics)
            } else {
                results(metrics)
            }
        }
        .background(Color.appBackground)
        .searchable(text: $query, prompt: type == .movie ? Text("Search movies") : Text("Search series"))
        .navigationTitle(type == .movie ? Text("Movies") : Text("Series"))
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: PosterCategoryRoute(category: PseudoCategory.all(type))) {
                    Label("All", systemImage: "square.grid.2x2")
                }
            }
            ToolbarItem(placement: .primaryAction) {
                CategoryFilterButton(type: type)
            }
        }
        .navigationDestination(for: PosterCategoryRoute.self) { route in
            if type == .movie {
                MovieGridView(category: route.category)
            } else {
                SeriesGridView(category: route.category)
            }
        }
        .sheet(item: $pinRequest) { category in
            PINPromptView { router?.push(PosterCategoryRoute(category: category)) }
        }
    }

    // MARK: Rows

    private func shelves(_ metrics: ShelfMetrics) -> some View {
        let categories = model.categories(for: type)
        let resume = model.continueWatching(type)
        let mine = model.myList().filter { item in
            switch item {
            case .movie: return type == .movie
            case .series: return type == .series
            }
        }

        return LazyVStack(alignment: .leading, spacing: compact ? 26 : 34) {
            chips(categories, metrics: metrics)
            if !resume.isEmpty {
                Shelf(title: "Continue Watching", metrics: metrics) {
                    ForEach(resume) { entry in
                        ContinueCard(entry: entry, width: metrics.wideCardWidth)
                    }
                }
            }
            if !mine.isEmpty {
                Shelf(title: "My List", route: .favorites(FavoritesRoute(type: type)), metrics: metrics) {
                    ForEach(mine) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }
            if type == .movie {
                let winners = model.awardWinners(limit: Self.rowLength)
                if !winners.isEmpty {
                    Shelf(title: "Award Winners", metrics: metrics) {
                        ForEach(winners) { movie in
                            ShowcasePoster(item: .movie(movie), width: metrics.posterWidth)
                        }
                    }
                }
            }
            ForEach(categories.filter { !model.isLocked($0) }) { category in
                // A category whose movies are all hidden as watched has no row.
                let titles = items(in: category)
                if !titles.isEmpty {
                    Shelf(category: category, metrics: metrics) {
                        ForEach(titles) { item in
                            ShowcasePoster(item: item, width: metrics.posterWidth)
                        }
                    }
                }
            }
            HiddenCategoriesNote(type: type)
                .padding(.horizontal, metrics.gutter)
        }
        .padding(.vertical, 12)
        .overlay {
            if categories.isEmpty {
                ContentUnavailableView(type == .movie ? String(localized: "No movies") : String(localized: "No series"),
                                       systemImage: AppSection(type: type).systemImage)
            }
        }
    }

    private func items(in category: MediaCategory) -> [ShowcaseItem] {
        if type == .movie {
            return model.movies(in: category, sort: .playlist).prefix(Self.rowLength).map(ShowcaseItem.movie)
        }
        return model.series(in: category, sort: .playlist).prefix(Self.rowLength).map(ShowcaseItem.series)
    }

    // MARK: Chips

    private func chips(_ categories: [MediaCategory], metrics: ShelfMetrics) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(PseudoCategory.all(type))
                ForEach(categories) { category in
                    chip(category)
                }
            }
            .padding(.horizontal, metrics.gutter)
        }
        .scrollClipDisabled()
    }

    @ViewBuilder
    private func chip(_ category: MediaCategory) -> some View {
        let locked = model.isLocked(category)
        let label = HStack(spacing: 5) {
            if locked { Image(systemName: "lock.fill").font(.caption2) }
            Text(verbatim: category.name).lineLimit(1)
        }
        .font(.subheadline.weight(.semibold))
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(OndoColor.surfaceHigh, in: Capsule())
        .contentShape(Capsule())

        Group {
            if locked {
                Button { pinRequest = category } label: { label }
            } else {
                NavigationLink(value: PosterCategoryRoute(category: category)) { label }
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            if category.id != PseudoCategory.all {
                CategoryMenu(category: category)
            }
        }
    }

    // MARK: Search

    private func results(_ metrics: ShelfMetrics) -> some View {
        let found = model.search(query)
        let items: [ShowcaseItem] = type == .movie ? found.movies.map(ShowcaseItem.movie) : found.series.map(ShowcaseItem.series)
        return LazyVGrid(columns: posterColumns, spacing: 18) {
            ForEach(items) { item in
                ShowcasePoster(item: item, width: nil, showsTitle: true)
            }
        }
        .padding(metrics.gutter)
        .overlay {
            if items.isEmpty { ContentUnavailableView.search(text: query) }
        }
    }
}

/// Hide / lock actions of a category (context menu).
struct CategoryMenu: View {
    @Environment(AppModel.self) private var model
    let category: MediaCategory

    var body: some View {
        Button {
            model.setHidden(true, category: category)
        } label: {
            Label("Hide Category", systemImage: "eye.slash")
        }
        if model.settings.parentalControlEnabled {
            let isLockedSetting = model.isLockConfigured(category)
            Button {
                model.setLocked(!isLockedSetting, category: category)
            } label: {
                Label(isLockedSetting ? String(localized: "Unlock Category") : String(localized: "Lock Category"),
                      systemImage: isLockedSetting ? "lock.open" : "lock")
            }
        }
    }
}

private extension AppSection {
    init(type: ContentType) {
        switch type {
        case .live: self = .live
        case .movie: self = .movies
        case .series: self = .series
        }
    }
}

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
        .sheet(item: $pinRequest) { category in
            PINPromptView { router?.push(PosterCategoryRoute(category: category)) }
        }
    }

    // MARK: Rows

    private func shelves(_ metrics: ShelfMetrics) -> some View {
        let categories = model.categories(for: type)
        // The rows in the viewer's order (Settings → Row order), without those they hid. Locked categories only appear
        // as chips.
        let rows = model.arrangedRows(type, categories: categories).filter { !$0.hidden }.map(\.key)
        let byKey = Dictionary(categories.filter { !model.isLocked($0) }.map { (RowOrder.categoryKey(id: $0.id, name: $0.name), $0) },
                               uniquingKeysWith: { first, _ in first })

        return LazyVStack(alignment: .leading, spacing: compact ? 26 : 34) {
            chips(categories, metrics: metrics)
            ForEach(rows, id: \.self) { key in
                row(key, categories: byKey, metrics: metrics)
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

    /// Where the "See All" of the row `key` leads: every row that can hold more titles than it shows has one, its grid
    /// listing them all in the row's order. Continue Watching has none (resume cards: a poster grid cannot resume), nor
    /// Trending (the row already lists every trending title). `category`: the row's, for a provider category's row.
    static func seeAll(_ key: String, type: ContentType, category: MediaCategory? = nil) -> AnyHashableRoute? {
        switch key {
        case RowOrder.all: return .category(PosterCategoryRoute(category: PseudoCategory.all(type)))
        case RowOrder.continueWatching, RowOrder.trending: return nil
        case RowOrder.myList: return .favorites(FavoritesRoute(type: type))
        case RowOrder.newReleases: return .category(PosterCategoryRoute(category: PseudoCategory.newReleases(type)))
        case RowOrder.awardWinners: return .category(PosterCategoryRoute(category: PseudoCategory.awardWinners(type)))
        default: return category.map { .category(PosterCategoryRoute(category: $0)) }
        }
    }

    /// One row of the tab, by its key (`RowOrder`); nothing when it has no titles.
    @ViewBuilder
    private func row(_ key: String, categories: [String: MediaCategory], metrics: ShelfMetrics) -> some View {
        switch key {
        case RowOrder.all:
            let titles = model.allTitles(type, limit: Self.rowLength)
            if !titles.isEmpty {
                let count = model.allCount(type).formatted()
                Shelf(title: type == .movie ? "All Movies (\(count))" : "All Series (\(count))",
                      route: Self.seeAll(key, type: type), metrics: metrics) {
                    ForEach(titles) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
                .accessibilityIdentifier("shelf-all")
            }
        case RowOrder.continueWatching:
            let resume = model.continueWatching(type)
            if !resume.isEmpty {
                Shelf(title: "Continue Watching", route: Self.seeAll(key, type: type), metrics: metrics) {
                    ForEach(resume) { entry in
                        ContinueCard(entry: entry, width: metrics.wideCardWidth)
                    }
                }
            }
        case RowOrder.myList:
            let mine = model.myList().filter { item in
                switch item {
                case .movie: return type == .movie
                case .series: return type == .series
                }
            }
            if !mine.isEmpty {
                Shelf(title: "My List", route: Self.seeAll(key, type: type), metrics: metrics) {
                    ForEach(mine) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }
        case RowOrder.newReleases:
            let titles = model.rowTitles(PseudoCategory.newReleases, type: type, limit: Self.rowLength)
            if !titles.isEmpty {
                Shelf(title: type == .movie ? "New Movies" : "New Series", route: Self.seeAll(key, type: type), metrics: metrics) {
                    ForEach(titles) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }
        case RowOrder.trending:
            let titles = model.trending(type)
            if !titles.isEmpty {
                Shelf(title: "Trending", route: Self.seeAll(key, type: type), metrics: metrics) {
                    ForEach(titles) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }
        case RowOrder.awardWinners:
            // Films in Movies, series in Series; no row when none of the playlist's titles won.
            let winners = model.rowTitles(PseudoCategory.awardWinners, type: type, limit: Self.rowLength)
            if !winners.isEmpty {
                Shelf(title: "Award Winners", route: Self.seeAll(key, type: type), metrics: metrics) {
                    ForEach(winners) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }
        default:
            if let id = Selections.id(ofRow: key) {
                if let selection = model.selection(id) {
                    // A selection's row: its titles of the day; "See All" opens them all, in their order.
                    let titles = model.selectionTitles(selection, tab: AppModel.selectionTab(type), limit: Self.rowLength)
                    if !titles.isEmpty {
                        Shelf(category: PseudoCategory.selection(id, name: model.selectionName(selection), type: type), metrics: metrics) {
                            ForEach(titles) { item in
                                ShowcasePoster(item: item, width: metrics.posterWidth)
                            }
                        }
                        .accessibilityIdentifier("shelf-selection")
                    }
                }
            } else if let category = categories[key] {
                // A category whose movies are all hidden as watched has no row.
                let titles = items(in: category)
                if !titles.isEmpty {
                    // "See All": the category's grid (`seeAll`).
                    Shelf(category: category, metrics: metrics) {
                        ForEach(titles) { item in
                            ShowcasePoster(item: item, width: metrics.posterWidth)
                        }
                    }
                }
            }
        }
    }

    /// A category's row: its first titles, in the playlist's order.
    @MainActor static func items(in category: MediaCategory, type: ContentType, model: AppModel) -> [ShowcaseItem] {
        if type == .movie {
            return model.movies(in: category, sort: .playlist).prefix(rowLength).map(ShowcaseItem.movie)
        }
        return model.series(in: category, sort: .playlist).prefix(rowLength).map(ShowcaseItem.series)
    }

    private func items(in category: MediaCategory) -> [ShowcaseItem] {
        Self.items(in: category, type: type, model: model)
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

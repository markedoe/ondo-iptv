import SwiftUI
import IPTVCore

/// My List: the movies, series and channels the viewer keeps, and their collections.
struct FavoritesView: View {
    @Environment(AppModel.self) private var model
    @State private var type: ContentType
    @State private var showingLists = false

    /// `nil` opens the collections.
    init(initialType: ContentType? = .movie) {
        _type = State(initialValue: initialType ?? .movie)
        _showingLists = State(initialValue: initialType == nil)
    }

    var body: some View {
        Group {
            if showingLists {
                CustomListsView()
            } else {
                favoritesList
            }
        }
        .safeAreaInset(edge: .top) {
            Picker("Type", selection: Binding(
                get: { showingLists ? nil : type },
                set: { value in
                    if let value { type = value; showingLists = false } else { showingLists = true }
                }
            )) {
                Text("Movies").tag(ContentType?.some(.movie))
                Text("Series").tag(ContentType?.some(.series))
                Text("Channels").tag(ContentType?.some(.live))
                Text("Collections").tag(ContentType?.none)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(.bar)
        }
        .navigationTitle("My List")
        .onAppear {
            // Opened on an empty type while another has items: show the one with items.
            guard !showingLists, model.favorites(type).isEmpty,
                  let filled = [ContentType.movie, .series, .live].first(where: { !model.favorites($0).isEmpty }) else { return }
            type = filled
        }
    }

    private var favoritesList: some View {
        let entries = model.favorites(type)
        return List {
            ForEach(entries) { entry in
                row(entry)
                    // Swipe to delete is iOS only: the menu works everywhere (Control-click on the Mac).
                    .contextMenu {
                        Button("Remove from My List", systemImage: "minus.circle", role: .destructive) { toggle(entry) }
                    }
            }
            .onMove { source, destination in
                model.moveFavorites(type, fromOffsets: source, toOffset: destination)
            }
            .onDelete { offsets in
                for offset in offsets {
                    toggle(entries[offset])
                }
            }
        }
        .overlay {
            if entries.isEmpty {
                ContentUnavailableView("My List is empty", systemImage: "list.star", description: Text("Use “Add to My List” on a movie, a series or a channel to find it here."))
            }
        }
        #if os(iOS)
        .toolbar { EditButton() }
        #endif
    }

    @ViewBuilder
    private func row(_ entry: FavoriteEntry) -> some View {
        switch entry.reference.type {
        case .live:
            if let channel = model.index.channelsByID[entry.reference.itemID] {
                Button {
                    model.play(channel, in: model.favoriteChannels())
                } label: {
                    ChannelRow(channel: channel)
                }
                .buttonStyle(.plain)
            }
        case .movie:
            if let movie = model.index.moviesByID[entry.reference.itemID] {
                NavigationLink(value: movie) {
                    SearchResultRow(title: movie.name, subtitle: movie.year.map(String.init), imageURL: movie.posterURL, icon: "film")
                }
            }
        case .series:
            if let series = model.index.seriesByID[entry.reference.itemID] {
                NavigationLink(value: series) {
                    SearchResultRow(title: series.name, subtitle: series.year.map(String.init), imageURL: series.coverURL, icon: "play.rectangle.on.rectangle")
                }
            }
        }
    }

    private func toggle(_ entry: FavoriteEntry) {
        switch entry.reference.type {
        case .live: if let item = model.index.channelsByID[entry.reference.itemID] { model.toggleFavorite(item) }
        case .movie: if let item = model.index.moviesByID[entry.reference.itemID] { model.toggleFavorite(item) }
        case .series: if let item = model.index.seriesByID[entry.reference.itemID] { model.toggleFavorite(item) }
        }
    }
}

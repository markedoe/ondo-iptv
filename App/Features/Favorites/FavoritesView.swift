import SwiftUI
import IPTVCore

struct FavoritesView: View {
    @Environment(AppModel.self) private var model
    @State private var type: ContentType
    @State private var showingLists = false

    init(initialType: ContentType = .live) {
        _type = State(initialValue: initialType)
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
                Text("Channels").tag(ContentType?.some(.live))
                Text("Movies").tag(ContentType?.some(.movie))
                Text("Series").tag(ContentType?.some(.series))
                Text("Lists").tag(ContentType?.none)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(.bar)
        }
        .navigationTitle(title)
    }

    /// Movies and series are "My List" everywhere else in the app.
    private var title: Text {
        if showingLists { return Text("Lists") }
        return type == .live ? Text("Favorites") : Text("My List")
    }

    private var favoritesList: some View {
        let entries = model.favorites(type)
        return List {
            ForEach(entries) { entry in
                row(entry)
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
                ContentUnavailableView("No favorites", systemImage: "star", description: Text("Add channels, movies and series to your favorites to find them here."))
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

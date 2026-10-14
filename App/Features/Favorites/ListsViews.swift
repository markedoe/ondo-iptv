import SwiftUI
import IPTVCore

/// Anything that can be put in My List or in a collection.
enum LibraryItem {
    case channel(Channel)
    case movie(Movie)
    case series(Series)

    var type: ContentType {
        switch self {
        case .channel: return .live
        case .movie: return .movie
        case .series: return .series
        }
    }

    var id: String {
        switch self {
        case .channel(let channel): return channel.id
        case .movie(let movie): return movie.id
        case .series(let series): return series.id
        }
    }

    var title: String {
        switch self {
        case .channel(let channel): return channel.name
        case .movie(let movie): return movie.name
        case .series(let series): return series.name
        }
    }

    var imageURL: URL? {
        switch self {
        case .channel(let channel): return channel.logoURL
        case .movie(let movie): return movie.posterURL
        case .series(let series): return series.coverURL
        }
    }
}

extension AppModel {
    func libraryItem(for reference: MediaReference) -> LibraryItem? {
        switch reference.type {
        case .live: return index.channelsByID[reference.itemID].map(LibraryItem.channel)
        case .movie: return index.moviesByID[reference.itemID].map(LibraryItem.movie)
        case .series: return index.seriesByID[reference.itemID].map(LibraryItem.series)
        }
    }
}

/// "Add to Collection" submenu used in context menus.
struct AddToListMenu: View {
    let item: LibraryItem

    var body: some View {
        Menu {
            CollectionMenuItems(item: item)
        } label: {
            Label("Add to Collection", systemImage: "rectangle.stack.badge.plus")
        }
    }
}

/// The viewer's collections (checked when the item is in one) and a new one with the item.
struct CollectionMenuItems: View {
    @Environment(AppModel.self) private var model
    let item: LibraryItem

    var body: some View {
        Group {
            ForEach(model.library.customLists) { list in
                Button {
                    model.toggle(item, inList: list.id)
                } label: {
                    if model.isInList(item, list: list) {
                        Label(list.name, systemImage: "checkmark")
                    } else {
                        Text(list.name)
                    }
                }
            }
            if !model.library.customLists.isEmpty { Divider() }
            Button {
                let id = model.createList(named: String(localized: "Collection \(model.library.customLists.count + 1)"))
                model.toggle(item, inList: id)
            } label: {
                Label("New Collection with This Item", systemImage: "plus")
            }
        }
    }
}

/// The viewer's collections (custom lists of channels, movies and series).
struct CustomListsView: View {
    @Environment(AppModel.self) private var model
    @State private var showingNewList = false
    @State private var newListName = ""

    var body: some View {
        List {
            ForEach(model.library.customLists) { list in
                NavigationLink(value: CustomListRoute(id: list.id)) {
                    LabeledContent {
                        Text("\(model.items(in: list).count)")
                    } label: {
                        Label(list.name, systemImage: "rectangle.stack")
                    }
                }
            }
            .onDelete { offsets in
                let lists = model.library.customLists
                for offset in offsets { model.deleteList(lists[offset].id) }
            }
        }
        .overlay {
            if model.library.customLists.isEmpty {
                ContentUnavailableView {
                    Label("No collections", systemImage: "rectangle.stack")
                } description: {
                    Text("Group movies, series and channels your way. Use “Add to Collection” on their page or in their menu.")
                } actions: {
                    Button("New Collection") { showingNewList = true }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showingNewList = true
                } label: {
                    Label("New Collection", systemImage: "plus")
                }
            }
        }
        .alert("New Collection", isPresented: $showingNewList) {
            TextField("Name", text: $newListName)
            Button("Create") {
                let name = newListName.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty { model.createList(named: name) }
                newListName = ""
            }
            Button("Cancel", role: .cancel) { newListName = "" }
        }
    }
}

struct CustomListDetailView: View {
    @Environment(AppModel.self) private var model
    let listID: UUID
    @State private var showingRename = false
    @State private var name = ""

    private var list: CustomList? { model.library.customLists.first { $0.id == listID } }

    var body: some View {
        let entries = list.map { model.items(in: $0) } ?? []
        List {
            ForEach(entries) { entry in
                if let item = model.libraryItem(for: entry.reference) {
                    row(item)
                }
            }
            .onMove { model.moveListItems(listID, fromOffsets: $0, toOffset: $1) }
            .onDelete { offsets in
                for offset in offsets {
                    if let item = model.libraryItem(for: entries[offset].reference) {
                        model.toggle(item, inList: listID)
                    }
                }
            }
        }
        .overlay {
            if entries.isEmpty {
                ContentUnavailableView("Empty collection", systemImage: "rectangle.stack", description: Text("Use “Add to Collection” on a movie, a series or a channel."))
            }
        }
        .navigationTitle(list?.name ?? "")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    name = list?.name ?? ""
                    showingRename = true
                } label: {
                    Label("Rename", systemImage: "pencil")
                }
            }
            #if os(iOS)
            ToolbarItem(placement: .primaryAction) { EditButton() }
            #endif
        }
        .alert("Rename Collection", isPresented: $showingRename) {
            TextField("Name", text: $name)
            Button("Save") {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty { model.renameList(listID, to: trimmed) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func row(_ item: LibraryItem) -> some View {
        switch item {
        case .channel(let channel):
            Button {
                model.play(channel)
            } label: {
                ChannelRow(channel: channel)
            }
            .buttonStyle(.plain)
        case .movie(let movie):
            NavigationLink(value: movie) {
                SearchResultRow(title: movie.name, subtitle: movie.year.map(String.init), imageURL: movie.posterURL, icon: "film")
            }
        case .series(let series):
            NavigationLink(value: series) {
                SearchResultRow(title: series.name, subtitle: series.year.map(String.init), imageURL: series.coverURL, icon: "play.rectangle.on.rectangle")
            }
        }
    }
}

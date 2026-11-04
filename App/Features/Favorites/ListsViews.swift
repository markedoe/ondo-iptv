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

/// The small mark of a collection shared with every profile (docs/PROFILES.md, "Shared lists").
struct SharedListMark: View {
    var body: some View {
        Image(systemName: "person.2.fill")
            .font(.caption)
            .foregroundStyle(.secondary)
            .accessibilityLabel("Shared")
            .accessibilityIdentifier("sharedMark")
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
            // A Kids profile adds to its own collections only: the shared ones it sees but does not change.
            ForEach(model.customLists.filter { model.canEditList($0.id) }) { list in
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
            if !model.customLists.isEmpty { Divider() }
            Button {
                let id = model.createList(named: String(localized: "Collection \(model.customLists.count + 1)"))
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
    @State private var unsharing: CustomList?

    var body: some View {
        List {
            ForEach(model.customLists) { list in
                NavigationLink(value: CustomListRoute(id: list.id)) {
                    LabeledContent {
                        HStack(spacing: 6) {
                            if model.isShared(list.id) { SharedListMark() }
                            Text("\(model.items(in: list).count)")
                        }
                    } label: {
                        Label(list.name, systemImage: "rectangle.stack")
                    }
                }
                // Swipe to delete is iOS only: the menu works everywhere (Control-click on the Mac). A shared collection is
                // deleted from its page, which asks first: it goes for every profile.
                .contextMenu {
                    if model.canShareList(list.id) {
                        Toggle("Shared with All Profiles", systemImage: "person.2", isOn: Binding(
                            get: { model.isShared(list.id) },
                            set: { shared in if shared { model.setListShared(list.id, true) } else { unsharing = list } }
                        ))
                    }
                    if !model.isShared(list.id) {
                        Button("Delete Collection", systemImage: "trash", role: .destructive) { model.deleteList(list.id) }
                    }
                }
                .deleteDisabled(model.isShared(list.id))
            }
            .onDelete { offsets in
                let lists = model.customLists
                for offset in offsets where !model.isShared(lists[offset].id) { model.deleteList(lists[offset].id) }
            }
        }
        .confirmationDialog("Stop sharing this collection?", isPresented: Binding(get: { unsharing != nil }, set: { if !$0 { unsharing = nil } }),
                            titleVisibility: .visible, presenting: unsharing) { list in
            Button("Stop Sharing") { model.setListShared(list.id, false) }
        } message: { list in
            Text("The other profiles will no longer see “\(list.name)”. It goes back to your collections.")
        }
        .overlay {
            if model.customLists.isEmpty {
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
    @Environment(\.dismiss) private var dismiss
    let listID: UUID
    @State private var showingRename = false
    @State private var showingDelete = false
    @State private var showingUnshare = false
    @State private var name = ""

    private var list: CustomList? { model.customLists.first { $0.id == listID } }
    private var shared: Bool { model.isShared(listID) }
    /// A Kids profile sees a shared collection (without what its rules hide) and changes nothing of it.
    private var canEdit: Bool { model.canEditList(listID) }

    var body: some View {
        let entries = list.map { model.items(in: $0) } ?? []
        List {
            if model.canShareList(listID) {
                Section {
                    Toggle(isOn: Binding(get: { shared }, set: { on in if on { model.setListShared(listID, true) } else { showingUnshare = true } })) {
                        Label("Shared with All Profiles", systemImage: "person.2")
                    }
                    .accessibilityIdentifier("sharedSwitch")
                }
            } else if shared {
                Label("Shared with All Profiles", systemImage: "person.2")
                    .foregroundStyle(.secondary)
            }
            ForEach(entries) { entry in
                if let item = model.libraryItem(for: entry.reference) {
                    row(item)
                        .contextMenu {
                            if canEdit {
                                Button("Remove from Collection", systemImage: "minus.circle", role: .destructive) {
                                    model.toggle(item, inList: listID)
                                }
                            }
                        }
                }
            }
            .onMove(perform: canEdit ? { model.moveListItems(listID, fromOffsets: $0, toOffset: $1) } : nil)
            .deleteDisabled(!canEdit)
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
            if shared {
                ToolbarItem(placement: .status) { SharedListMark() }
            }
            if canEdit {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        name = list?.name ?? ""
                        showingRename = true
                    } label: {
                        Label("Rename", systemImage: "pencil")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(role: .destructive) {
                        showingDelete = true
                    } label: {
                        Label("Delete Collection", systemImage: "trash")
                    }
                }
                #if os(iOS)
                ToolbarItem(placement: .primaryAction) { EditButton() }
                #endif
            }
        }
        .confirmationDialog(shared ? LocalizedStringKey("Delete this collection for every profile?") : LocalizedStringKey("Delete this collection?"),
                            isPresented: $showingDelete,
                            titleVisibility: .visible) {
            Button("Delete Collection", role: .destructive) {
                model.deleteList(listID)
                dismiss()
            }
        } message: {
            if shared {
                Text("Deleting it takes it away from every profile.")
            } else {
                Text("The movies, series and channels stay in your playlist.")
            }
        }
        .confirmationDialog("Stop sharing this collection?", isPresented: $showingUnshare, titleVisibility: .visible) {
            Button("Stop Sharing") { model.setListShared(listID, false) }
        } message: {
            Text("The other profiles will no longer see “\(list?.name ?? "")”. It goes back to your collections.")
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

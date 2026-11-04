import SwiftUI
import IPTVCore

/// Settings → Row order: the rows of Home, Movies, then Series, in the viewer's order. Each row moves with the list's own
/// reordering (its handle on iPhone and iPad, a drag on the Mac) and shows or hides with its switch; Default Order puts
/// a tab's rows back as they come.
struct RowOrderView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            Section {
                Text("Drag a row to move it, and turn its switch off to hide it. With Sync on this network, the order follows on your other devices.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            home
            tab(.movie)
            tab(.series)
        }
        #if os(iOS)
        // The handles stay out: moving rows is what this page is for.
        .environment(\.editMode, .constant(.active))
        #endif
        .navigationTitle("Row Order")
        .accessibilityIdentifier("rowOrder")
    }

    /// Home's rows (below its carousel, which stays at the top).
    private var home: some View {
        let rows = model.arrangedHomeRows()
        let tab = SyncRows.home
        return Section {
            ForEach(rows, id: \.key) { row in
                Toggle(isOn: Binding(
                    get: { !row.hidden },
                    set: { shown in
                        model.setRowOrder(tab: tab, arranged: rows.map(\.key),
                                          hidden: rows.filter { $0.key == row.key ? !shown : $0.hidden }.map(\.key))
                    }
                )) {
                    homeName(row.key)
                }
                .accessibilityIdentifier("row-\(tab)-\(row.key)")
            }
            .onMove { from, to in
                var moved = rows
                moved.move(fromOffsets: from, toOffset: to)
                model.setRowOrder(tab: tab, arranged: moved.map(\.key), hidden: moved.filter(\.hidden).map(\.key))
            }
        } header: {
            HStack {
                Text("Home")
                Spacer()
                Button("Default Order") { model.resetRowOrder(tab: tab) }
                    .font(.footnote)
                    .textCase(nil)
                    .accessibilityIdentifier("rowOrderDefault-\(tab)")
            }
        }
    }

    @ViewBuilder
    private func homeName(_ key: String) -> some View {
        switch key {
        case RowOrder.homeContinueMovies: Text("Movies in Progress")
        case RowOrder.homeContinueSeries: Text("Series in Progress")
        case RowOrder.myList: Text("My List")
        case RowOrder.homeNewMovies: Text("New Movies")
        case RowOrder.homeNewSeries: Text("New Series")
        case RowOrder.trending: Text("Trending")
        case RowOrder.homeCollections: Text("Collections")
        case RowOrder.homeChannels: Text("Live TV")
        default: Text(verbatim: key)
        }
    }

    private func tab(_ type: ContentType) -> some View {
        let categories = model.categories(for: type)
        let names = Dictionary(categories.map { (RowOrder.categoryKey(id: $0.id, name: $0.name), $0.name) }, uniquingKeysWith: { first, _ in first })
        let rows = model.arrangedRows(type, categories: categories)
        return Section {
            ForEach(rows, id: \.key) { row in
                Toggle(isOn: Binding(
                    get: { !row.hidden },
                    set: { shown in
                        model.setRowOrder(type, arranged: rows.map(\.key),
                                          hidden: rows.filter { $0.key == row.key ? !shown : $0.hidden }.map(\.key))
                    }
                )) {
                    name(row.key, type: type, category: names[row.key])
                }
                .accessibilityIdentifier("row-\(AppModel.rowTab(type))-\(row.key)")
            }
            .onMove { from, to in
                var moved = rows
                moved.move(fromOffsets: from, toOffset: to)
                model.setRowOrder(type, arranged: moved.map(\.key), hidden: moved.filter(\.hidden).map(\.key))
            }
        } header: {
            HStack {
                type == .movie ? Text("Movies") : Text("Series")
                Spacer()
                Button("Default Order") { model.resetRowOrder(type) }
                    .font(.footnote)
                    .textCase(nil)
                    .accessibilityIdentifier("rowOrderDefault-\(AppModel.rowTab(type))")
            }
        }
    }

    @ViewBuilder
    private func name(_ key: String, type: ContentType, category: String?) -> some View {
        if let category {
            Text(verbatim: category)
        } else {
            switch key {
            case RowOrder.all: type == .movie ? Text("All Movies") : Text("All Series")
            case RowOrder.continueWatching: Text("Continue Watching")
            case RowOrder.myList: Text("My List")
            case RowOrder.newReleases: type == .movie ? Text("New Movies") : Text("New Series")
            case RowOrder.trending: Text("Trending")
            case RowOrder.awardWinners: Text("Award Winners")
            default: Text(verbatim: key)
            }
        }
    }
}

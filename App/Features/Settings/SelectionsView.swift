import SwiftUI
import IPTVCore

/// Settings → My Selections: the open profile's selections, each with its tags and the tabs it shows in. A selection
/// opens its page (`SelectionEditorView`) to change it; a swipe or its menu deletes it; New Selection makes one. The rows
/// themselves are placed with Settings → Row Order.
struct SelectionsView: View {
    @Environment(AppModel.self) private var model
    /// The selection being made or changed, in a sheet.
    @State private var editing: SelectionEditing?

    var body: some View {
        let selections: [SyncSelection] = model.profileSelections
        List {
            Section {
                Text("Each selection is a row of your own in Home, Movies or Series, a new pick every day. Settings → Row Order places its row.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button {
                    editing = SelectionEditing(id: nil, tab: SyncRows.home)
                } label: {
                    Label("New Selection", systemImage: "plus")
                }
                .accessibilityIdentifier("newSelection")
            }
            Section {
                if selections.isEmpty {
                    Text("No selection yet: New Selection makes one.")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("selectionsNone")
                }
                ForEach(selections, id: \.id) { (selection: SyncSelection) in
                    row(selection)
                }
            }
        }
        .navigationTitle("My Selections")
        .accessibilityIdentifier("selections")
        .sheet(item: $editing) { editing in
            NavigationStack { SelectionEditorView(editing: editing) }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 560)
                #endif
        }
    }

    /// A selection: its name, its tags, the tabs it shows in; it opens its page.
    private func row(_ selection: SyncSelection) -> some View {
        let tab: String = selection.tabs.first ?? SyncRows.home
        return Button {
            editing = SelectionEditing(id: selection.id, tab: tab)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: model.selectionName(selection))
                    .foregroundStyle(.primary)
                Text(verbatim: tagsSummary(selection))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                Text(verbatim: tabsSummary(selection))
                    .font(.footnote)
                    .foregroundStyle(.tint)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("selection-\(selection.id)")
        .swipeActions {
            Button("Delete Selection", role: .destructive) { model.deleteSelection(selection.id) }
        }
        .contextMenu {
            Button("Edit Selection") { editing = SelectionEditing(id: selection.id, tab: tab) }
            Button("Delete Selection", role: .destructive) { model.deleteSelection(selection.id) }
        }
    }

    /// Its tags by name ("Drama, Cannes, 1990s"), those of a kind this version does not know left out.
    private func tagsSummary(_ selection: SyncSelection) -> String {
        var names: [String] = []
        for tag in selection.tags where Selections.isKnown(tag) {
            names.append(model.selectionTagName(tag))
        }
        let joined: String = names.joined(separator: ", ")
        if selection.matchesAll && names.count > 1 { return String(localized: "All of: \(joined)") }
        return joined
    }

    /// The tabs it shows in ("Home · Movies").
    private func tabsSummary(_ selection: SyncSelection) -> String {
        var names: [String] = []
        if selection.tabs.contains(SyncRows.home) { names.append(String(localized: "Home")) }
        if selection.tabs.contains(SyncRows.movies) { names.append(String(localized: "Movies")) }
        if selection.tabs.contains(SyncRows.series) { names.append(String(localized: "Series")) }
        return names.joined(separator: " · ")
    }
}

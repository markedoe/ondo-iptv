import SwiftUI
import IPTVCore

/// Which selection the editor opens: `id` nil for a new one, first shown in `tab` (`home`, `movie`, `series`).
struct SelectionEditing: Identifiable, Hashable {
    let id: String?
    let tab: String
}

/// A selection (Settings → My Selections or Row Order → New Selection): its name (its tags' names until one is typed), the tabs it shows
/// in, whether a title needs any tag or all of them, then every tag the playlist offers as chips, by group: genres,
/// awards, decades and styles. Save keeps it, Delete removes one already made.
struct SelectionEditorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let editing: SelectionEditing

    @State private var name = ""
    @State private var tags: [String] = []
    @State private var all = false
    @State private var tabs: [String] = []
    @State private var loaded = false

    private static let tabs = [SyncRows.home, SyncRows.movies, SyncRows.series]

    var body: some View {
        // An earlier version's genre tags as this version's: saving keeps them so.
        let savedTags: [String] = editing.id.flatMap { model.selection($0) }?.tags ?? []
        let saved: [String] = Genres.canonicalTags(savedTags)
        let groups: [(LocalizedStringKey, [String])] = model.selectionTagGroups(keeping: saved).map { (group: (kind: String, tags: [String])) -> (LocalizedStringKey, [String]) in
            (Self.groupTitle(group.kind), group.tags)
        }
        Form {
            Section {
                Text("Choose tags: your selection shows as a row of the titles that have them, a new pick every day, the best rated more often first.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextField("Name", text: $name, prompt: Text(verbatim: model.selectionName(name: "", tags: tags)))
                    .accessibilityIdentifier("selectionName")
            }
            Section("Shown In") {
                FlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(Self.tabs, id: \.self) { tab in
                        chip(tabName(tab), selected: tabs.contains(tab), identifier: "selectionTab-\(tab)") {
                            if let index = tabs.firstIndex(of: tab) { tabs.remove(at: index) } else { tabs.append(tab) }
                        }
                    }
                }
            }
            Section {
                Toggle(isOn: $all) {
                    VStack(alignment: .leading) {
                        Text("All Tags")
                        (all ? Text("A title needs every tag chosen") : Text("A title needs one of the tags chosen"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("selectionAll")
            }
            ForEach(groups, id: \.1) { group in
                Section(group.0) {
                    FlowLayout(spacing: 8, lineSpacing: 8) {
                        ForEach(group.1, id: \.self) { tag in
                            chip(model.selectionTagName(tag), selected: tags.contains(tag), identifier: "tag-\(tag)") {
                                if let index = tags.firstIndex(of: tag) { tags.remove(at: index) } else { tags.append(tag) }
                            }
                        }
                    }
                }
            }
            if groups.isEmpty {
                Section {
                    Text("No tags yet: genres come with the movies and series you open.")
                        .foregroundStyle(.secondary)
                }
            }
            if editing.id != nil {
                Section {
                    Button("Delete Selection", role: .destructive) {
                        if let id = editing.id { model.deleteSelection(id) }
                        dismiss()
                    }
                    .accessibilityIdentifier("deleteSelection")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(editing.id == nil ? Text("New Selection") : Text("Edit Selection"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    // The name typed, or none: the tags' names then, in each viewer's language.
                    model.saveSelection(id: editing.id, name: name, tags: tags, all: all, tabs: tabs)
                    dismiss()
                }
                .disabled(tags.isEmpty || tabs.isEmpty)
                .accessibilityIdentifier("saveSelection")
            }
        }
        .onAppear(perform: load)
    }

    /// The selection as it is, once.
    private func load() {
        guard !loaded else { return }
        loaded = true
        if let id = editing.id, let selection = model.selection(id) {
            name = selection.name
            tags = Genres.canonicalTags(selection.tags)
            all = selection.matchesAll
            tabs = selection.tabs
        } else {
            tabs = [editing.tab]
        }
    }

    /// A group of tags' title, by the kind of its tags.
    private static func groupTitle(_ kind: String) -> LocalizedStringKey {
        switch kind {
        case Selections.genrePrefix: return "Genres"
        case Selections.awardPrefix: return "Awards"
        case Selections.decadePrefix: return "Decades"
        default: return "Styles"
        }
    }

    private func tabName(_ tab: String) -> String {
        switch tab {
        case SyncRows.home: return String(localized: "Home")
        case SyncRows.series: return String(localized: "Series")
        default: return String(localized: "Movies")
        }
    }

    /// A tag the viewer chooses or leaves: tinted when chosen.
    private func chip(_ title: String, selected: Bool, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected { Image(systemName: "checkmark") }
                Text(verbatim: title)
            }
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill(selected ? OndoColor.selected.opacity(0.25) : Color.secondary.opacity(0.15)))
            .overlay(Capsule().strokeBorder(selected ? OndoColor.selected : Color.clear, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

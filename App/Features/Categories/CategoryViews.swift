import SwiftUI
import IPTVCore

/// Which categories are shown: by language or country tag ("|PT|"), in every playlist, then one by one.
struct CategoryManagerView: View {
    @Environment(AppModel.self) private var model
    @State private var type: ContentType
    @State private var filter = ""
    @State private var newWord = ""

    init(type: ContentType = .movie) {
        _type = State(initialValue: type)
    }

    private var categories: [MediaCategory] {
        let all = model.content.categories(for: type)
        guard !filter.isEmpty else { return all }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return all.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    var body: some View {
        let shown = categories
        List {
            if !model.categoryTags.isEmpty && filter.isEmpty {
                tagsSection
            }
            if filter.isEmpty {
                stylesSection
            }
            Section {
                Picker("Type", selection: $type) {
                    Text("Movies").tag(ContentType.movie)
                    Text("Series").tag(ContentType.series)
                    Text("Live TV").tag(ContentType.live)
                }
                .pickerStyle(.segmented)
                ForEach(shown) { category in
                    row(category)
                }
            } header: {
                Text("Categories")
            } footer: {
                if !shown.isEmpty {
                    HStack {
                        Button("Show All") { setHidden(false, shown) }
                        Spacer()
                        Button("Hide All") { setHidden(true, shown) }
                    }
                    .buttonStyle(.borderless)
                    .padding(.top, 4)
                }
            }
        }
        .overlay {
            if shown.isEmpty && !filter.isEmpty { ContentUnavailableView.search(text: filter) }
        }
        .searchable(text: $filter, prompt: Text("Filter categories"))
        .navigationTitle("Categories")
    }

    private var tagsSection: some View {
        Section {
            ForEach(model.categoryTags.sortedTags(), id: \.self) { tag in
                Toggle(isOn: Binding(
                    get: { !model.isHidden(tag: tag) },
                    set: { model.setHidden(!$0, tag: tag) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(CategoryTagName.name(of: tag).map { "\(tag) · \($0)" } ?? tag)
                        Text("\(model.categoryTags.categories[tag]?.count ?? 0) categories")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            Text("Languages and Countries")
        } footer: {
            Text("Many providers start category names with a code such as |PT| or FR:. Hiding a code hides all its categories, in every playlist, including those added later.")
        }
    }

    /// Styles found in the playlist (or hidden before), the viewer's own words, and a field to add one.
    private var stylesSection: some View {
        let styles = CategoryStyles.order.filter { model.categoryTags.styles[$0] != nil || model.isHidden(word: $0) }
        return Section {
            ForEach(styles, id: \.self) { style in
                Toggle(isOn: Binding(
                    get: { !model.isHidden(word: style) },
                    set: { model.setHidden(!$0, word: style) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(CategoryStyleName.name(style))
                        Text("\(model.categoryTags.styles[style]?.count ?? 0) categories")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            ForEach(model.hiddenCustomWords, id: \.self) { word in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: word)
                        Text("\(model.categoryCount(word: word)) categories")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Show") { model.setHidden(false, word: word) }
                        .buttonStyle(.borderless)
                }
            }
            HStack {
                TextField("Word to hide", text: $newWord)
                    .onSubmit(addWord)
                    .accessibilityIdentifier("wordToHide")
                Button("Add", action: addWord)
                    .buttonStyle(.borderless)
                    .disabled(CategoryStyles.rule(forWord: newWord) == nil)
            }
        } header: {
            Text("Styles")
        } footer: {
            Text("Hides the categories whose name holds a style or a word (Manga, Bollywood…), in every playlist, including those added later. Only category names count, not the genre of each movie.")
        }
    }

    private func addWord() {
        guard let word = CategoryStyles.rule(forWord: newWord) else { return }
        model.setHidden(true, word: word)
        newWord = ""
    }

    @ViewBuilder
    private func row(_ category: MediaCategory) -> some View {
        let hiddenTag = model.hiddenTag(of: category) ?? model.hiddenWord(of: category).map(CategoryStyleName.name)
        Toggle(isOn: Binding(
            get: { !model.isHidden(category) },
            set: { model.setHidden(!$0, category: category) }
        )) {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(category.name)
                    Spacer()
                    Text("\(model.count(in: category))").font(.caption).foregroundStyle(.secondary)
                }
                if let hiddenTag {
                    Text("Hidden with \(hiddenTag)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .disabled(hiddenTag != nil)
    }

    private func setHidden(_ hidden: Bool, _ categories: [MediaCategory]) {
        for category in categories where model.hiddenTag(of: category) == nil && model.hiddenWord(of: category) == nil {
            model.setHidden(hidden, category: category)
        }
    }
}

/// The categories screen in a sheet, with a Done button.
struct CategoryManagerSheet: View {
    let type: ContentType
    @Binding var isPresented: Bool

    var body: some View {
        NavigationStack {
            CategoryManagerView(type: type)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { isPresented = false }
                    }
                }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 520)
        #endif
    }
}

/// Toolbar button of the Movies, Series and Live TV screens: the categories screen in a sheet.
struct CategoryFilterButton: View {
    @Environment(AppModel.self) private var model
    let type: ContentType
    @State private var isPresented = false

    var body: some View {
        let hidden = model.hiddenCategoryCount(type)
        Button {
            isPresented = true
        } label: {
            Label("Categories", systemImage: hidden > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
        .help("Choose the categories shown")
        .accessibilityIdentifier("categoryFilter")
        .sheet(isPresented: $isPresented) {
            CategoryManagerSheet(type: type, isPresented: $isPresented)
        }
    }
}

/// "N categories hidden" with a way to see them again, at the end of a list of categories.
struct HiddenCategoriesNote: View {
    @Environment(AppModel.self) private var model
    let type: ContentType
    @State private var isPresented = false

    var body: some View {
        let hidden = model.hiddenCategoryCount(type)
        if hidden > 0 {
            Button {
                isPresented = true
            } label: {
                Label("\(hidden) hidden categories", systemImage: "eye.slash")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $isPresented) {
                CategoryManagerSheet(type: type, isPresented: $isPresented)
            }
        }
    }
}

/// Offered once after opening a playlist with categories for many languages and countries.
struct TagSuggestionCard: View {
    @Environment(AppModel.self) private var model
    let suggestion: TagSuggestion
    @State private var choosing = false

    var body: some View {
        let kept = ListFormatter.localizedString(byJoining: suggestion.kept.map { CategoryTagName.name(of: $0) ?? $0 })
        let count = suggestion.kept.count + suggestion.hidden.count
        VStack(alignment: .leading, spacing: 10) {
            Label("Show only your languages?", systemImage: "globe")
                .font(.headline)
            Text("This playlist has categories for \(count) languages and countries. Keep \(kept) and hide the others? You can change this at any time in Settings, Categories.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Button("Keep Only These") { model.accept(suggestion) }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("keepMyLanguages")
                Button("Choose…") { choosing = true }
                    .buttonStyle(.bordered)
                Spacer(minLength: 0)
                Button("Not Now") { model.dismissTagSuggestion() }
                    .buttonStyle(.borderless)
            }
        }
        .padding(16)
        .frame(maxWidth: 640, alignment: .leading)
        .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .sheet(isPresented: $choosing, onDismiss: { model.dismissTagSuggestion() }) {
            CategoryManagerSheet(type: .movie, isPresented: $choosing)
        }
    }
}

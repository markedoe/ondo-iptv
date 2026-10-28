import SwiftUI
import IPTVCore

/// Lets the user pick which guide channel feeds a playlist channel (or turn its guide off),
/// with suggestions and a preview of what each guide channel is airing.
struct GuideMatchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let channel: Channel

    @State private var filter = ""

    private var match: GuideMatch { model.guideMatch(for: channel) }

    private var allOptions: [EPGIndex.GuideChannelOption] {
        let options = model.epg.guideChannelOptions()
        guard !filter.isEmpty else { return options }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return options.filter { option in
            TextNormalizer.matches(normalizedText: TextNormalizer.normalize(option.name + " " + option.id), queryTokens: tokens)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    automaticRow
                    choiceRow(
                        title: Text("No guide"),
                        subtitle: Text("Hide guide information for this channel"),
                        isSelected: match == .none
                    ) {
                        select("")
                    }
                } footer: {
                    Text("Pick a guide channel when the automatic match is wrong or missing. The choice is saved with the playlist.")
                }

                let suggestions = filter.isEmpty ? model.epg.suggestions(for: channel) : []
                if !suggestions.isEmpty {
                    Section("Suggestions") {
                        ForEach(suggestions) { option in optionRow(option) }
                    }
                }

                let options = allOptions
                Section {
                    ForEach(options.prefix(300)) { option in optionRow(option) }
                } header: {
                    if filter.isEmpty {
                        Text("All guide channels (\(options.count))")
                    } else {
                        Text("Results (\(options.count))")
                    }
                }
            }
            .overlay {
                if model.epg.isEmpty {
                    ContentUnavailableView("No TV guide", systemImage: "calendar.badge.exclamationmark", description: Text("Load a guide for this playlist first."))
                }
            }
            .searchable(text: $filter, prompt: Text("Guide channel name or id"))
            .navigationTitle("Match TV Guide")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 560)
        #endif
    }

    private var automaticRow: some View {
        let automaticID: String? = {
            if case .automatic(let id) = match { return id }
            return model.epg.automaticGuideID(for: channel)
        }()
        let isSelected: Bool = {
            if case .automatic = match { return true }
            return false
        }()
        return choiceRow(
            title: Text("Automatic"),
            subtitle: automaticID.map { Text(model.epg.option(for: $0).name) } ?? Text("No match found"),
            isSelected: isSelected
        ) {
            select(nil)
        }
    }

    private func optionRow(_ option: EPGIndex.GuideChannelOption) -> some View {
        let now = model.epg.current(guideID: option.id)
        return choiceRow(
            title: Text(option.name),
            subtitle: Text(now.map { "\(option.id) · \($0.title)" } ?? option.id),
            isSelected: match == .manual(option.id),
            iconURL: option.iconURL
        ) {
            select(option.id)
        }
    }

    private func choiceRow(title: Text, subtitle: Text, isSelected: Bool, iconURL: URL? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let iconURL {
                    RemoteImage(url: iconURL, placeholderText: "", maxPixelSize: 120)
                        .frame(width: 40, height: 28)
                }
                VStack(alignment: .leading, spacing: 2) {
                    title.lineLimit(1)
                    subtitle.font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark").foregroundStyle(OndoColor.selected)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func select(_ guideID: String?) {
        Task {
            await model.setGuideMatch(guideID, for: channel)
            dismiss()
        }
    }
}

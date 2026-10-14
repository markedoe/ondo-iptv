import SwiftUI
import IPTVCore

/// Finds subtitles for what is playing on the services the user set up, and loads the chosen one.
struct SubtitleSearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let controller: PlayerController
    let item: PlaybackItem

    @State private var title = ""
    @State private var year = ""
    @State private var season = ""
    @State private var episode = ""
    @State private var firstLanguage = "fr"
    @State private var secondLanguage = ""
    @State private var outcome: SubtitleSearch.Outcome?
    @State private var isSearching = false
    @State private var downloading: OnlineSubtitle.ID?
    @State private var message: String?

    private var languageCodes: [String] { SubtitleLanguages.codes }

    private var query: SubtitleQuery {
        SubtitleQuery(
            title: title.trimmingCharacters(in: .whitespaces),
            year: Int(year),
            season: Int(season),
            episode: Int(episode),
            languages: [firstLanguage, secondLanguage].filter { !$0.isEmpty }.reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Title", text: $title)
                    if item.reference.type == .series {
                        HStack {
                            TextField("Season", text: $season)
                            TextField("Episode", text: $episode)
                        }
                    } else {
                        TextField("Year", text: $year)
                    }
                    Picker("Language", selection: $firstLanguage) {
                        ForEach(languageCodes, id: \.self) { Text(Self.languageName($0)).tag($0) }
                    }
                    Picker("Second Language", selection: $secondLanguage) {
                        Text("None").tag("")
                        ForEach(languageCodes, id: \.self) { Text(Self.languageName($0)).tag($0) }
                    }
                    Button {
                        Task { await search() }
                    } label: {
                        HStack {
                            Label("Search", systemImage: "magnifyingglass")
                            if isSearching {
                                Spacer()
                                ProgressView().controlSize(.small)
                            }
                        }
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || isSearching)
                } footer: {
                    let services = model.subtitleServices
                    if services.isEmpty {
                        Text("No subtitle service is set up. Add a key in Settings > Subtitles.")
                    } else {
                        Text("Searched on: \(services.map(\.displayName).formatted(.list(type: .and))). More services in Settings > Subtitles.")
                    }
                }

                if let message {
                    Section { Text(message).foregroundStyle(.red) }
                }

                if let outcome {
                    ForEach(outcome.failures.keys.sorted { $0.rawValue < $1.rawValue }, id: \.self) { service in
                        Label(outcome.failures[service] ?? "", systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                    if outcome.results.isEmpty {
                        Text("No subtitles found. Check the title, or try another language.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(outcome.results) { subtitle in
                        Button {
                            Task { await load(subtitle) }
                        } label: {
                            row(subtitle)
                        }
                        .buttonStyle(.plain)
                        .disabled(downloading != nil)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Find Subtitles")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .onAppear(perform: fill)
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 560)
        #endif
    }

    private func row(_ subtitle: OnlineSubtitle) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(subtitle.language.uppercased())
                .font(.caption.weight(.bold).monospaced())
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 5))
            VStack(alignment: .leading, spacing: 3) {
                Text(subtitle.release.isEmpty ? subtitle.service.displayName : subtitle.release)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    Text(subtitle.service.displayName)
                    if let downloads = subtitle.downloads {
                        Label("\(downloads)", systemImage: "arrow.down.circle")
                    }
                    if subtitle.hearingImpaired {
                        Image(systemName: "ear")
                            .accessibilityLabel("For the deaf and hard of hearing")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if downloading == subtitle.id {
                ProgressView().controlSize(.small)
            }
        }
        .contentShape(Rectangle())
    }

    private func fill() {
        let initial = model.subtitleQuery(for: item)
        title = initial.title
        year = initial.year.map(String.init) ?? ""
        season = initial.season.map(String.init) ?? ""
        episode = initial.episode.map(String.init) ?? ""
        firstLanguage = initial.languages.first ?? "en"
        secondLanguage = initial.languages.dropFirst().first ?? ""
    }

    private func search() async {
        isSearching = true
        message = nil
        let query = self.query
        model.rememberSubtitleLanguages(query.languages)
        outcome = await model.subtitleSearch().search(query)
        isSearching = false
    }

    private func load(_ subtitle: OnlineSubtitle) async {
        downloading = subtitle.id
        defer { downloading = nil }
        do {
            let file = try await model.subtitleSearch().download(subtitle, for: query)
            if controller.loadExternalSubtitles(data: file.data, name: file.fileName) {
                dismiss()
            } else {
                message = String(localized: "This file holds no subtitles that can be shown.")
            }
        } catch {
            message = error.localizedDescription
        }
    }

    static func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code)?.localizedCapitalized ?? code
    }
}

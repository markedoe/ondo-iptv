import SwiftUI
import IPTVCore

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var results = SearchResults()
    @State private var programs: [ProgramSearchResult] = []
    @State private var scope: SearchScope = .all
    @State private var selectedProgram: ProgramSearchResult?

    enum SearchScope: String, CaseIterable, Identifiable {
        case all, live, guide, movies, series
        var id: String { rawValue }
        var label: LocalizedStringKey {
            switch self {
            case .all: return "All"
            case .live: return "Live TV"
            case .guide: return "Guide"
            case .movies: return "Movies"
            case .series: return "Series"
            }
        }
    }

    var body: some View {
        List {
            if (scope == .all || scope == .live) && !results.channels.isEmpty {
                Section("Channels (\(results.channels.count))") {
                    ForEach(results.channels.prefix(scope == .all ? 30 : 150)) { channel in
                        Button {
                            model.play(channel, in: results.channels)
                        } label: {
                            ChannelRow(channel: channel)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            if (scope == .all || scope == .guide) && !programs.isEmpty {
                Section("TV Guide (\(programs.count))") {
                    ForEach(programs.prefix(scope == .all ? 15 : 100)) { result in
                        Button {
                            selectedProgram = result
                        } label: {
                            ProgramSearchRow(result: result)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            if (scope == .all || scope == .movies) && !results.movies.isEmpty {
                Section("Movies (\(results.movies.count))") {
                    ForEach(results.movies.prefix(scope == .all ? 30 : 150)) { movie in
                        NavigationLink(value: movie) {
                            SearchResultRow(title: movie.name, subtitle: movie.year.map(String.init), imageURL: movie.posterURL, icon: "film")
                        }
                    }
                }
            }
            if (scope == .all || scope == .series) && !results.series.isEmpty {
                Section("Series (\(results.series.count))") {
                    ForEach(results.series.prefix(scope == .all ? 30 : 150)) { series in
                        NavigationLink(value: series) {
                            SearchResultRow(title: series.name, subtitle: series.year.map(String.init), imageURL: series.coverURL, icon: "play.rectangle.on.rectangle")
                        }
                    }
                }
            }
        }
        .overlay {
            if query.isEmpty {
                ContentUnavailableView("Search", systemImage: "magnifyingglass", description: Text("Find channels, TV programmes, movies and series in the current playlist."))
            } else if results.isEmpty && programs.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .searchable(text: $query, prompt: Text("Channels, programmes, movies, series"))
        .searchScopes($scope) {
            ForEach(SearchScope.allCases) { scope in
                Text(scope.label).tag(scope)
            }
        }
        .task(id: query) {
            // Debounce typing.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            results = model.search(query)
            programs = query.count >= 2 ? model.searchPrograms(query) : []
        }
        .sheet(item: $selectedProgram) { result in
            ProgramDetailView(program: result.program, channel: result.channel)
        }
        .navigationTitle("Search")
    }
}

/// A programme found in the guide: when, where, and whether it is on now, replayable or reminded.
struct ProgramSearchRow: View {
    @Environment(AppModel.self) private var model
    let result: ProgramSearchResult

    var body: some View {
        let now = Date()
        let program = result.program
        HStack(spacing: 12) {
            RemoteImage(url: model.logoURL(for: result.channel), placeholderText: result.channel.name, maxPixelSize: 120)
                .frame(width: 44, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(program.title).lineLimit(1)
                Text("\(result.channel.name) · \(Formatting.dayAndTime(program.start))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if program.isLive(at: now) {
                Text("LIVE").font(.caption2.bold()).foregroundStyle(.red)
            } else if program.start < now {
                Image(systemName: "clock.arrow.circlepath").foregroundStyle(Color.accentColor)
            } else if model.hasReminder(for: program, on: result.channel) {
                Image(systemName: "bell.fill").foregroundStyle(Color.accentColor)
            }
        }
        .contentShape(Rectangle())
    }
}

struct SearchResultRow: View {
    let title: String
    let subtitle: String?
    let imageURL: URL?
    let icon: String

    var body: some View {
        HStack(spacing: 12) {
            Color.secondary.opacity(0.12)
                .frame(width: 40, height: 60)
                .overlay { RemoteImage(url: imageURL, placeholderText: title, contentMode: .fill, maxPixelSize: 120) }
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(2)
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Image(systemName: icon).foregroundStyle(.secondary)
        }
    }
}

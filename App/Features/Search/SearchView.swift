import SwiftUI
import IPTVCore

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var results = SearchResults()
    @State private var programs: [ProgramSearchResult] = []
    /// Actors and directors matching the search (see `AppModel.people(matching:)`).
    @State private var people: [String] = []
    @State private var scope: SearchScope = .all
    @State private var selectedProgram: ProgramSearchResult?
    @State private var fromYear: Int?
    @State private var toYear: Int?
    @State private var releaseYears: [Int] = []
    @State private var awardGroup: String?
    @State private var winnersOnly = false
    @State private var awardGroups: [String] = []

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

    /// The release years asked for, or nil when the search is not filtered. One bound alone keeps the other open.
    private var years: ClosedRange<Int>? {
        guard fromYear != nil || toYear != nil else { return nil }
        let lower = fromYear ?? Int.min
        let upper = toYear ?? Int.max
        return min(lower, upper)...max(lower, upper)
    }

    private var isFiltered: Bool { years != nil || awardGroup != nil }

    private struct Request: Equatable {
        let query: String
        let years: ClosedRange<Int>?
        let awardGroup: String?
        let winnersOnly: Bool
    }

    var body: some View {
        List {
            if isFiltered {
                filtersRow
            }
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
            if scope != .live && scope != .guide && !people.isEmpty {
                Section("People (\(people.count))") {
                    ForEach(people, id: \.self) { name in
                        NavigationLink(value: PersonRoute(name: name)) {
                            Label(name, systemImage: "person.crop.circle")
                        }
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
            if query.isEmpty && !isFiltered {
                ContentUnavailableView("Search", systemImage: "magnifyingglass", description: Text("Find channels, TV programmes, movies, series, actors and directors in the current playlist."))
            } else if results.isEmpty && programs.isEmpty && people.isEmpty {
                if query.isEmpty {
                    ContentUnavailableView("No movie or series", systemImage: "line.3.horizontal.decrease.circle", description: Text("Nothing in this playlist matches these filters."))
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
        .searchable(text: $query, prompt: Text("Channels, programmes, movies, series, people"))
        .searchScopes($scope) {
            ForEach(SearchScope.allCases) { scope in
                Text(scope.label).tag(scope)
            }
        }
        .task(id: Request(query: query, years: years, awardGroup: awardGroup, winnersOnly: winnersOnly)) {
            // Debounce typing.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            if isFiltered {
                // Release years and awards only exist for movies and series: channels and programmes are left out.
                results = model.search(query, releasedIn: years, awardGroup: awardGroup, winnersOnly: winnersOnly)
                programs = []
                people = []
            } else {
                results = model.search(query)
                programs = query.count >= 2 ? model.searchPrograms(query) : []
                people = model.people(matching: query)
                // With a TMDb token, the people TMDb knows by that name follow.
                let online = await model.tmdbPeople(matching: query)
                guard !Task.isCancelled, !online.isEmpty else { return }
                var seen = Set(people.map(TextNormalizer.normalize))
                people += online.filter { seen.insert(TextNormalizer.normalize($0)).inserted }
            }
        }
        .task(id: [model.content.movies.count, model.content.series.count, model.movieAwards.count]) {
            releaseYears = model.releaseYears()
            awardGroups = model.awardGroups()
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) { yearsMenu }
        }
        .sheet(item: $selectedProgram) { result in
            ProgramDetailView(program: result.program, channel: result.channel)
        }
        .navigationTitle("Search")
    }

    /// From and To pickers over the playlist's release years.
    private var yearsMenu: some View {
        Menu {
            Picker("From", selection: $fromYear) {
                Text("Any Year").tag(Int?.none)
                ForEach(releaseYears.reversed(), id: \.self) { year in
                    Text(verbatim: String(year)).tag(Int?.some(year))
                }
            }
            .pickerStyle(.menu)
            Picker("To", selection: $toYear) {
                Text("Any Year").tag(Int?.none)
                ForEach(releaseYears, id: \.self) { year in
                    Text(verbatim: String(year)).tag(Int?.some(year))
                }
            }
            .pickerStyle(.menu)
            if !awardGroups.isEmpty {
                Picker("Awards", selection: $awardGroup) {
                    Text("Any Movie").tag(String?.none)
                    ForEach(awardGroups, id: \.self) { group in
                        Text(verbatim: AwardGroup.name(group)).tag(String?.some(group))
                    }
                }
                .pickerStyle(.menu)
                Toggle("Winners Only", isOn: $winnersOnly)
                    .disabled(awardGroup == nil)
            }
            if isFiltered {
                Button("Clear Filters", systemImage: "xmark.circle", role: .destructive) { clearFilters() }
            }
        } label: {
            Label("Filters", systemImage: isFiltered ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
        .accessibilityIdentifier("releaseYears")
    }

    private var filtersRow: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                if let years {
                    Label(yearsText(years), systemImage: "calendar")
                }
                if let awardGroup {
                    Label(winnersOnly ? String(localized: "Winners: \(AwardGroup.name(awardGroup))") : AwardGroup.name(awardGroup),
                          systemImage: "trophy")
                }
            }
            .font(.subheadline)
            Spacer()
            Button("Clear Filters", systemImage: "xmark.circle.fill") { clearFilters() }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
        }
    }

    private func yearsText(_ years: ClosedRange<Int>) -> String {
        switch (fromYear, toYear) {
        case (let from?, nil): return String(localized: "Released in or after \(String(from))")
        case (nil, let to?): return String(localized: "Released in or before \(String(to))")
        default:
            let lower = String(years.lowerBound)
            let upper = String(years.upperBound)
            return lower == upper ? String(localized: "Released in \(lower)") : String(localized: "Released from \(lower) to \(upper)")
        }
    }

    private func clearFilters() {
        fromYear = nil
        toYear = nil
        awardGroup = nil
        winnersOnly = false
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

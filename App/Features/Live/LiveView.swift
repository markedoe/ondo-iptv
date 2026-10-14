import SwiftUI
import IPTVCore

struct LiveView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        CategoryBrowser(
            type: .live,
            pseudoCategories: [PseudoCategory.all(.live), PseudoCategory.favorites(.live), PseudoCategory.recent(.live)]
        ) { category in
            ChannelListView(category: category)
        }
        .navigationTitle("Live TV")
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: AppSection.guide) {
                    Label("TV Guide", systemImage: "calendar.day.timeline.left")
                }
            }
            #endif
            ToolbarItem(placement: .primaryAction) {
                EPGStatusButton()
            }
            ToolbarItem(placement: .primaryAction) {
                CategoryFilterButton(type: .live)
            }
        }
    }
}

/// Shows guide loading progress / errors and lets the user reload it.
struct EPGStatusButton: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        switch model.epgPhase {
        case .loading:
            ProgressView().controlSize(.small)
        case .failed(let message, _):
            Button {
                model.loadEPG(force: true)
            } label: {
                Label("Guide unavailable", systemImage: "exclamationmark.arrow.circlepath")
            }
            .help(message)
        default:
            Button {
                model.loadEPG(force: true)
            } label: {
                Label("Refresh Guide", systemImage: "arrow.clockwise")
            }
        }
    }
}

struct ChannelListView: View {
    @Environment(AppModel.self) private var model
    let category: MediaCategory
    @Environment(NavigationRouter.self) private var router: NavigationRouter?
    @State private var filter = ""
    @State private var matchingChannel: Channel?
    @State private var editingChannel: Channel?

    private var channels: [Channel] {
        let base: [Channel]
        switch category.id {
        case PseudoCategory.all: base = model.channels(in: nil)
        case PseudoCategory.favorites: base = model.favoriteChannels()
        case PseudoCategory.recent: base = model.recentChannels()
        default: base = model.channels(in: category)
        }
        guard !filter.isEmpty else { return base }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return base.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    var body: some View {
        let list = channels
        TimelineView(.periodic(from: .now, by: 60)) { context in
            List {
                ForEach(list) { channel in
                    Button {
                        model.play(channel, in: list)
                    } label: {
                        ChannelRow(channel: channel, date: context.date)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        ChannelContextMenu(
                            channel: channel,
                            showGuide: { router?.push(channel) },
                            matchGuide: { matchingChannel = channel },
                            edit: { editingChannel = channel }
                        )
                    }
                    #if os(iOS)
                    .swipeActions(edge: .trailing) {
                        Button {
                            model.toggleFavorite(channel)
                        } label: {
                            MyListLabel(inList: model.isFavorite(.live, id: channel.id))
                        }
                        .tint(.accentColor)
                        Button {
                            router?.push(channel)
                        } label: {
                            Label("Guide", systemImage: "calendar")
                        }
                        .tint(.indigo)
                    }
                    #endif
                }
            }
            .listStyle(.plain)
        }
        .overlay {
            if list.isEmpty {
                if category.id == PseudoCategory.favorites {
                    #if os(macOS)
                    ContentUnavailableView("No channels in My List", systemImage: "list.star", description: Text("Control-click a channel and choose “Add to My List”."))
                    #else
                    ContentUnavailableView("No channels in My List", systemImage: "list.star", description: Text("Long-press a channel and choose “Add to My List”."))
                    #endif
                } else if !filter.isEmpty {
                    ContentUnavailableView.search(text: filter)
                } else {
                    ContentUnavailableView("No channels", systemImage: "tv")
                }
            }
        }
        .searchable(text: $filter, prompt: Text("Search channels"))
        .navigationTitle(category.name)
        .sheet(item: $matchingChannel) { channel in
            GuideMatchView(channel: channel)
        }
        .sheet(item: $editingChannel) { channel in
            ChannelEditView(channel: channel)
        }
    }
}

struct ChannelContextMenu: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    var showGuide: () -> Void
    var matchGuide: (() -> Void)?
    var edit: (() -> Void)?

    var body: some View {
        Button {
            model.toggleFavorite(channel)
        } label: {
            MyListLabel(inList: model.isFavorite(.live, id: channel.id))
        }
        AddToListMenu(item: .channel(channel))
        Button(action: showGuide) {
            Label(channel.hasCatchup ? "Guide & Replay" : "TV Guide", systemImage: "calendar")
        }
        if let matchGuide {
            Button(action: matchGuide) {
                Label("Match TV Guide…", systemImage: "link")
            }
        }
        if AppModel.supportsMultiview {
            Button {
                model.addToMultiview(channel)
            } label: {
                Label("Add to Multiview", systemImage: "rectangle.split.2x2")
            }
        }
        if let edit {
            Button(action: edit) {
                Label("Rename or Renumber…", systemImage: "pencil")
            }
        }
        Button {
            model.setHidden(true, channel: channel)
        } label: {
            Label("Hide Channel", systemImage: "eye.slash")
        }
        // The address carries the provider login: not under parental control (it could re-add the playlist).
        if !model.isParentalControlActive {
            Button {
                Pasteboard.copy(channel.streamURL.absoluteString)
            } label: {
                Label("Copy Stream URL", systemImage: "doc.on.doc")
            }
        }
    }
}

/// Programme list of one channel by day, with replay of past programmes when available.
struct ChannelGuideView: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    @State private var programs: [EPGProgram] = []
    @State private var isLoading = true
    @State private var selectedDay = Calendar.current.startOfDay(for: Date())
    @State private var selectedProgram: EPGProgram?
    @State private var showingMatch = false

    private var days: [Date] {
        let calendar = Calendar.current
        let set = Set(programs.map { calendar.startOfDay(for: $0.start) })
        return set.sorted()
    }

    private var programsOfDay: [EPGProgram] {
        programs.filter { Calendar.current.isDate($0.start, inSameDayAs: selectedDay) }
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    RemoteImage(url: model.logoURL(for: channel), placeholderText: channel.name)
                        .frame(width: 80, height: 54)
                    VStack(alignment: .leading) {
                        Text(channel.name).font(.title3.bold())
                        if let catchup = channel.catchup, catchup.days > 0 {
                            Label("Replay: \(catchup.days) days", systemImage: "clock.arrow.circlepath")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Button {
                        model.play(channel)
                    } label: {
                        Label("Watch", systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            if days.count > 1 {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(days, id: \.self) { day in
                                Button {
                                    selectedDay = day
                                } label: {
                                    Text(dayLabel(day))
                                        .font(.subheadline.weight(.medium))
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(day == selectedDay ? Color.accentColor : Color.secondary.opacity(0.15), in: Capsule())
                                        .foregroundStyle(day == selectedDay ? Color.white : Color.primary)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }

            Section {
                if isLoading && programs.isEmpty {
                    ProgressView()
                } else if programsOfDay.isEmpty {
                    Text("No guide information for this channel.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(programsOfDay) { program in
                        Button {
                            selectedProgram = program
                        } label: {
                            ProgramRow(program: program, channel: channel)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .navigationTitle(channel.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showingMatch = true
                } label: {
                    Label("Match TV Guide…", systemImage: "link")
                }
            }
        }
        .sheet(isPresented: $showingMatch, onDismiss: reload) {
            GuideMatchView(channel: channel)
        }
        .task {
            programs = await model.archivePrograms(for: channel)
            isLoading = false
            if !days.contains(selectedDay), let today = days.last(where: { $0 <= Date() }) ?? days.first {
                selectedDay = today
            }
        }
        .sheet(item: $selectedProgram) { program in
            ProgramDetailView(program: program, channel: channel)
        }
    }

    private func reload() {
        Task { programs = await model.archivePrograms(for: channel) }
    }

    private func dayLabel(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return String(localized: "Today") }
        if calendar.isDateInYesterday(day) { return String(localized: "Yesterday") }
        if calendar.isDateInTomorrow(day) { return String(localized: "Tomorrow") }
        return day.formatted(.dateTime.weekday(.abbreviated).day())
    }
}

struct ProgramRow: View {
    @Environment(AppModel.self) private var model
    let program: EPGProgram
    let channel: Channel

    var body: some View {
        let now = Date()
        HStack(alignment: .top, spacing: 12) {
            Text(Formatting.time(program.start))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(program.isLive(at: now) ? Color.accentColor : .secondary)
                .frame(width: 56, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                Text(program.title)
                    .font(.body.weight(program.isLive(at: now) ? .semibold : .regular))
                if let summary = program.summary {
                    Text(summary).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                if program.isLive(at: now) {
                    ProgressView(value: program.progress(at: now)).frame(maxWidth: 200)
                }
            }
            Spacer()
            if program.isLive(at: now) {
                Text("LIVE").font(.caption2.bold()).foregroundStyle(.red)
            } else if model.canReplay(program, on: channel) {
                Image(systemName: "play.circle").foregroundStyle(Color.accentColor)
            } else if model.hasReminder(for: program, on: channel) {
                Image(systemName: "bell.fill").foregroundStyle(Color.accentColor)
            }
        }
        .opacity(program.isPast(at: now) && !model.canReplay(program, on: channel) ? 0.6 : 1)
        .contentShape(Rectangle())
    }
}

struct ProgramDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let program: EPGProgram
    let channel: Channel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let icon = program.iconURL {
                        RemoteImage(url: icon, placeholderText: program.title, contentMode: .fill, maxPixelSize: 800)
                            .frame(maxWidth: .infinity)
                            .frame(height: 200)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    Text(program.title).font(.title2.bold())
                    if let subtitle = program.subtitle {
                        Text(subtitle).font(.headline).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 12) {
                        Label(channel.name, systemImage: "tv")
                        Label(Formatting.dayAndTime(program.start) + " – " + Formatting.time(program.end), systemImage: "clock")
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        if let category = program.category { Tag(text: category) }
                        if let episode = program.episode { Tag(text: episode) }
                        if let rating = program.rating { Tag(text: rating) }
                    }
                    if let summary = program.summary {
                        Text(summary).font(.body)
                    }
                    actions
                }
                .padding()
            }
            .navigationTitle(channel.name)
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 420)
        #endif
    }

    @ViewBuilder
    private var actions: some View {
        let now = Date()
        HStack {
            if program.isLive(at: now) {
                Button {
                    dismiss()
                    model.play(channel)
                } label: {
                    Label("Watch Live", systemImage: "dot.radiowaves.left.and.right")
                }
                .buttonStyle(.borderedProminent)
                if model.canReplay(program, on: channel) {
                    Button {
                        dismiss()
                        model.playCatchup(program, on: channel)
                    } label: {
                        Label("Watch from Start", systemImage: "backward.end.fill")
                    }
                    .buttonStyle(.bordered)
                }
            } else if model.canReplay(program, on: channel) {
                Button {
                    dismiss()
                    model.playCatchup(program, on: channel)
                } label: {
                    Label("Play Replay", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
            } else if program.start > now {
                ReminderButton(program: program, channel: channel)
                    .buttonStyle(.borderedProminent)
                Label("Starts \(Formatting.relative(program.start))", systemImage: "calendar.badge.clock")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Label("Replay not available", systemImage: "clock.badge.xmark")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.top, 8)
    }
}

struct Tag: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.secondary.opacity(0.15), in: Capsule())
    }
}

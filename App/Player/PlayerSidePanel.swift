import SwiftUI
import IPTVCore

enum PlayerPanelTab: Hashable {
    case channels
    case guide
}

/// Side panel of the player: the zapping list (searchable by name or number) and the guide of the
/// channel being watched, where the current programme can be restarted and past ones replayed.
struct PlayerSidePanel: View {
    @Environment(AppModel.self) private var model
    let session: PlaybackSession
    @Binding var tab: PlayerPanelTab
    var onClose: () -> Void

    @State private var filter = ""

    private var channel: Channel? {
        guard let item = session.current, item.isLive else { return nil }
        return model.index.channelsByID[item.reference.itemID]
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                if channel != nil {
                    Picker("Panel", selection: $tab) {
                        Text("Channels").tag(PlayerPanelTab.channels)
                        Text("Guide").tag(PlayerPanelTab.guide)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                } else {
                    Text("Channels").font(.headline)
                    Spacer()
                }
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .padding(6)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
                .accessibilityIdentifier("panelClose")
            }
            .padding()

            switch tab {
            case .channels:
                channelList
            case .guide:
                if let channel {
                    PlayerGuideList(session: session, channel: channel)
                }
            }
        }
        .foregroundStyle(.white)
        .background(.ultraThinMaterial)
        .environment(\.colorScheme, .dark)
    }

    // MARK: - Channels

    private var numbers: [Int?] {
        session.queue.map { model.index.channelsByID[$0.reference.itemID]?.number }
    }

    private struct Entry: Identifiable {
        let position: Int
        let item: PlaybackItem
        let number: Int?
        var id: String { item.id }
    }

    private var entries: [Entry] {
        let numbers = ChannelNumbering.displayNumbers(numbers)
        let all = session.queue.enumerated().map { position, item in
            Entry(position: position, item: item, number: numbers[position])
        }
        let query = filter.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return all }
        if query.allSatisfy(\.isNumber) {
            return all.filter { entry in entry.number.map { number in String(number).hasPrefix(query) } ?? false }
        }
        let tokens = TextNormalizer.normalize(query).split(separator: " ")
        return all.filter { entry in TextNormalizer.matches(normalizedText: TextNormalizer.normalize(entry.item.title), queryTokens: tokens) }
    }

    private var channelList: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Name or number", text: $filter)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                    .onSubmit(openFirstMatch)
                if !filter.isEmpty {
                    Button { filter = "" } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(8)
            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal)
            .padding(.bottom, 8)

            ScrollViewReader { proxy in
                List {
                    ForEach(entries) { entry in
                        Button {
                            session.select(index: entry.position)
                        } label: {
                            row(entry.item, number: entry.number)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(entry.position == session.index ? Color.accentColor.opacity(0.35) : Color.clear)
                        .id(entry.position)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .onAppear { proxy.scrollTo(session.index, anchor: .center) }
            }
        }
    }

    private func openFirstMatch() {
        if let first = entries.first { session.select(index: first.position) }
    }

    private func row(_ item: PlaybackItem, number: Int?) -> some View {
        let channel = model.index.channelsByID[item.reference.itemID]
        let program = channel.flatMap { model.epg.current(for: $0) }
        return HStack(spacing: 10) {
            Text(number.map(String.init) ?? "")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 34, alignment: .trailing)
            RemoteImage(url: channel.flatMap { model.logoURL(for: $0) } ?? item.artworkURL, placeholderText: item.title)
                .frame(width: 44, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.subheadline).lineLimit(1)
                if let program {
                    Text(program.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    ProgressView(value: program.progress())
                        .tint(.accentColor)
                        .scaleEffect(x: 1, y: 0.6, anchor: .center)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}

/// Programmes of the watched channel around now: restart the one airing, replay past ones.
struct PlayerGuideList: View {
    @Environment(AppModel.self) private var model
    let session: PlaybackSession
    let channel: Channel
    @State private var showingMatch = false

    /// How far back the list goes: the archive when the channel has one (two days at most), a few hours otherwise.
    private var lookBack: TimeInterval {
        channel.catchup.map { min(Double($0.days), 2) * 86_400 } ?? 3 * 3600
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let now = context.date
            let programs = model.epg.programs(for: channel, in: DateInterval(start: now.addingTimeInterval(-lookBack), end: now.addingTimeInterval(24 * 3600)))
            if programs.isEmpty {
                ContentUnavailableView {
                    Label("No guide information", systemImage: "calendar.badge.exclamationmark")
                } actions: {
                    if !model.epg.isEmpty {
                        Button("Match TV Guide…") { showingMatch = true }
                            .buttonStyle(.bordered)
                    }
                }
            } else {
                ScrollViewReader { proxy in
                    List(programs) { program in
                        row(program, now: now)
                            .listRowBackground(isPlaying(program) ? Color.accentColor.opacity(0.35) : Color.clear)
                            .id(program.id)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .onAppear {
                        let target = session.timeshift.flatMap { shift in programs.last { $0.start <= shift.start } }
                            ?? programs.first { $0.end > now }
                        if let target { proxy.scrollTo(target.id, anchor: .center) }
                    }
                }
            }
        }
        .sheet(isPresented: $showingMatch) {
            GuideMatchView(channel: channel)
                .environment(\.colorScheme, .dark)
        }
    }

    private func isPlaying(_ program: EPGProgram) -> Bool {
        guard let shift = session.timeshift else { return program.start <= Date() && program.end > Date() }
        return program.start <= shift.start && program.end > shift.start
    }

    @ViewBuilder
    private func row(_ program: EPGProgram, now: Date) -> some View {
        let airing = program.start <= now && program.end > now
        let upcoming = program.start > now
        let replayable = program.start < now && session.canTimeshift(from: program.start)
        let hasReminder = upcoming && model.hasReminder(for: program, on: channel)
        Button {
            if upcoming {
                Task { await model.toggleReminder(for: program, on: channel) }
            } else {
                session.play(program)
            }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Text(Formatting.time(program.start))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(width: 66, alignment: .leading)
                VStack(alignment: .leading, spacing: 3) {
                    Text(program.title)
                        .font(.subheadline.weight(airing ? .semibold : .regular))
                        .lineLimit(2)
                    if airing {
                        ProgressView(value: program.progress(at: now))
                            .tint(.red)
                    } else if let subtitle = program.subtitle ?? program.category {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if upcoming {
                    Image(systemName: hasReminder ? "bell.fill" : "bell")
                        .font(.callout)
                        .foregroundStyle(hasReminder ? Color.accentColor : .secondary)
                        .accessibilityLabel(hasReminder ? Text("Reminder Set") : Text("Remind Me"))
                } else if replayable {
                    Image(systemName: airing ? "backward.end.circle" : "play.circle")
                        .font(.title3)
                        .foregroundStyle(Color.accentColor)
                        .accessibilityLabel(airing ? Text("Restart") : Text("Replay"))
                } else if airing {
                    Text("LIVE")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(.red, in: RoundedRectangle(cornerRadius: 4))
                }
            }
            .contentShape(Rectangle())
            .opacity(upcoming && !hasReminder ? 0.7 : 1)
        }
        .buttonStyle(.plain)
        .disabled(!replayable && !upcoming)
    }
}

import SwiftUI
import IPTVCore

/// Classic EPG grid: channels vertically, time horizontally, a red line for "now".
struct GuideView: View {
    @Environment(AppModel.self) private var model
    @State private var categoryID: String?
    @State private var selected: ProgramSearchResult?

    private let hourWidth: CGFloat = Platform.isMac ? 300 : 240
    private let rowHeight: CGFloat = 58
    private let channelColumnWidth: CGFloat = Platform.isMac ? 170 : 110

    private var categories: [MediaCategory] {
        model.categories(for: .live).filter { !model.isLocked($0) }
    }

    /// Favorites when there are some, otherwise every channel.
    private var selectedCategoryID: String {
        categoryID ?? (model.favoriteChannels().isEmpty ? PseudoCategory.all : PseudoCategory.favorites)
    }

    private var channels: [Channel] {
        switch selectedCategoryID {
        case PseudoCategory.favorites:
            return model.favoriteChannels()
        case PseudoCategory.all:
            return Array(model.channels(in: nil).prefix(300))
        default:
            let category = categories.first { $0.id == selectedCategoryID }
            return Array(model.channels(in: category).prefix(300))
        }
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let window = Self.window(around: context.date)
            let list = channels
            ScrollView(.vertical) {
                HStack(alignment: .top, spacing: 0) {
                    channelColumn(list)
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal) {
                            VStack(alignment: .leading, spacing: 0) {
                                timeHeader(window)
                                ForEach(list) { channel in
                                    programRow(channel, window: window, now: context.date)
                                }
                            }
                            .overlay(alignment: .topLeading) {
                                nowLine(window: window, now: context.date, height: CGFloat(list.count) * rowHeight + 32)
                            }
                        }
                        .onAppear { proxy.scrollTo("now", anchor: .leading) }
                    }
                }
            }
            .overlay {
                if list.isEmpty {
                    ContentUnavailableView("No channels", systemImage: "calendar")
                } else if model.epg.isEmpty && !model.epgPhase.isLoading {
                    ContentUnavailableView {
                        Label("No TV guide", systemImage: "calendar.badge.exclamationmark")
                    } description: {
                        Text("Your provider does not publish a guide, or it could not be loaded. You can add an XMLTV address in the playlist settings.")
                    } actions: {
                        Button("Reload Guide") { model.loadEPG(force: true) }
                    }
                }
            }
        }
        .navigationTitle("TV Guide")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Picker("Category", selection: Binding(
                    get: { selectedCategoryID },
                    set: { categoryID = $0 }
                )) {
                    Text("My List").tag(PseudoCategory.favorites)
                    Text("All Channels").tag(PseudoCategory.all)
                    ForEach(categories) { category in
                        Text(category.name).tag(category.id)
                    }
                }
                .pickerStyle(.menu)
            }
            ToolbarItem(placement: .primaryAction) { EPGStatusButton() }
        }
        .sheet(item: $selected) { selection in
            ProgramDetailView(program: selection.program, channel: selection.channel)
        }
    }

    /// Two hours back, twelve hours ahead, aligned on the half hour.
    static func window(around date: Date) -> DateInterval {
        let calendar = Calendar.current
        var components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date.addingTimeInterval(-2 * 3600))
        components.minute = (components.minute ?? 0) < 30 ? 0 : 30
        let start = calendar.date(from: components) ?? date
        return DateInterval(start: start, duration: 14 * 3600)
    }

    private func x(for date: Date, in window: DateInterval) -> CGFloat {
        CGFloat(date.timeIntervalSince(window.start) / 3600) * hourWidth
    }

    private func channelColumn(_ list: [Channel]) -> some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: 32)
            ForEach(list) { channel in
                Button {
                    model.play(channel, in: list)
                } label: {
                    HStack(spacing: 8) {
                        RemoteImage(url: model.logoURL(for: channel), placeholderText: channel.name, maxPixelSize: 120)
                            .frame(width: 40, height: 28)
                        if Platform.isMac || channelColumnWidth > 100 {
                            Text(channel.name)
                                .font(.caption)
                                .lineLimit(2)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 8)
                    .frame(width: channelColumnWidth, height: rowHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .background(Color.secondary.opacity(0.06))
                .overlay(alignment: .bottom) { Divider() }
            }
        }
        .frame(width: channelColumnWidth)
    }

    private func timeHeader(_ window: DateInterval) -> some View {
        let slots = Int(window.duration / 1800)
        return HStack(spacing: 0) {
            ForEach(0..<slots, id: \.self) { slot in
                let date = window.start.addingTimeInterval(Double(slot) * 1800)
                Text(Formatting.time(date))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: hourWidth / 2, height: 32, alignment: .leading)
                    .padding(.leading, 4)
                    .id(date <= Date() && Date() < date.addingTimeInterval(1800) ? "now" : "slot-\(slot)")
            }
        }
    }

    private func programRow(_ channel: Channel, window: DateInterval, now: Date) -> some View {
        let programs = model.epg.programs(for: channel, in: window)
        return ZStack(alignment: .topLeading) {
            Color.clear.frame(width: x(for: window.end, in: window), height: rowHeight)
            if programs.isEmpty {
                Text("No information")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 10)
                    .frame(width: x(for: window.end, in: window), height: rowHeight - 4, alignment: .leading)
                    .background(Color.secondary.opacity(0.05), in: RoundedRectangle(cornerRadius: 6))
                    .offset(y: 2)
            }
            ForEach(programs) { program in
                let start = max(program.start, window.start)
                let end = min(program.end, window.end)
                let width = max(x(for: end, in: window) - x(for: start, in: window) - 2, 2)
                Button {
                    selected = ProgramSearchResult(channel: channel, program: program)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(program.title)
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                        Text(Formatting.timeRange(program.start, program.end))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .frame(width: max(width - 16, 0), alignment: .leading)
                    // Keeps the title readable when the programme started left of the visible area.
                    .visualEffect { content, proxy in
                        let hidden = -proxy.frame(in: .scrollView(axis: .horizontal)).minX
                        return content.offset(x: min(max(hidden + 6, 0), max(proxy.size.width - 56, 0)))
                    }
                    .padding(.horizontal, 8)
                    .frame(width: width, height: rowHeight - 4, alignment: .leading)
                    .clipped()
                    .background(
                        program.isLive(at: now) ? Color.accentColor.opacity(0.28) : Color.secondary.opacity(program.isPast(at: now) ? 0.06 : 0.13),
                        in: RoundedRectangle(cornerRadius: 6)
                    )
                    .overlay(alignment: .topTrailing) {
                        if program.isPast(at: now) && model.canReplay(program, on: channel) {
                            Image(systemName: "clock.arrow.circlepath")
                                .font(.caption2)
                                .foregroundStyle(Color.accentColor)
                                .padding(4)
                        } else if program.start > now && model.hasReminder(for: program, on: channel) {
                            Image(systemName: "bell.fill")
                                .font(.caption2)
                                .foregroundStyle(Color.accentColor)
                                .padding(4)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.leading, x(for: start, in: window) + 1)
                .padding(.top, 2)
            }
        }
        .frame(height: rowHeight)
        .overlay(alignment: .bottom) { Divider() }
    }

    private func nowLine(window: DateInterval, now: Date, height: CGFloat) -> some View {
        Rectangle()
            .fill(.red)
            .frame(width: 2, height: height)
            .offset(x: x(for: now, in: window))
            .allowsHitTesting(false)
    }
}

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
            return model.channels(in: nil)
        default:
            let category = categories.first { $0.id == selectedCategoryID }
            return model.channels(in: category)
        }
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let window = Self.window(around: context.date)
            let list = channels
            GeometryReader { geometry in
                // One scroll view for both directions, with rows made as they come into view: a list of thousands of
                // channels opens at once. The channel column stays at the left edge and the times at the top.
                ScrollView([.horizontal, .vertical]) {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                        Section {
                            ForEach(list) { channel in
                                HStack(spacing: 0) {
                                    channelCell(channel, in: list)
                                    programRow(channel, window: window, now: context.date)
                                }
                                .overlay(alignment: .bottom) { Divider() }
                            }
                        } header: {
                            timeHeader(window, now: context.date)
                        }
                    }
                }
                .defaultScrollAnchor(openingAnchor(now: context.date, window: window, viewportWidth: geometry.size.width))
                // The grid is a timeline that runs left to right in every language (Arabic text in it stays right to
                // left): the column that stays in place and where the grid opens are worked out in those terms.
                .environment(\.layoutDirection, .leftToRight)
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

    /// The start of the half hour that holds `date`.
    static func slot(containing date: Date, in window: DateInterval) -> Date {
        window.start.addingTimeInterval((date.timeIntervalSince(window.start) / 1800).rounded(.down) * 1800)
    }

    /// Where the grid opens: the current half hour next to the channel column, as a share of the scrollable width
    /// (before the first frame, so the grid does not jump there).
    private func openingAnchor(now: Date, window: DateInterval, viewportWidth: CGFloat) -> UnitPoint {
        let scrollable = channelColumnWidth + x(for: window.end, in: window) - viewportWidth
        guard scrollable > 0 else { return .topLeading }
        let target = x(for: Self.slot(containing: now, in: window), in: window)
        return UnitPoint(x: min(max(target / scrollable, 0), 1), y: 0)
    }

    private func x(for date: Date, in window: DateInterval) -> CGFloat {
        CGFloat(date.timeIntervalSince(window.start) / 3600) * hourWidth
    }

    private func channelCell(_ channel: Channel, in list: [Channel]) -> some View {
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
        .accessibilityIdentifier("guideChannel")
        .background { Self.columnBackground }
        .modifier(PinnedToLeadingEdge())
    }

    /// Opaque: the programmes scroll under the channel column and the time header.
    private static var columnBackground: some View {
        ZStack {
            Rectangle().fill(.background)
            Color.secondary.opacity(0.06)
        }
    }

    private func timeHeader(_ window: DateInterval, now: Date) -> some View {
        let slots = Int(window.duration / 1800)
        return HStack(spacing: 0) {
            Self.columnBackground
                .frame(width: channelColumnWidth, height: 32)
                .modifier(PinnedToLeadingEdge())
            ZStack(alignment: .topLeading) {
                HStack(spacing: 0) {
                    ForEach(0..<slots, id: \.self) { slot in
                        Text(Formatting.time(window.start.addingTimeInterval(Double(slot) * 1800)))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .padding(.leading, 4)
                            .frame(width: hourWidth / 2, height: 32, alignment: .leading)
                    }
                }
                nowLine(window: window, now: now, height: 32)
            }
        }
        .background(.background)
        .overlay(alignment: .bottom) { Divider() }
    }

    private func programRow(_ channel: Channel, window: DateInterval, now: Date) -> some View {
        let programs = model.epg.programs(for: channel, in: window)
        let column = channelColumnWidth
        return ZStack(alignment: .topLeading) {
            Color.clear.frame(width: x(for: window.end, in: window), height: rowHeight)
            if programs.isEmpty {
                Text("No information")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    // Follows the scroll, next to the channel column.
                    .visualEffect { content, proxy in
                        content.offset(x: max(column - proxy.frame(in: .scrollView).minX + 10, 0))
                    }
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
                    // Keeps the title readable when the programme started under the channel column.
                    .visualEffect { content, proxy in
                        let hidden = column - proxy.frame(in: .scrollView).minX
                        return content.offset(x: min(max(hidden + 6, 0), max(proxy.size.width - 56, 0)))
                    }
                    .padding(.horizontal, 8)
                    .frame(width: width, height: rowHeight - 4, alignment: .leading)
                    .clipped()
                    .background(
                        program.isLive(at: now) ? Color.white.opacity(0.2) : Color.secondary.opacity(program.isPast(at: now) ? 0.06 : 0.13),
                        in: RoundedRectangle(cornerRadius: 6)
                    )
                    .overlay(alignment: .topTrailing) {
                        if program.isPast(at: now) && model.canReplay(program, on: channel) {
                            Image(systemName: "clock.arrow.circlepath")
                                .font(.caption2)
                                .foregroundStyle(OndoColor.selected)
                                .padding(4)
                        } else if program.start > now && model.hasReminder(for: program, on: channel) {
                            Image(systemName: "bell.fill")
                                .font(.caption2)
                                .foregroundStyle(OndoColor.selected)
                                .padding(4)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.leading, x(for: start, in: window) + 1)
                .padding(.top, 2)
            }
            nowLine(window: window, now: now, height: rowHeight)
        }
        .frame(height: rowHeight)
    }

    private func nowLine(window: DateInterval, now: Date, height: CGFloat) -> some View {
        Rectangle()
            .fill(.red)
            .frame(width: 2, height: height)
            .offset(x: x(for: now, in: window))
            .allowsHitTesting(false)
    }
}

/// Keeps a view at the leading edge of the guide's scroll view while the programmes scroll under it, without
/// rebuilding anything as it scrolls.
private struct PinnedToLeadingEdge: ViewModifier {
    func body(content: Content) -> some View {
        content
            .visualEffect { content, proxy in
                content.offset(x: max(-proxy.frame(in: .scrollView).minX, 0))
            }
            .zIndex(1)
    }
}

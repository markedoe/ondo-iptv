import SwiftUI
import IPTVCore

/// Bottom bar of a live channel: programme now / next, DVR scrubber when the stream keeps a window,
/// and quick actions (restart the programme, last channel, back to live).
struct LiveInfoBar: View {
    @Environment(AppModel.self) private var model
    let session: PlaybackSession
    let channel: Channel?
    /// Called when the user interacts, to keep the controls visible.
    var onInteraction: () -> Void
    var onScrubbing: (Bool) -> Void

    @State private var scrubPosition: Double?

    private var controller: PlayerController { session.controller }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let programs = channel.map { model.epg.upcoming(for: $0, at: context.date, count: 2) } ?? []
            VStack(alignment: .leading, spacing: 10) {
                if controller.hasLiveWindow, let window = controller.liveWindow {
                    dvrScrubber(window)
                }
                HStack(alignment: .bottom) {
                    nowNext(programs, date: context.date)
                    Spacer()
                    if let end = controller.sleepTimerEnd {
                        Label(Formatting.time(end), systemImage: "moon.zzz").font(.caption)
                    }
                    if session.queue.count > 1 {
                        Text("\(session.index + 1)/\(session.queue.count)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
                actions(current: programs.first)
            }
        }
    }

    private func nowNext(_ programs: [EPGProgram], date: Date) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                LiveBadge()
                if let now = programs.first {
                    Text(now.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                } else {
                    Text("No guide information").font(.caption).foregroundStyle(.white.opacity(0.75))
                }
            }
            if let now = programs.first {
                ProgressView(value: now.progress(at: date))
                    .tint(.white)
                    .frame(maxWidth: 420)
                HStack {
                    Text(Formatting.timeRange(now.start, now.end))
                    if programs.count > 1 {
                        Text("Next: \(programs[1].title)").lineLimit(1)
                    }
                }
                .font(.caption)
                .foregroundStyle(.white.opacity(0.75))
            }
        }
    }

    private func dvrScrubber(_ window: ClosedRange<Double>) -> some View {
        HStack(spacing: 10) {
            Slider(
                value: Binding(
                    get: { min(max(scrubPosition ?? controller.currentTime, window.lowerBound), window.upperBound) },
                    set: { scrubPosition = $0 }
                ),
                in: window
            ) { editing in
                onScrubbing(editing)
                if !editing, let position = scrubPosition {
                    controller.seek(to: position)
                    scrubPosition = nil
                    onInteraction()
                }
            }
            .tint(.red)
            let offset = window.upperBound - (scrubPosition ?? controller.currentTime)
            Text(offset > 5 ? "-" + Formatting.clock(offset) : String(localized: "Live"))
                .font(.caption.monospacedDigit())
                .frame(minWidth: 54, alignment: .trailing)
        }
    }

    @ViewBuilder
    private func actions(current program: EPGProgram?) -> some View {
        let canRestart = program.map { session.canTimeshift(from: $0.start) } ?? false
        let behindLive = controller.hasLiveWindow && controller.liveOffset > 15
        if canRestart || session.canReturnToPreviousChannel || behindLive {
            HStack(spacing: 8) {
                if behindLive {
                    Button {
                        controller.seekToLiveEdge()
                        onInteraction()
                    } label: {
                        Label("Live", systemImage: "dot.radiowaves.left.and.right")
                    }
                }
                if let program, canRestart {
                    Button {
                        session.play(program)
                        onInteraction()
                    } label: {
                        Label("Restart", systemImage: "backward.end.fill")
                    }
                    .accessibilityIdentifier("restartProgram")
                }
                if session.canReturnToPreviousChannel {
                    Button {
                        session.returnToPreviousChannel()
                        onInteraction()
                    } label: {
                        Label("Last Channel", systemImage: "arrow.uturn.backward")
                    }
                    .accessibilityIdentifier("lastChannel")
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .tint(.white)
        }
    }
}

/// Header of the bottom bar while a live channel is watched from its archive.
struct TimeshiftHeader: View {
    let session: PlaybackSession
    var onInteraction: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text("REPLAY")
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(.orange, in: RoundedRectangle(cornerRadius: 4))
            if let title = session.timeshift?.programTitle {
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            if let position = session.timeshiftPosition {
                Text(Formatting.time(position))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.75))
            }
            Spacer()
            Button {
                session.returnToLive()
                onInteraction()
            } label: {
                Label("Back to Live", systemImage: "dot.radiowaves.left.and.right")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .tint(.white)
            .accessibilityIdentifier("backToLive")
        }
    }
}

struct LiveBadge: View {
    var body: some View {
        Text("LIVE")
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(.red, in: RoundedRectangle(cornerRadius: 4))
    }
}

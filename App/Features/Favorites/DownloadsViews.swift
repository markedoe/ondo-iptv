import SwiftUI
import IPTVCore
#if os(macOS)
import AppKit
#endif

// The TestFlight edition only (and development builds): the App Store edition is built without downloads.
#if TESTFLIGHT_DOWNLOADS || DEBUG

/// Download / progress / delete control for a movie or an episode.
struct DownloadButton: View {
    let item: PlaybackItem?
    var compact = false
    private var manager: DownloadManager { DownloadManager.shared }

    var body: some View {
        if let item, DownloadManager.canDownload(item) {
            let record = manager.record(for: item)
            Group {
                switch record?.status {
                case .completed?:
                    Menu {
                        Button("Delete Download", role: .destructive) { if let record { manager.delete(record) } }
                    } label: {
                        label("Downloaded", icon: "checkmark.circle.fill")
                    }
                case .downloading?, .queued?:
                    Menu {
                        Button("Pause", systemImage: "pause.circle") { if let record { manager.pause(record) } }
                        Button("Cancel Download", role: .destructive) { if let record { manager.delete(record) } }
                    } label: {
                        if let record, record.totalBytes <= 0 {
                            // Size not given by the provider: a spinner and what arrived, not a frozen 0 %.
                            HStack {
                                ProgressView().controlSize(.small)
                                if !compact { Text(DownloadsView.progressText(record)).monospacedDigit() }
                            }
                        } else if compact {
                            ProgressView(value: record?.progress ?? 0).progressViewStyle(.circular)
                        } else {
                            HStack {
                                ProgressView(value: record?.progress ?? 0).frame(width: 60)
                                Text((record?.progress ?? 0).formatted(.percent.precision(.fractionLength(0)))).monospacedDigit()
                            }
                        }
                    } primaryAction: {
                        if let record { manager.pause(record) }
                    }
                    .menuIndicator(.hidden)
                case .paused?, .failed?:
                    Menu {
                        Button("Resume Download", systemImage: "arrow.down.circle") { if let record { manager.resume(record) } }
                        Button("Delete Download", role: .destructive) { if let record { manager.delete(record) } }
                    } label: {
                        label("Resume Download", icon: "arrow.down.circle")
                    } primaryAction: {
                        if let record { manager.resume(record) }
                    }
                    .menuIndicator(.hidden)
                case nil:
                    Button {
                        manager.download(item)
                    } label: {
                        label("Download", icon: "arrow.down.circle")
                    }
                }
            }
            .help("Download")
        }
    }

    @ViewBuilder
    private func label(_ title: LocalizedStringKey, icon: String) -> some View {
        if compact {
            // Only the icon is drawn; VoiceOver still says what the button does.
            Image(systemName: icon).accessibilityLabel(Text(title))
        } else {
            Label(title, systemImage: icon)
        }
    }
}

struct DownloadsView: View {
    /// What Clean Up deletes, once confirmed.
    enum Cleanup: Identifiable {
        case unfinished, all
        var id: Self { self }
        var question: LocalizedStringKey {
            switch self {
            case .unfinished: return "Delete the unfinished downloads and what they received?"
            case .all: return "Delete all downloads and their files?"
            }
        }
    }

    @Environment(AppModel.self) private var model
    @State private var details: DownloadRecord?
    @State private var cleanup: Cleanup?
    private var manager: DownloadManager { DownloadManager.shared }

    var body: some View {
        let records = manager.records.filter(model.isVisible)
        List {
            ForEach(records) { record in
                HStack(spacing: 12) {
                    Color.secondary.opacity(0.12)
                        .frame(width: 48, height: 72)
                        .overlay { RemoteImage(url: record.item.artworkURL, placeholderText: record.item.title, contentMode: .fill, maxPixelSize: 150) }
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(record.item.title).font(.body.weight(.medium)).lineLimit(1)
                        if let subtitle = record.item.subtitle {
                            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        status(record)
                    }
                    Spacer()
                    Button {
                        details = record
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Download Details")
                    .help("Download Details")
                    if record.isCompleted {
                        Button {
                            model.playDownload(record)
                        } label: {
                            Image(systemName: "play.circle.fill").font(.title2)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Play")
                    } else {
                        DownloadButton(item: record.item, compact: true)
                            .buttonStyle(.borderless)
                    }
                    #if os(macOS)
                    Button(role: .destructive) {
                        manager.delete(record)
                    } label: {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Delete")
                    .help("Delete")
                    #endif
                }
                .contextMenu {
                    Button("Delete", role: .destructive) { manager.delete(record) }
                }
            }
            .onDelete { offsets in
                for offset in offsets { manager.delete(records[offset]) }
            }
        }
        .overlay {
            if records.isEmpty {
                ContentUnavailableView("No downloads", systemImage: "arrow.down.circle", description: Text("Download movies and episodes to watch them without a connection."))
            }
        }
        .sheet(item: $details) { record in
            DownloadDetailsView(record: record)
        }
        .navigationTitle("Downloads")
        .toolbar {
            ToolbarItem(placement: .status) {
                Text(storage).font(.caption).foregroundStyle(.secondary)
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Delete Unfinished Downloads", role: .destructive) { cleanup = .unfinished }
                        .disabled(!manager.hasUnfinished)
                    Button("Delete All Downloads", role: .destructive) { cleanup = .all }
                        .disabled(manager.records.isEmpty)
                } label: {
                    Label("Clean Up", systemImage: "trash")
                }
            }
        }
        .confirmationDialog(cleanup?.question ?? "Clean Up", isPresented: Binding(get: { cleanup != nil }, set: { if !$0 { cleanup = nil } }),
                            titleVisibility: .visible, presenting: cleanup) { choice in
            Button("Delete", role: .destructive) {
                if choice == .all { manager.deleteAll() } else { manager.deleteUnfinished() }
            }
        }
    }

    /// Space taken by the finished downloads and by what the unfinished ones received.
    private var storage: String {
        var parts = [String(localized: "\(Formatting.bytes(manager.usedBytes)) downloaded")]
        if manager.unfinishedBytes > 0 {
            parts.append(String(localized: "\(Formatting.bytes(manager.unfinishedBytes)) unfinished"))
        }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private func status(_ record: DownloadRecord) -> some View {
        switch record.status {
        case .completed:
            Label("Downloaded", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.green)
        case .downloading, .queued:
            if record.totalBytes > 0 { ProgressView(value: record.progress) }
            Text(Self.progressText(record, meter: manager.meters[record.id]))
                .font(.caption2)
                .foregroundStyle(.secondary)
            if let problem = manager.stalls[record.id] {
                Label(DownloadManager.message(for: problem), systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(5)
            }
        case .paused:
            if record.totalBytes > 0 { ProgressView(value: record.progress).tint(.secondary) }
            Label("Paused", systemImage: "pause.circle").font(.caption).foregroundStyle(.secondary)
            if (record.receivedBytes ?? 0) > 0 { Text(Self.progressText(record)).font(.caption2).foregroundStyle(.secondary) }
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red).lineLimit(5)
            if (record.receivedBytes ?? 0) > 0 { Text(Self.progressText(record)).font(.caption2).foregroundStyle(.secondary) }
        }
    }

    /// Percentage and size when the provider gives the size, otherwise what arrived so far; then, while it runs, the
    /// speed and the time left: a big film from a slow provider moves by a percent every few minutes.
    static func progressText(_ record: DownloadRecord, meter: TransferMeter? = nil) -> String {
        var parts: [String] = []
        if record.totalBytes > 0 {
            parts.append(record.progress.formatted(.percent.precision(.fractionLength(0))))
            let received = record.receivedBytes ?? Int64(record.progress * Double(record.totalBytes))
            parts.append(String(localized: "\(Formatting.bytes(received)) of \(Formatting.bytes(record.totalBytes))"))
        } else if let received = record.receivedBytes, received > 0 {
            parts.append(String(localized: "\(Formatting.bytes(received)) received"))
        } else {
            return String(localized: "Starting…")
        }
        if record.isActive, let meter {
            if let rate = meter.rate() {
                parts.append(String(localized: "\(Formatting.bytes(Int64(rate)))/s"))
            }
            if let left = meter.remaining(total: record.totalBytes) {
                let time = Duration.seconds(left).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
                parts.append(String(localized: "\(time) left"))
            }
        }
        return parts.joined(separator: " · ")
    }
}

/// Downloads, Details: how a download goes, live (speed, time left, what the provider answered, what it waits for,
/// what happened), to read or share. Like the playback report, it holds no address, login or title.
struct DownloadDetailsView: View {
    let record: DownloadRecord
    @Environment(\.dismiss) private var dismiss
    private var manager: DownloadManager { DownloadManager.shared }

    var body: some View {
        let current = manager.records.first { $0.id == record.id } ?? record
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                let report = manager.report(for: current).text
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(current.item.title).font(.headline)
                        Text(DownloadsView.progressText(current, meter: manager.meters[current.id]))
                            .font(.callout.monospacedDigit())
                        location(of: current)
                        Text("""
                            These details help find why a download is slow or stuck: what the provider answered, the \
                            speed, what the download waits for and what happened. They hold no address, login or title.
                            """)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Text(verbatim: report)
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding()
                }
                .toolbar {
                    ToolbarItemGroup(placement: .primaryAction) {
                        Button {
                            Pasteboard.copy(report)
                        } label: {
                            Label("Copy Report", systemImage: "doc.on.doc")
                        }
                        ShareLink(item: report, subject: Text("Ondo download report"))
                    }
                }
            }
            .navigationTitle("Download Details")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 520)
        #endif
    }

    /// Where the film is kept, so that it can be found on the Mac. The path stays out of the report, which is shared.
    private func location(of record: DownloadRecord) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Location").font(.subheadline.bold())
            #if os(macOS)
            let url = manager.localURL(for: record)
            Text(verbatim: url.path)
                .font(.caption.monospaced())
                .textSelection(.enabled)
            if !record.isCompleted {
                Text("The download is saved here when it ends.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text("The file is named with a code, not with the title.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Show in Finder", systemImage: "folder") {
                let exists = FileManager.default.fileExists(atPath: url.path)
                NSWorkspace.shared.activateFileViewerSelecting([exists ? url : DownloadManager.directory])
            }
            #else
            Text("Kept inside Ondo on this device: the Files app does not show it.")
                .font(.caption)
                .foregroundStyle(.secondary)
            #endif
            Button("Delete Download", systemImage: "trash", role: .destructive) {
                manager.delete(record)
                dismiss()
            }
        }
    }
}

#endif

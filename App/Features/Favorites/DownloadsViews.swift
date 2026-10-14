import SwiftUI
import IPTVCore

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
                    Button {
                        if let record { manager.pause(record) }
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
                    }
                case .paused?, .failed?:
                    Button {
                        if let record { manager.resume(record) }
                    } label: {
                        label("Resume Download", icon: "arrow.down.circle")
                    }
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
            Image(systemName: icon)
        } else {
            Label(title, systemImage: icon)
        }
    }
}

struct DownloadsView: View {
    @Environment(AppModel.self) private var model
    private var manager: DownloadManager { DownloadManager.shared }

    var body: some View {
        List {
            ForEach(manager.records) { record in
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
                }
                .contextMenu {
                    Button("Delete", role: .destructive) { manager.delete(record) }
                }
            }
            .onDelete { offsets in
                let records = manager.records
                for offset in offsets { manager.delete(records[offset]) }
            }
        }
        .overlay {
            if manager.records.isEmpty {
                ContentUnavailableView("No downloads", systemImage: "arrow.down.circle", description: Text("Download movies and episodes to watch them without a connection."))
            }
        }
        .navigationTitle("Downloads")
        .toolbar {
            ToolbarItem(placement: .status) {
                Text(Formatting.bytes(manager.usedBytes)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func status(_ record: DownloadRecord) -> some View {
        switch record.status {
        case .completed:
            Label("Downloaded", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.green)
        case .downloading, .queued:
            if record.totalBytes > 0 { ProgressView(value: record.progress) }
            Text(Self.progressText(record))
                .font(.caption2)
                .foregroundStyle(.secondary)
            if let problem = manager.stalls[record.id] {
                Label(DownloadManager.message(for: problem), systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(5)
            }
        case .paused:
            Label("Paused", systemImage: "pause.circle").font(.caption).foregroundStyle(.secondary)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red).lineLimit(5)
        }
    }

    /// Percentage and size when the provider gives the size, otherwise what arrived so far.
    static func progressText(_ record: DownloadRecord) -> String {
        if record.totalBytes > 0 {
            let percent = record.progress.formatted(.percent.precision(.fractionLength(0)))
            return "\(percent) · \(Formatting.bytes(record.totalBytes))"
        }
        if let received = record.receivedBytes, received > 0 {
            return String(localized: "\(Formatting.bytes(received)) received")
        }
        return String(localized: "Starting…")
    }
}

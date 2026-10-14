import SwiftUI
import IPTVCore

/// Up to four live channels side by side. Tap a tile to hear it, double-tap to open it in the player.
struct MultiviewView: View {
    @Environment(AppModel.self) private var model
    var onClose: () -> Void

    /// Tile being changed (`nil` index = add a tile).
    @State private var picker: PickerRequest?

    private struct PickerRequest: Identifiable {
        let tile: Int?
        var id: Int { tile ?? -1 }
    }

    private var multiview: Multiview { model.multiview }

    var body: some View {
        GeometryReader { proxy in
            let tiles = multiview.tiles
            let portrait = proxy.size.height > proxy.size.width
            let columns = tiles.count <= 1 || (tiles.count == 2 && portrait) ? 1 : 2
            let rows = max(Int((Double(tiles.count) / Double(columns)).rounded(.up)), 1)
            VStack(spacing: 2) {
                ForEach(0..<rows, id: \.self) { row in
                    HStack(spacing: 2) {
                        ForEach(row * columns ..< min(tiles.count, (row + 1) * columns), id: \.self) { index in
                            MultiviewTile(
                                session: tiles[index],
                                hasSound: index == multiview.audioIndex,
                                onSelect: { multiview.focusAudio(at: index) },
                                onExpand: { model.expandMultiviewTile(index) },
                                onChange: { picker = PickerRequest(tile: index) },
                                onRemove: { remove(at: index) }
                            )
                            .id(tiles[index].id)
                        }
                    }
                }
            }
            .overlay {
                if tiles.isEmpty {
                    ContentUnavailableView {
                        Label("Multiview", systemImage: "rectangle.split.2x2")
                    } description: {
                        Text("Watch up to 4 channels at once. Only the highlighted one plays sound.")
                    } actions: {
                        Button("Add Channel") { picker = PickerRequest(tile: nil) }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .overlay(alignment: .topLeading) { toolbar }
        .environment(\.colorScheme, .dark)
        .sheet(item: $picker) { request in
            MultiviewChannelPicker { channel in
                if let tile = request.tile {
                    model.setMultiviewTile(tile, to: channel)
                } else {
                    model.addToMultiview(channel)
                }
                picker = nil
            }
        }
    }

    private var toolbar: some View {
        HStack(spacing: 12) {
            Button(action: onClose) {
                Image(systemName: Platform.isMac ? "xmark" : "chevron.down")
                    .font(.title3.weight(.semibold))
                    .frame(width: 36, height: 36)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .keyboardShortcut(.cancelAction)
            .help("Close")
            .accessibilityLabel("Close")
            .accessibilityIdentifier("multiviewClose")
            if !multiview.isFull && !multiview.tiles.isEmpty {
                Button {
                    picker = PickerRequest(tile: nil)
                } label: {
                    Label("Add Channel", systemImage: "plus")
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule())
                }
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .padding()
    }

    private func remove(at index: Int) {
        multiview.remove(at: index)
        if multiview.tiles.isEmpty { onClose() }
    }
}

/// One channel of the multiview, with its name, programme and sound indicator.
struct MultiviewTile: View {
    @Environment(AppModel.self) private var model
    let session: PlaybackSession
    let hasSound: Bool
    var onSelect: () -> Void
    var onExpand: () -> Void
    var onChange: () -> Void
    var onRemove: () -> Void

    private var controller: PlayerController { session.controller }

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(controller: controller)
            #if canImport(VLCKitSPM)
            VLCVideoSurface(controller: controller)
                .opacity(controller.activeEngine == .vlc ? 1 : 0)
                .allowsHitTesting(false)
            #endif
            status
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 2, perform: onExpand)
        .onTapGesture(perform: onSelect)
        .overlay(alignment: .bottomLeading) { caption }
        .overlay(alignment: .topTrailing) { menu }
        .overlay {
            RoundedRectangle(cornerRadius: 2)
                .strokeBorder(hasSound ? Color.accentColor : Color.clear, lineWidth: 3)
                .allowsHitTesting(false)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(session.current?.title ?? ""))
    }

    @ViewBuilder
    private var status: some View {
        switch controller.state {
        case .loading:
            ProgressView().tint(.white)
        case .failed(_, let attribution):
            VStack(spacing: 8) {
                Image(systemName: attribution.systemImage).font(.title2)
                Button("Retry") { controller.retry() }
                    .buttonStyle(.bordered)
            }
            .foregroundStyle(.white)
        default:
            if controller.isBuffering { ProgressView().tint(.white) }
        }
    }

    private var caption: some View {
        let channel = session.current.flatMap { model.index.channelsByID[$0.reference.itemID] }
        let program = channel.flatMap { model.epg.current(for: $0) }
        return HStack(spacing: 6) {
            Image(systemName: hasSound ? "speaker.wave.2.fill" : "speaker.slash.fill")
                .foregroundStyle(hasSound ? Color.accentColor : .white.opacity(0.7))
            VStack(alignment: .leading, spacing: 1) {
                Text(session.current?.title ?? "").font(.caption.weight(.semibold)).lineLimit(1)
                if let program {
                    Text(program.title).font(.caption2).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))
        .padding(8)
        .allowsHitTesting(false)
    }

    private var menu: some View {
        Menu {
            Button(action: onExpand) { Label("Full Screen", systemImage: "arrow.up.left.and.arrow.down.right") }
            Button(action: onChange) { Label("Change Channel", systemImage: "arrow.left.arrow.right") }
            Button(role: .destructive, action: onRemove) { Label("Remove", systemImage: "xmark") }
        } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .frame(width: 32, height: 32)
                .background(.black.opacity(0.55), in: Circle())
                .foregroundStyle(.white)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .padding(8)
    }
}

/// Searchable list of the playlist's channels, favorites first.
struct MultiviewChannelPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onPick: (Channel) -> Void

    @State private var filter = ""

    private var channels: [Channel] {
        let all = model.channels(in: nil)
        guard !filter.isEmpty else { return Array(all.prefix(500)) }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return all.filter { channel in TextNormalizer.matches(normalizedText: TextNormalizer.normalize(channel.name), queryTokens: tokens) }
    }

    var body: some View {
        NavigationStack {
            List {
                let favorites = filter.isEmpty ? model.favoriteChannels() : []
                if !favorites.isEmpty {
                    Section("Favorites") {
                        ForEach(favorites) { channel in row(channel) }
                    }
                }
                Section {
                    ForEach(channels) { channel in row(channel) }
                } header: {
                    if !favorites.isEmpty { Text("All Channels") }
                }
            }
            .searchable(text: $filter, prompt: Text("Search channels"))
            .navigationTitle("Add Channel")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 520)
        #endif
    }

    private func row(_ channel: Channel) -> some View {
        Button {
            onPick(channel)
        } label: {
            ChannelRow(channel: channel)
        }
        .buttonStyle(.plain)
    }
}

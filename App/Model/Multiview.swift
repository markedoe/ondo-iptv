import Foundation
import IPTVCore
import Observation

/// Several live channels on screen at once (iPad and Mac). Only one tile plays sound.
@MainActor
@Observable
final class Multiview {
    static let maxTiles = 4

    /// One player per tile, in display order.
    private(set) var tiles: [PlaybackSession] = []
    /// Tile whose sound is on.
    private(set) var audioIndex = 0

    var isFull: Bool { tiles.count >= Self.maxTiles }

    func contains(_ item: PlaybackItem) -> Bool {
        tiles.contains { $0.current?.id == item.id }
    }

    /// Adds a channel in a new tile (ignored when full or already shown).
    @discardableResult
    func add(_ item: PlaybackItem, configure: (PlaybackSession) -> Void) -> Bool {
        guard !isFull, !contains(item) else { return false }
        let session = PlaybackSession(queue: [item], index: 0)
        configure(session)
        session.controller.isMuted = !tiles.isEmpty
        tiles.append(session)
        session.start()
        if tiles.count == 1 { audioIndex = 0 }
        return true
    }

    func replace(at index: Int, with item: PlaybackItem) {
        guard tiles.indices.contains(index) else { return }
        tiles[index].replace(with: item)
    }

    func remove(at index: Int) {
        guard tiles.indices.contains(index) else { return }
        tiles[index].controller.stop()
        tiles.remove(at: index)
        if audioIndex == index {
            audioIndex = 0
        } else if audioIndex > index {
            audioIndex -= 1
        }
        applyAudio()
    }

    /// Gives the sound to one tile and mutes the others.
    func focusAudio(at index: Int) {
        guard tiles.indices.contains(index) else { return }
        audioIndex = index
        applyAudio()
    }

    func stopAll() {
        for tile in tiles { tile.controller.stop() }
        tiles.removeAll()
        audioIndex = 0
    }

    private func applyAudio() {
        for (index, tile) in tiles.enumerated() {
            tile.controller.isMuted = index != audioIndex
        }
    }
}

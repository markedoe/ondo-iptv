import Foundation

/// What the player measured of the streams it played (`StreamSpecs`, `.measured`), by key (a playlist and a stream id):
/// the `capacity` streams played last, the oldest forgotten first. Kept on the device as one small document (`encoded()`,
/// `init(decoding:capacity:)`). The same rules as Android's `MeasuredSpecs`.
public struct MeasuredSpecs: Sendable, Equatable {
    /// Streams kept at most: a few hundred bytes each.
    public static let defaultCapacity = 2000

    public let capacity: Int
    /// Oldest first: a stream played again moves to the end.
    private var order: [String] = []
    private var specs: [String: StreamSpecs] = [:]

    private struct Entry: Codable {
        var key: String
        var specs: StreamSpecs
    }

    private struct Document: Codable {
        var entries: [Entry]
    }

    public init(capacity: Int = MeasuredSpecs.defaultCapacity) {
        self.capacity = max(1, capacity)
    }

    /// The document `encoded()` wrote; empty when it cannot be read.
    public init(decoding data: Data?, capacity: Int = MeasuredSpecs.defaultCapacity) {
        self.init(capacity: capacity)
        guard let data, let document = try? JSONDecoder().decode(Document.self, from: data) else { return }
        for entry in document.entries { put(entry.key, entry.specs) }
    }

    /// The key of `streamID` in `playlistID`: ids of two playlists can be the same.
    public static func key(playlistID: String, streamID: String) -> String { "\(playlistID)|\(streamID)" }

    public subscript(key: String) -> StreamSpecs? { specs[key] }

    public var count: Int { order.count }

    /// Every stream's key, oldest first.
    public var keys: [String] { order }

    /// Keeps what the player measured of `key`'s stream: false (nothing to save) when it is what was kept already, or
    /// nothing is known. A field the player did not read this time keeps what it read before (`StreamSpecs.orElse`).
    @discardableResult
    public mutating func record(_ key: String, _ measured: StreamSpecs) -> Bool {
        guard !measured.isEmpty else { return false }
        var reading = measured
        reading.source = .measured
        let merged = reading.orElse(specs[key])
        if specs[key] == merged {
            // Played again: kept a while longer.
            put(key, merged)
            return false
        }
        put(key, merged)
        return true
    }

    private mutating func put(_ key: String, _ value: StreamSpecs) {
        if specs[key] != nil { order.removeAll { $0 == key } }
        var kept = value
        kept.source = .measured
        specs[key] = kept
        order.append(key)
        while order.count > capacity {
            specs[order.removeFirst()] = nil
        }
    }

    private var document: Document {
        Document(entries: order.compactMap { key in specs[key].map { Entry(key: key, specs: $0) } })
    }

    public func encoded() -> Data? {
        try? JSONEncoder().encode(document)
    }
}

/// As one document (`FileStore`): `{"entries": [{"key", "specs"}]}`, oldest first, with the default capacity.
extension MeasuredSpecs: Codable {
    public init(from decoder: Decoder) throws {
        self.init()
        let document = try Document(from: decoder)
        for entry in document.entries { put(entry.key, entry.specs) }
    }

    public func encode(to encoder: Encoder) throws {
        try document.encode(to: encoder)
    }
}

import Foundation

/// How many seconds of a live channel VLC buffers before it shows the picture (its network caching). A provider
/// that stops sending for less than this is not seen; with a provider that sends in real time, the picture of a
/// channel also appears this much later (one that sends a few seconds ahead on connection shows it at once).
/// Measured with libVLC (tools/playback-lab): 3 s covers the short stops most providers have; once a stop used up
/// the whole buffer, the next channels get 6 s.
public struct LiveBufferPolicy: Sendable, Equatable {
    public static let normal = 3.0
    public static let raised = 6.0

    public private(set) var seconds = normal

    public init() {}

    /// The position stood still for `stall` seconds while `buffer` seconds were held: a stop that used up the buffer
    /// (the picture froze, or was about to) means this provider needs more.
    public mutating func note(stall: Double, buffer: Double) {
        if buffer > 0 && stall >= buffer { seconds = max(seconds, Self.raised) }
    }
}

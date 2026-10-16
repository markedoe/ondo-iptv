import Foundation

/// How many seconds VLC buffers before it shows the picture and after a seek (its network caching), when the buffer
/// setting is automatic. A provider that stops sending for less than this is not seen; the picture appears that much
/// later (on a provider that sends in real time) and seeks wait for it (on a connection that is not much faster than
/// the video). Measured with libVLC in tools/playback-lab: once a stop used up the buffer, the next items get more.
public struct BufferPolicy: Sendable, Equatable {
    /// Live channels: 3 s covers the short stops most providers have; 6 s after one that did not.
    public static let live = BufferPolicy(normal: 3, raised: 6)
    /// Films and episodes: VLC reads ahead while it plays, so 1 s is enough to start and to seek fast; 5 s after a
    /// stop on a connection too slow to read ahead.
    public static let film = BufferPolicy(normal: 1, raised: 5)

    public let normal: Double
    public let raised: Double
    public private(set) var seconds: Double

    public init(normal: Double, raised: Double) {
        self.normal = normal
        self.raised = raised
        seconds = normal
    }

    /// The position stood still for `stall` seconds while `buffer` seconds were held: a stop that used up the buffer
    /// (the picture froze, or was about to) means this provider or connection needs more.
    public mutating func note(stall: Double, buffer: Double) {
        if buffer > 0 && stall >= buffer { seconds = max(seconds, raised) }
    }
}

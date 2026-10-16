import Foundation

/// The buffer setting (Settings, Player): seconds of video the player gathers before it shows the picture and
/// after a seek. 0 is automatic. A longer buffer covers longer stops of the provider; the picture then appears
/// later and seeks wait longer (measured in tools/playback-lab).
public enum PlaybackBuffer {
    /// The choices offered, in seconds; 0 is automatic.
    public static let choices = [0, 1, 2, 3, 5, 10]

    /// Seconds to buffer: the setting when one is chosen, otherwise `automatic`.
    public static func seconds(setting: Int, automatic: Double) -> Double {
        setting > 0 ? Double(setting) : automatic
    }
}

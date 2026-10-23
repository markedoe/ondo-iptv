import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#elseif canImport(Musl)
import Musl
#endif

/// The network side of sync (docs/SYNC.md): which addresses are local, and how a snapshot travels. The same rules
/// as the Kotlin `SyncNetwork` (android/core).
public enum SyncNetwork {
    /// The Bonjour service Android devices advertise while sync is on.
    public static let serviceType = "_ondo._tcp"
    /// TXT records of the service: the device ID, the protocol version and a name for the device list.
    public static let deviceKey = "d"
    public static let versionKey = "v"
    public static let nameKey = "n"
    public static let protocolVersion = "1"
    public static let maxFrame = 16 * 1024 * 1024
    /// Seconds.
    public static let connectTimeout: TimeInterval = 10
    public static let sessionTimeout: TimeInterval = 30

    public enum FrameError: Error, Equatable {
        case tooLarge
        case cutShort
    }

    /// Whether an address (4 or 16 bytes) is on the local network: IPv4 10/8, 172.16/12, 192.168/16, 169.254/16 and
    /// 127/8; IPv6 fc00::/7, fe80::/10 and ::1; an IPv4 address mapped in IPv6 is checked as IPv4. Sync talks to
    /// nothing else.
    public static func isLocal(_ bytes: [UInt8]) -> Bool {
        switch bytes.count {
        case 4: return isLocalV4(bytes[0], bytes[1])
        case 16: return isLocalV6(bytes)
        default: return false
        }
    }

    private static func isLocalV4(_ first: UInt8, _ second: UInt8) -> Bool {
        first == 10 || (first == 172 && (16...31).contains(second)) || (first == 192 && second == 168)
            || (first == 169 && second == 254) || first == 127
    }

    private static func isLocalV6(_ bytes: [UInt8]) -> Bool {
        let mapped = bytes[0..<10].allSatisfy { $0 == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF
        if mapped { return isLocalV4(bytes[12], bytes[13]) }
        let loopback = bytes[0..<15].allSatisfy { $0 == 0 } && bytes[15] == 1
        return loopback || bytes[0] & 0xFE == 0xFC || (bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80)
    }

    /// The bytes of an IPv4 or IPv6 address written as text (4 or 16), nil when it is not one.
    public static func addressBytes(_ text: String) -> [UInt8]? {
        var v4 = in_addr()
        if inet_pton(AF_INET, text, &v4) == 1 {
            return withUnsafeBytes(of: &v4) { Array($0) }
        }
        var v6 = in6_addr()
        if inet_pton(AF_INET6, text, &v6) == 1 {
            return withUnsafeBytes(of: &v6) { Array($0) }
        }
        return nil
    }

    /// One frame: the length on 4 bytes (big-endian), then `payload`. Over `maxFrame`, refused.
    public static func frame(_ payload: Data) throws -> Data {
        guard payload.count <= maxFrame else { throw FrameError.tooLarge }
        let size = UInt32(payload.count)
        var frame = Data([UInt8(size >> 24 & 0xFF), UInt8(size >> 16 & 0xFF), UInt8(size >> 8 & 0xFF), UInt8(size & 0xFF)])
        frame.append(payload)
        return frame
    }

    /// The length a frame's 4-byte header announces; a length over `maxFrame` is refused.
    public static func frameLength(_ header: Data) throws -> Int {
        guard header.count == 4 else { throw FrameError.cutShort }
        let bytes = [UInt8](header)
        let size = Int(bytes[0]) << 24 | Int(bytes[1]) << 16 | Int(bytes[2]) << 8 | Int(bytes[3])
        guard size <= maxFrame else { throw FrameError.tooLarge }
        return size
    }

    /// The payload of one whole frame; a frame over `maxFrame`, or cut short, is refused.
    public static func readFrame(_ data: Data) throws -> Data {
        let size = try frameLength(data.prefix(4))
        let body = data.dropFirst(4)
        guard body.count >= size else { throw FrameError.cutShort }
        return Data(body.prefix(size))
    }
}

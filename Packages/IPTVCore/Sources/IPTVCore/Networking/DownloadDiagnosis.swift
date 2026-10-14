import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Why a download fails or does not progress, from what the provider answered.
public enum DownloadProblem: Equatable, Sendable {
    /// 401 / 403: subscription expired, connection limit reached, or downloads not allowed by the provider.
    case refused(status: Int)
    /// 404 / 410: the file is no longer on the provider's server.
    case notFound(status: Int)
    /// 429, 458, 509…: too many connections or requests for the subscription.
    case tooManyConnections(status: Int)
    /// Any other HTTP error.
    case httpError(status: Int)
    /// A message or a web page instead of the video (some providers refuse with a 200 answer).
    case errorPage
    /// Nothing received and no answer from the provider yet.
    case noResponse
    /// The provider answered but sends nothing.
    case noData
    /// The provider answers an ordinary request normally, yet the download receives nothing.
    case downloadStalled
    case offline
    case unreachable
    case timedOut
    case connectionLost
}

public enum DownloadDiagnosis {
    /// The problem an HTTP status reveals (nil for a success).
    public static func problem(status: Int) -> DownloadProblem? {
        switch status {
        case 200..<300: return nil
        case 401, 403: return .refused(status: status)
        case 404, 410: return .notFound(status: status)
        case 429, 456, 458, 509: return .tooManyConnections(status: status)
        default: return .httpError(status: status)
        }
    }

    /// The problem behind a transfer error (nil when it has no clearer explanation).
    public static func problem(urlErrorCode code: Int) -> DownloadProblem? {
        switch URLError.Code(rawValue: code) {
        case .notConnectedToInternet, .dataNotAllowed: return .offline
        case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed: return .unreachable
        case .timedOut: return .timedOut
        case .networkConnectionLost: return .connectionLost
        default: return nil
        }
    }

    /// What a download that received nothing for a while is waiting for, from its response (if any).
    public static func stall(status: Int?) -> DownloadProblem {
        guard let status else { return .noResponse }
        return problem(status: status) ?? .noData
    }

    /// Whether a body is a message or a page rather than a video: providers answer some refusals with a
    /// 200 and a short text ("Max connections reached"), which must not be saved as the film.
    public static func isErrorPage(contentType: String?, prefix: Data, size: Int64) -> Bool {
        if looksLikeVideo(prefix) { return false }
        // Messages are small: a large file is the film, whatever type the server announces.
        guard size < 1_000_000 else { return false }
        if prefix.isEmpty { return true }
        if let type = contentType?.lowercased(),
           type.hasPrefix("text/") || type.contains("json") || type.contains("xml") || type.contains("html") {
            return true
        }
        // Text (UTF-8 included) has no control characters besides tabs and line breaks; video does.
        return prefix.prefix(512).allSatisfy { ($0 >= 32 && $0 != 127) || $0 == 9 || $0 == 10 || $0 == 13 }
    }

    /// Signatures of the containers providers serve (a video is never taken for an error page).
    public static func looksLikeVideo(_ prefix: Data) -> Bool {
        let bytes = [UInt8](prefix.prefix(512))
        func starts(_ signature: [UInt8], at offset: Int = 0) -> Bool {
            bytes.count >= offset + signature.count && Array(bytes[offset..<offset + signature.count]) == signature
        }
        return starts([0x1A, 0x45, 0xDF, 0xA3])          // Matroska, WebM
            || ["ftyp", "moov", "mdat", "wide", "free", "skip"].contains { starts(Array($0.utf8), at: 4) } // MP4, MOV
            || starts(Array("RIFF".utf8))                  // AVI
            || starts(Array("FLV".utf8))
            || starts([0x00, 0x00, 0x01, 0xBA])            // MPEG program stream
            || starts([0x30, 0x26, 0xB2, 0x75])            // ASF, WMV
            || (bytes.count > 188 && bytes[0] == 0x47 && bytes[188] == 0x47) // MPEG-TS: a sync byte every 188 bytes
    }

    /// Total size of the file from a ranged answer ("bytes 0-511/734003200") or from the length of a whole one
    /// (the length of a ranged answer is the range's, not the file's).
    public static func totalSize(contentRange: String?, contentLength: Int64?) -> Int64? {
        if let contentRange {
            guard let slash = contentRange.lastIndex(of: "/"),
                  let total = Int64(contentRange[contentRange.index(after: slash)...].trimmingCharacters(in: .whitespaces)),
                  total > 0 else { return nil }
            return total
        }
        guard let contentLength, contentLength > 0 else { return nil }
        return contentLength
    }
}

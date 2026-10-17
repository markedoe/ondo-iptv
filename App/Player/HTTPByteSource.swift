import Foundation
import IPTVCore

// iPhone and iPad only: on the Mac, VLC plays MKV films (PlayerController+Remux).
#if os(iOS)

/// A provider's file read through range requests, for the remux of MKV films: one request at a time on one connection
/// (subscriptions often allow a single one), with the playlist's headers.
final class HTTPByteSource: ByteSource, @unchecked Sendable {
    private let url: URL
    private let headers: [String: String]
    private let session: URLSession
    /// Guards `knownSize`.
    private let lock = NSLock()
    private var knownSize: Int64?

    init(url: URL, headers: [String: String]) {
        self.url = url
        self.headers = headers
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.timeoutIntervalForRequest = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    deinit {
        session.invalidateAndCancel()
    }

    private func request(_ range: Range<Int64>) -> URLRequest {
        var request = URLRequest(url: url)
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        if request.value(forHTTPHeaderField: "User-Agent") == nil {
            request.setValue(defaultIPTVUserAgent, forHTTPHeaderField: "User-Agent")
        }
        request.setValue("bytes=\(range.lowerBound)-\(range.upperBound - 1)", forHTTPHeaderField: "Range")
        return request
    }

    /// The size, from the answer to a one-byte range request. A server that ignores ranges cannot serve the remux: its
    /// answer is dropped before the body comes.
    func size() async throws -> Int64? {
        if let known = lock.withLock({ knownSize }) { return known }
        let (bytes, response) = try await session.bytes(for: request(0..<1))
        bytes.task.cancel()
        try remember(response)
        return lock.withLock { knownSize }
    }

    func read(_ range: Range<Int64>) async throws -> Data {
        if let known = lock.withLock({ knownSize }), range.lowerBound >= known { return Data() }
        let (data, response) = try await session.data(for: request(range))
        if (response as? HTTPURLResponse)?.statusCode == 416 { return Data() }
        try remember(response)
        return data
    }

    /// Keeps the total size a partial answer gives ("Content-Range: bytes 0-0/13210000000").
    private func remember(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw RemuxError("no HTTP answer") }
        guard http.statusCode == 206 else { throw RemuxError("the server does not send parts of the file (HTTP \(http.statusCode))") }
        if let range = http.value(forHTTPHeaderField: "Content-Range"), let total = range.split(separator: "/").last.flatMap({ Int64($0) }) {
            lock.withLock { knownSize = total }
        }
    }
}

#endif

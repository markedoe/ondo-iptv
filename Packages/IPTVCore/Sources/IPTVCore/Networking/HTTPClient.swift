import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum HTTPClientError: Error, LocalizedError, Equatable {
    case badStatus(Int)
    case emptyResponse
    case invalidURL
    /// A request this client cannot send (a POST to a client that only downloads).
    case unsupportedRequest

    public var errorDescription: String? {
        switch self {
        case .badStatus(let code):
            switch code {
            case 401, 403: return "Access denied by the server (HTTP \(code)). Check your credentials or subscription."
            case 404: return "Not found on the server (HTTP 404)."
            case 512, 884: return "The provider refused the connection (HTTP \(code)). Too many connections?"
            default: return "The server answered with HTTP \(code)."
            }
        case .emptyResponse: return "The server returned an empty response."
        case .invalidURL: return "The address is not valid."
        case .unsupportedRequest: return "This request cannot be sent."
        }
    }
}

/// A request with a method and a body; most of the app only downloads (`HTTPClient.data(from:headers:)`).
public struct HTTPRequest: Sendable, Equatable {
    public var url: URL
    public var method: String
    public var headers: [String: String]
    public var body: Data?

    public init(url: URL, method: String = "GET", headers: [String: String] = [:], body: Data? = nil) {
        self.url = url
        self.method = method
        self.headers = headers
        self.body = body
    }
}

/// Minimal HTTP abstraction so loaders can be unit-tested with fixtures.
public protocol HTTPClient: Sendable {
    func data(from url: URL, headers: [String: String]) async throws -> Data
    func send(_ request: HTTPRequest) async throws -> Data
}

extension HTTPClient {
    /// Clients that only download send GET requests; anything else is refused clearly.
    public func send(_ request: HTTPRequest) async throws -> Data {
        guard request.method == "GET", request.body == nil else { throw HTTPClientError.unsupportedRequest }
        return try await data(from: request.url, headers: request.headers)
    }
}

/// Default user agent: many panels block unknown agents, VLC's is widely accepted.
public let defaultIPTVUserAgent = "VLC/3.0.20 LibVLC/3.0.20"

public final class URLSessionHTTPClient: HTTPClient, Sendable {
    private let session: URLSession
    private let timeout: TimeInterval

    public init(session: URLSession? = nil, timeout: TimeInterval = 60) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.default
            configuration.timeoutIntervalForRequest = timeout
            configuration.timeoutIntervalForResource = 600
            configuration.httpAdditionalHeaders = ["User-Agent": defaultIPTVUserAgent]
            self.session = URLSession(configuration: configuration)
        }
        self.timeout = timeout
    }

    public func data(from url: URL, headers: [String: String]) async throws -> Data {
        if url.isFileURL {
            return try Data(contentsOf: url)
        }
        return try await send(HTTPRequest(url: url, headers: headers))
    }

    public func send(_ httpRequest: HTTPRequest) async throws -> Data {
        var request = URLRequest(url: httpRequest.url, timeoutInterval: timeout)
        request.httpMethod = httpRequest.method
        request.httpBody = httpRequest.body
        for (key, value) in httpRequest.headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        let session = self.session
        let running = RunningTask()
        // Cancelling the calling task (switching playlist, leaving a screen) stops the download.
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let task = session.dataTask(with: request) { data, response, error in
                    if let error {
                        continuation.resume(throwing: error)
                        return
                    }
                    if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                        continuation.resume(throwing: HTTPClientError.badStatus(http.statusCode))
                        return
                    }
                    continuation.resume(returning: data ?? Data())
                }
                running.start(task)
            }
        } onCancel: {
            running.cancel()
        }
    }
}

/// The data task of a request, cancelled even when the cancellation arrives before the task exists.
private final class RunningTask: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionDataTask?
    private var cancelled = false

    func start(_ task: URLSessionDataTask) {
        lock.lock()
        self.task = task
        let cancelled = cancelled
        lock.unlock()
        task.resume()
        if cancelled { task.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let task = task
        lock.unlock()
        task?.cancel()
    }
}

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
    /// An answer past the client's limit (bytes, once inflated): a server that sends without end, or a gzip bomb.
    case tooLarge(Int)

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
        case .tooLarge(let limit): return "The server sent more than \(limit / 1_048_576) MB."
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
    /// Largest answer kept in memory, counted once URLSession has inflated it (`Content-Encoding: gzip`): far above any
    /// real playlist or guide, below what would get the app stopped. A playlist's guide address can be anyone's server.
    public static let maxBodySize = 256 * 1_048_576

    private let session: URLSession
    private let receiver: AnswerReceiver
    private let timeout: TimeInterval

    /// With `session`, its configuration is used: answers are read through this client's own session and delegate.
    public init(session: URLSession? = nil, timeout: TimeInterval = 60, maxBodySize: Int = URLSessionHTTPClient.maxBodySize) {
        let configuration: URLSessionConfiguration
        if let session {
            configuration = session.configuration
        } else {
            configuration = URLSessionConfiguration.default
            configuration.timeoutIntervalForRequest = timeout
            configuration.timeoutIntervalForResource = 600
            configuration.httpAdditionalHeaders = ["User-Agent": defaultIPTVUserAgent]
        }
        let receiver = AnswerReceiver(limit: maxBodySize)
        self.receiver = receiver
        self.session = URLSession(configuration: configuration, delegate: receiver, delegateQueue: nil)
        self.timeout = timeout
    }

    deinit {
        // A session keeps its delegate until it is invalidated.
        session.finishTasksAndInvalidate()
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
        let receiver = self.receiver
        let running = RunningTask()
        // Cancelling the calling task (switching playlist, leaving a screen) stops the download.
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let task = session.dataTask(with: request)
                receiver.expect(task) { continuation.resume(with: $0) }
                running.start(task)
            }
        } onCancel: {
            running.cancel()
        }
    }
}

/// Collects the answers of a client's session, each up to the limit. An error status or an answer past the limit
/// cancels its task and drops what came; what URLSession still delivers after that is not kept.
private final class AnswerReceiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private final class Answer {
        var data = Data()
        var checked = false
        var failure: HTTPClientError?
        let finish: @Sendable (Result<Data, Error>) -> Void

        init(finish: @escaping @Sendable (Result<Data, Error>) -> Void) {
            self.finish = finish
        }
    }

    private let limit: Int
    /// Guards `answers`.
    private let lock = NSLock()
    private var answers: [Int: Answer] = [:]

    init(limit: Int) {
        self.limit = limit
    }

    /// `finish` receives the task's body or its error, once.
    func expect(_ task: URLSessionDataTask, finish: @escaping @Sendable (Result<Data, Error>) -> Void) {
        lock.withLock { answers[task.taskIdentifier] = Answer(finish: finish) }
    }

    /// An error status, or a length announced past the limit.
    private func refusal(_ response: URLResponse?) -> HTTPClientError? {
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return .badStatus(http.statusCode) }
        if let response, response.expectedContentLength > Int64(limit) { return .tooLarge(limit) }
        return nil
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let stop: Bool = lock.withLock {
            guard let answer = answers[dataTask.taskIdentifier], answer.failure == nil else { return false }
            if !answer.checked {
                answer.checked = true
                answer.failure = refusal(dataTask.response)
            }
            if answer.failure == nil, answer.data.count + data.count > limit { answer.failure = .tooLarge(limit) }
            guard answer.failure == nil else {
                answer.data = Data()
                return true
            }
            answer.data.append(data)
            return false
        }
        if stop { dataTask.cancel() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let answer = lock.withLock({ answers.removeValue(forKey: task.taskIdentifier) }) else { return }
        if let failure = answer.failure ?? refusal(task.response) {
            answer.finish(.failure(failure))
        } else if let error {
            answer.finish(.failure(error))
        } else {
            answer.finish(.success(answer.data))
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

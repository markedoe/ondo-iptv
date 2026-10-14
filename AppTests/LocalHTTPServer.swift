import Foundation
import Network

/// A real HTTP/1.1 server on 127.0.0.1 for end-to-end tests. Requests leave the app through URLSession
/// and the network stack as they do with a provider, and the server records what actually arrived: the
/// headers sent, and the query decoded the way PHP panels decode it ("+" is a space).
final class LocalHTTPServer: @unchecked Sendable {
    struct Request: Sendable {
        let method: String
        /// Path without the query, percent-encoded as sent.
        let path: String
        let query: [String: String]
        /// Header names in lower case.
        let headers: [String: String]

        /// Path segments decoded one by one, as a web server splits them.
        var segments: [String] {
            path.split(separator: "/").map { $0.removingPercentEncoding ?? String($0) }
        }
    }

    struct Response: Sendable {
        var status = 200
        var headers: [String: String] = [:]
        var body = Data()

        static func text(_ text: String, type: String = "text/plain; charset=utf-8") -> Response {
            Response(headers: ["Content-Type": type], body: Data(text.utf8))
        }

        static func json(_ text: String) -> Response { .text(text, type: "application/json") }
        static func redirect(to location: String) -> Response { Response(status: 302, headers: ["Location": location]) }
        static let notFound = Response(status: 404)
    }

    private let listener: NWListener
    private let queue = DispatchQueue(label: "LocalHTTPServer")
    private let handler: @Sendable (Request) -> Response
    private let lock = NSLock()
    private var recorded: [Request] = []

    init(handler: @escaping @Sendable (Request) -> Response) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        self.handler = handler
    }

    deinit { listener.cancel() }

    /// Every request received so far, in order.
    var requests: [Request] {
        lock.withLock { recorded }
    }

    /// Starts listening and returns the server's address, `http://127.0.0.1:<port>`.
    func start() async throws -> URL {
        let once = Once()
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready:
                    guard let port = listener.port, let url = URL(string: "http://127.0.0.1:\(port.rawValue)") else { return }
                    once.run { continuation.resume(returning: url) }
                case .failed(let error):
                    once.run { continuation.resume(throwing: error) }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.serve(connection) }
            listener.start(queue: queue)
        }
    }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(on: connection, buffer: Data())
    }

    /// Reads up to the end of the headers (the tests only send GET requests, without a body).
    private func receive(on connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)), let head = String(data: buffer[..<end.lowerBound], encoding: .utf8),
               let request = Self.parse(head) {
                lock.withLock { recorded.append(request) }
                send(handler(request), on: connection)
            } else if isComplete || error != nil {
                connection.cancel()
            } else {
                receive(on: connection, buffer: buffer)
            }
        }
    }

    private func send(_ response: Response, on connection: NWConnection) {
        var head = "HTTP/1.1 \(response.status) \(HTTPURLResponse.localizedString(forStatusCode: response.status))\r\n"
        var headers = response.headers
        headers["Content-Length"] = String(response.body.count)
        headers["Connection"] = "close"
        for (name, value) in headers.sorted(by: { $0.key < $1.key }) { head += "\(name): \(value)\r\n" }
        head += "\r\n"
        connection.send(content: Data(head.utf8) + response.body, completion: .contentProcessed { _ in connection.cancel() })
    }

    private static func parse(_ head: String) -> Request? {
        let lines = head.components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2 else { return nil }
        let target = String(parts[1])
        let path = String(target.split(separator: "?", maxSplits: 1).first ?? "")
        var query: [String: String] = [:]
        if let rawQuery = target.split(separator: "?", maxSplits: 1).dropFirst().first {
            for pair in rawQuery.split(separator: "&") {
                let keyValue = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                func decode(_ part: Substring?) -> String {
                    let spaced = (part ?? "").replacingOccurrences(of: "+", with: " ")
                    return spaced.removingPercentEncoding ?? spaced
                }
                query[decode(keyValue.first)] = decode(keyValue.dropFirst().first)
            }
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        return Request(method: String(parts[0]), path: path, query: query, headers: headers)
    }
}

/// Runs a block once, whatever the thread (the listener reports its state more than once).
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func run(_ block: () -> Void) {
        let first = lock.withLock { () -> Bool in
            defer { done = true }
            return !done
        }
        if first { block() }
    }
}

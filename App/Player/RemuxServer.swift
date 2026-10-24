import Foundation
import IPTVCore
import Network

// iPhone and iPad only: on the Mac, VLC plays MKV films (PlayerController+Remux).
#if os(iOS)

/// Serves an MKV film remuxed to HLS to the system player, on 127.0.0.1 only: the playlists, the initialization segment
/// and the media segments, made from the provider's file as the player asks for them (`MatroskaRemux`). The system
/// player takes HLS media segments only from an HTTP server, not from a resource loader.
final class RemuxServer: @unchecked Sendable {
    private let remux: MatroskaRemux
    /// The provider's file, read for `remux`: its requests stop with the server.
    private let source: HTTPByteSource
    private let listener: NWListener
    private let queue = DispatchQueue(label: "Ondo.RemuxServer")
    /// The connections open, closed with the server (on the server's queue).
    private var clients: [ObjectIdentifier: Client] = [:]
    private var stopped = false
    /// A segment that could not be made, for the playback log (on the server's queue).
    var onFailure: (@Sendable (String) -> Void)?
    /// The first requests and every refused one, for the playback log: path, status, size and time taken.
    var onRequest: (@Sendable (String) -> Void)?
    /// The listener failed once ready (on the server's queue): iOS takes back the sockets of an app left suspended a
    /// while (a paused film, the phone locked), and the player can no longer connect.
    var onListenerFailure: (@Sendable (String) -> Void)?
    /// Set before `onListenerFailure` is called, for a player that fails before the callback reaches it.
    var hasFailed: Bool { failed.isSet }
    private let failed = Flag()
    private let served = Counter()

    init(remux: MatroskaRemux, source: HTTPByteSource) throws {
        let parameters = NWParameters.tcp
        // Bound to 127.0.0.1: nothing outside this device can connect.
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        self.remux = remux
        self.source = source
    }

    deinit {
        listener.cancel()
    }

    /// Starts listening; the address of the film's playlist.
    func start() async throws -> URL {
        let resumed = Resumed()
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self, listener] state in
                switch state {
                case .ready:
                    guard resumed.claim() else { return }
                    guard let port = listener.port?.rawValue, let url = URL(string: "http://127.0.0.1:\(port)/index.m3u8") else {
                        continuation.resume(throwing: RemuxError("no port"))
                        return
                    }
                    continuation.resume(returning: url)
                case .failed(let error):
                    if resumed.claim() {
                        continuation.resume(throwing: error)
                    } else {
                        self?.listenerFailed(error)
                    }
                case .cancelled:
                    if resumed.claim() { continuation.resume(throwing: CancellationError()) }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.start(queue: queue)
        }
    }

    /// The listener failed after it was ready (on the server's queue); stopping the server cancels it, which is no failure.
    private func listenerFailed(_ error: NWError) {
        guard !stopped else { return }
        failed.set()
        onListenerFailure?("\(error)")
    }

    /// Closes the connections, drops the answers being made and stops reading the provider's file (the player closed,
    /// another film, VLC taking over): nothing is downloaded for this film any more.
    func stop() {
        listener.cancel()
        queue.async { [self] in
            stopped = true
            let open = clients.values
            clients = [:]
            for client in open { close(client) }
        }
        Task { [remux] in await remux.close() }
        source.cancel()
    }

    private func accept(_ connection: NWConnection) {
        guard !stopped else {
            connection.cancel()
            return
        }
        let client = Client(connection)
        clients[ObjectIdentifier(connection)] = client
        connection.stateUpdateHandler = { [weak self, weak client] state in
            guard let self, let client else { return }
            switch state {
            case .cancelled, .failed: self.close(client)
            default: break
            }
        }
        connection.start(queue: queue)
        read(client)
    }

    /// The connection is closed, by the player or the server: the answer being made is dropped, and with it the read of
    /// the provider's file it waits for, which would hold the provider's single connection (a seek waiting behind it).
    private func close(_ client: Client) {
        client.answer?.cancel()
        client.answer = nil
        client.connection.cancel()
        if clients[ObjectIdentifier(client.connection)] === client { clients[ObjectIdentifier(client.connection)] = nil }
    }

    /// Reads all the time, also while an answer is made, so that the player closing the connection (a seek, the player
    /// closing) is seen at once; the requests received are answered one after the other (the player keeps the
    /// connection open).
    private func read(_ client: Client) {
        client.connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else {
                client.connection.cancel()
                return
            }
            if let data { client.buffer.append(data) }
            guard !isComplete, error == nil, client.buffer.count <= 64 * 1024 else {
                self.close(client)
                return
            }
            self.answerNext(client)
            self.read(client)
        }
    }

    /// Answers the next request received, once the answer before it is sent (on the server's queue).
    private func answerNext(_ client: Client) {
        guard !stopped, client.answer == nil, clients[ObjectIdentifier(client.connection)] === client,
              let end = client.buffer.range(of: Data("\r\n\r\n".utf8)) else { return }
        let head = String(decoding: client.buffer[client.buffer.startIndex..<end.lowerBound], as: UTF8.self)
        client.buffer = Data(client.buffer[end.upperBound...])
        let connection = client.connection
        client.answer = Task {
            let started = Date()
            let response = await self.respond(to: head)
            guard !Task.isCancelled else { return }
            self.log(head, response: response, since: started)
            connection.send(content: response, completion: .contentProcessed { [weak self] error in
                guard let self, error == nil else {
                    connection.cancel()
                    return
                }
                client.answer = nil
                self.answerNext(client)
            })
        }
    }

    private func log(_ head: String, response: Data, since started: Date) {
        let count = served.next()
        let status = Int(String(decoding: response.prefix(12).suffix(3), as: UTF8.self)) ?? 0
        guard count <= 6 || status >= 400, let onRequest else { return }
        let request = head.prefix { $0 != "\r" }.split(separator: " ").prefix(2).joined(separator: " ")
        let headLength = response.range(of: Data("\r\n\r\n".utf8))?.upperBound ?? 0
        onRequest("\(request): \(status), \(response.count - headLength) bytes in \(Int(Date().timeIntervalSince(started) * 1_000)) ms")
    }

    func respond(to head: String) async -> Data {
        let lines = head.components(separatedBy: "\r\n")
        let request = lines.first?.split(separator: " ") ?? []
        guard request.count >= 2, request[0] == "GET" || request[0] == "HEAD" else { return Self.response(status: 405) }
        let path = String(request[1].split(separator: "?").first ?? "")
        let range = lines.dropFirst().first { $0.lowercased().hasPrefix("range:") }.flatMap { Self.byteRange($0) }
        do {
            guard let (body, type) = try await content(for: path) else { return Self.response(status: 404) }
            return Self.response(body: body, type: type, range: range, headOnly: request[0] == "HEAD")
        } catch {
            // A request the player gave up, or the server stopping, is no failure of the remux.
            if !Task.isCancelled { onFailure?("\(path): \(error)") }
            return Self.response(status: 500)
        }
    }

    private func content(for path: String) async throws -> (Data, String)? {
        switch path {
        case "/index.m3u8":
            return (Data(remux.multivariantPlaylist(media: "media.m3u8").utf8), "application/vnd.apple.mpegurl")
        case "/media.m3u8":
            return (Data(remux.mediaPlaylist(initURI: "init.mp4") { "seg-\($0).m4s" }.utf8), "application/vnd.apple.mpegurl")
        case "/init.mp4":
            return (remux.initSegment, "video/mp4")
        default:
            guard path.hasPrefix("/seg-"), path.hasSuffix(".m4s"), let index = Int(path.dropFirst(5).dropLast(4)) else { return nil }
            return (try await remux.segment(index), "video/mp4")
        }
    }

    /// "Range: bytes=0-1023" (an open end reads to the last byte).
    static func byteRange(_ line: String) -> (start: Int, end: Int?)? {
        guard let spec = line.split(separator: "=", maxSplits: 1).last, !spec.contains(",") else { return nil }
        let bounds = spec.trimmingCharacters(in: .whitespaces).split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2, let start = Int(bounds[0]) else { return nil }
        return (start, Int(bounds[1]))
    }

    static func response(body: Data = Data(), type: String = "text/plain", status: Int = 200, range: (start: Int, end: Int?)? = nil, headOnly: Bool = false) -> Data {
        var status = status
        var body = body
        var extra = ""
        if status == 200, let range {
            let end = min(range.end ?? body.count - 1, body.count - 1)
            if range.start <= end {
                extra = "Content-Range: bytes \(range.start)-\(end)/\(body.count)\r\n"
                body = body.subdata(in: range.start..<end + 1)
                status = 206
            } else {
                extra = "Content-Range: bytes */\(body.count)\r\n"
                body = Data()
                status = 416
            }
        }
        let reason = [200: "OK", 206: "Partial Content", 404: "Not Found", 405: "Method Not Allowed", 416: "Range Not Satisfiable"][status] ?? "Error"
        var head = "HTTP/1.1 \(status) \(reason)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\n"
        head += "Accept-Ranges: bytes\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\(extra)\r\n"
        return Data(head.utf8) + (headOnly ? Data() : body)
    }
}

/// A connection of the player, used on the server's queue: what it sent that is not answered yet, and the answer being
/// made (one at a time).
private final class Client: @unchecked Sendable {
    let connection: NWConnection
    var buffer = Data()
    var answer: Task<Void, Never>?

    init(_ connection: NWConnection) {
        self.connection = connection
    }
}

/// A flag set on the server's queue and read by the player.
private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isSet: Bool { lock.withLock { value } }

    func set() {
        lock.withLock { value = true }
    }
}

/// Counts the requests served.
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    func next() -> Int {
        lock.withLock {
            value += 1
            return value
        }
    }
}

/// Resumes a continuation once, whichever state comes first.
private final class Resumed: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.withLock {
            defer { done = true }
            return !done
        }
    }
}

#endif

import Foundation
import IPTVCore
import Network

/// Serves an MKV film remuxed to HLS to the system player, on 127.0.0.1 only: the playlists, the initialization segment
/// and the media segments, made from the provider's file as the player asks for them (`MatroskaRemux`). The system
/// player takes HLS media segments only from an HTTP server, not from a resource loader.
final class RemuxServer: @unchecked Sendable {
    private let remux: MatroskaRemux
    private let listener: NWListener
    private let queue = DispatchQueue(label: "Ondo.RemuxServer")
    /// The connections open, closed with the server (on the server's queue).
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var stopped = false
    /// A segment that could not be made, for the playback log (on the server's queue).
    var onFailure: (@Sendable (String) -> Void)?

    init(remux: MatroskaRemux) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        parameters.acceptLocalOnly = true
        listener = try NWListener(using: parameters)
        self.remux = remux
    }

    deinit {
        listener.cancel()
    }

    /// Starts listening; the address of the film's playlist.
    func start() async throws -> URL {
        let resumed = Resumed()
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready:
                    guard resumed.claim() else { return }
                    guard let port = listener.port?.rawValue, let url = URL(string: "http://127.0.0.1:\(port)/index.m3u8") else {
                        continuation.resume(throwing: RemuxError("no port"))
                        return
                    }
                    continuation.resume(returning: url)
                case .failed(let error):
                    if resumed.claim() { continuation.resume(throwing: error) }
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

    func stop() {
        listener.cancel()
        queue.async { [self] in
            stopped = true
            for connection in connections.values { connection.cancel() }
            connections = [:]
        }
    }

    private func accept(_ connection: NWConnection) {
        guard !stopped else {
            connection.cancel()
            return
        }
        connections[ObjectIdentifier(connection)] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .cancelled, .failed: self.connections[ObjectIdentifier(connection)] = nil
            default: break
            }
        }
        connection.start(queue: queue)
        receive(on: connection, buffer: Data())
    }

    /// Reads a request, answers it, then waits for the next one on the same connection (the player keeps it open).
    private func receive(on connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else {
                connection.cancel()
                return
            }
            var buffer = buffer
            if let data { buffer.append(data) }
            guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                if isComplete || error != nil || buffer.count > 64 * 1024 {
                    connection.cancel()
                } else {
                    self.receive(on: connection, buffer: buffer)
                }
                return
            }
            let head = String(decoding: buffer[buffer.startIndex..<end.lowerBound], as: UTF8.self)
            let rest = Data(buffer[end.upperBound...])
            Task {
                let response = await self.respond(to: head)
                connection.send(content: response, completion: .contentProcessed { error in
                    guard error == nil else {
                        connection.cancel()
                        return
                    }
                    self.receive(on: connection, buffer: rest)
                })
            }
        }
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
            onFailure?("\(path): \(error)")
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
            return (try await remux.segment(index), "video/iso.segment")
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

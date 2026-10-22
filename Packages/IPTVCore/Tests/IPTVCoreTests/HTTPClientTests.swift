import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif
@testable import IPTVCore

/// The real URLSession client, against local sockets.
final class HTTPClientTests: XCTestCase {
    /// A server that accepts connections (the kernel does, through the listen backlog) and never answers.
    private final class SilentServer {
        let socket: Int32
        let port: UInt16

        init() throws {
            (socket, port) = try listeningSocket()
        }

        deinit { close(socket) }
    }

    /// A server that answers one request with `response`, then waits for the client to close the connection.
    private final class AnsweringServer {
        let socket: Int32
        let port: UInt16

        init(_ response: Data) throws {
            (socket, port) = try listeningSocket()
            let listener = socket
            Thread.detachNewThread {
                let client = acceptConnection(listener)
                guard client >= 0 else { return }
                readRequest(client)
                sendAll(client, response)
                readRequest(client, untilClosed: true)
                close(client)
            }
        }

        func url(_ path: String) throws -> URL {
            try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/\(path)"))
        }

        deinit { close(socket) }
    }

    /// An HTTP answer: its status line and headers, then its body.
    private func answer(_ head: [String], _ body: Data) -> Data {
        Data((head + ["Connection: close", "", ""]).joined(separator: "\r\n").utf8) + body
    }

    func testCancellingTheCallerStopsTheDownload() async throws {
        let server = try SilentServer()
        let client = URLSessionHTTPClient(timeout: 30)
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:\(server.port)/guide.xml"))
        let download = Task { try await client.data(from: url, headers: [:]) }
        try await Task.sleep(nanoseconds: 200_000_000)
        let cancelledAt = Date()
        download.cancel()
        do {
            _ = try await download.value
            XCTFail("a server that never answers cannot produce data")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .cancelled, "\(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(cancelledAt), 5, "the request stops at once, not at its 30 s timeout")
    }

    func testAnAlreadyCancelledCallerDoesNotDownload() async throws {
        let server = try SilentServer()
        let client = URLSessionHTTPClient(timeout: 30)
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:\(server.port)/list.m3u"))
        let download = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await client.data(from: url, headers: [:])
        }
        let started = Date()
        do {
            _ = try await download.value
            XCTFail("a cancelled caller cannot receive data")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .cancelled, "\(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)
    }

    /// Answers past the limit, announced or not: refused, without keeping them (a guide's address is anyone's server).
    func testAnswersPastTheLimitAreRefused() async throws {
        let limit = 64 * 1_024
        let client = URLSessionHTTPClient(timeout: 30, maxBodySize: limit)
        let announced = try AnsweringServer(answer(["HTTP/1.1 200 OK", "Content-Length: 1000000000000"], Data(count: limit)))
        let unannounced = try AnsweringServer(answer(["HTTP/1.1 200 OK"], Data(count: 4 * limit)))
        for server in [announced, unannounced] {
            do {
                _ = try await client.data(from: try server.url("guide.xml"), headers: [:])
                XCTFail("an answer past the limit")
            } catch {
                XCTAssertEqual(error as? HTTPClientError, .tooLarge(limit), "\(error)")
            }
        }
        XCTAssertEqual(HTTPClientError.tooLarge(URLSessionHTTPClient.maxBodySize).errorDescription, "The server sent more than 256 MB.")
    }

    /// URLSession inflates `Content-Encoding: gzip` itself: the limit counts what it gives, so a small gzip that inflates
    /// past it (a gzip bomb) is refused too.
    func testTheLimitCountsInflatedBytes() async throws {
        let gzip = try Fixture.data("epg.xml.gz")  // 550 bytes, 1,415 inflated
        let head = ["HTTP/1.1 200 OK", "Content-Encoding: gzip", "Content-Length: \(gzip.count)"]
        let inflating = try AnsweringServer(answer(head, gzip))
        do {
            _ = try await URLSessionHTTPClient(maxBodySize: 1_000).data(from: try inflating.url("guide.xml"), headers: [:])
            XCTFail("1,415 bytes once inflated")
        } catch {
            XCTAssertEqual(error as? HTTPClientError, .tooLarge(1_000), "\(error)")
        }
        let within = try AnsweringServer(answer(head, gzip))
        let data = try await URLSessionHTTPClient(maxBodySize: 2_000).data(from: try within.url("guide.xml"), headers: [:])
        XCTAssertEqual(data, try Fixture.data("epg.xml"))
    }

    func testErrorStatusesStillThrow() async throws {
        let server = try AnsweringServer(answer(["HTTP/1.1 403 Forbidden", "Content-Length: 13"], Data("Access denied".utf8)))
        do {
            _ = try await URLSessionHTTPClient().data(from: try server.url("list.m3u"), headers: [:])
            XCTFail("a refusal")
        } catch {
            XCTAssertEqual(error as? HTTPClientError, .badStatus(403), "\(error)")
        }
    }

    func testFileAddressesAreReadFromDisk() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("ondo-\(UUID().uuidString).m3u")
        try Data("#EXTM3U\n".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let data = try await URLSessionHTTPClient().data(from: file, headers: [:])
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "#EXTM3U\n")
    }
}

/// A socket listening on 127.0.0.1, on a port the system picks.
private func listeningSocket() throws -> (socket: Int32, port: UInt16) {
    #if canImport(Glibc)
    let fd = Glibc.socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
    #else
    let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
    #endif
    var address = sockaddr_in()
    address.sin_family = sa_family_t(AF_INET)
    address.sin_addr.s_addr = inet_addr("127.0.0.1")
    address.sin_port = 0
    #if canImport(Darwin)
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    #endif
    let length = socklen_t(MemoryLayout<sockaddr_in>.size)
    let bound = withUnsafePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bindSocket(fd, $0, length) }
    }
    guard fd >= 0, bound == 0, listen(fd, 8) == 0 else { throw URLError(.cannotConnectToHost) }
    var assigned = sockaddr_in()
    var assignedLength = length
    _ = withUnsafeMutablePointer(to: &assigned) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &assignedLength) }
    }
    return (fd, UInt16(bigEndian: assigned.sin_port))
}

/// `bind` alone can resolve to an instance method on Apple platforms: name the C function's module.
private func bindSocket(_ fd: Int32, _ address: UnsafePointer<sockaddr>, _ length: socklen_t) -> Int32 {
    #if canImport(Glibc)
    return Glibc.bind(fd, address, length)
    #else
    return Darwin.bind(fd, address, length)
    #endif
}

private func acceptConnection(_ fd: Int32) -> Int32 {
    #if canImport(Glibc)
    return Glibc.accept(fd, nil, nil)
    #else
    return Darwin.accept(fd, nil, nil)
    #endif
}

/// Reads up to the end of a request's headers, or until the client closes the connection (10 s at most).
private func readRequest(_ fd: Int32, untilClosed: Bool = false) {
    var timeout = timeval(tv_sec: 10, tv_usec: 0)
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    var received = Data()
    var buffer = [UInt8](repeating: 0, count: 4_096)
    while untilClosed || received.range(of: Data("\r\n\r\n".utf8)) == nil {
        #if canImport(Glibc)
        let length = Glibc.recv(fd, &buffer, buffer.count, 0)
        #else
        let length = Darwin.recv(fd, &buffer, buffer.count, 0)
        #endif
        guard length > 0 else { return }
        if !untilClosed { received.append(contentsOf: buffer[0..<length]) }
    }
}

/// Sends `data`, up to where the client closes the connection (without SIGPIPE, which would stop the tests).
private func sendAll(_ fd: Int32, _ data: Data) {
    #if canImport(Glibc)
    let flags = Int32(MSG_NOSIGNAL)
    #else
    var on: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
    let flags: Int32 = 0
    #endif
    data.withUnsafeBytes { bytes in
        guard let base = bytes.baseAddress else { return }
        var sent = 0
        while sent < bytes.count {
            #if canImport(Glibc)
            let written = Glibc.send(fd, base + sent, bytes.count - sent, flags)
            #else
            let written = Darwin.send(fd, base + sent, bytes.count - sent, flags)
            #endif
            guard written > 0 else { return }
            sent += written
        }
    }
}

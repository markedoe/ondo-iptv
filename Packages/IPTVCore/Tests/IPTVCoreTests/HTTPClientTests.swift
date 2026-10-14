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
            socket = fd
            port = UInt16(bigEndian: assigned.sin_port)
        }

        deinit { close(socket) }
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
        await XCTAssertThrowsErrorAsync(try await download.value)
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)
    }

    func testFileAddressesAreReadFromDisk() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("ondo-\(UUID().uuidString).m3u")
        try Data("#EXTM3U\n".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let data = try await URLSessionHTTPClient().data(from: file, headers: [:])
        XCTAssertEqual(String(decoding: data, as: UTF8.self), "#EXTM3U\n")
    }
}

/// `bind` alone can resolve to an instance method on Apple platforms: name the C function's module.
private func bindSocket(_ fd: Int32, _ address: UnsafePointer<sockaddr>, _ length: socklen_t) -> Int32 {
    #if canImport(Glibc)
    return Glibc.bind(fd, address, length)
    #else
    return Darwin.bind(fd, address, length)
    #endif
}

func XCTAssertThrowsErrorAsync<T>(_ expression: @autoclosure () async throws -> T, file: StaticString = #filePath, line: UInt = #line) async {
    do {
        _ = try await expression()
        XCTFail("expected an error", file: file, line: line)
    } catch {}
}

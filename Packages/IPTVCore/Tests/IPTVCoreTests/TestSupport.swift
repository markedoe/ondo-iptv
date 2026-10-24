import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import IPTVCore

enum Fixture {
    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Fixtures") else {
            throw NSError(domain: "Fixture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing fixture \(name)"])
        }
        return try Data(contentsOf: url)
    }
}

/// Serves fixtures based on the Xtream `action` query item or the URL path.
final class MockHTTPClient: HTTPClient, @unchecked Sendable {
    var routes: [String: Data] = [:]
    /// Errors thrown, one per request, before the route answers (a flaky or busy server).
    var failures: [String: [Error]] = [:]
    private(set) var requested: [URL] = []
    private let lock = NSLock()

    private func record(_ url: URL, key: String) -> Error? {
        lock.lock()
        defer { lock.unlock() }
        requested.append(url)
        guard var queue = failures[key], !queue.isEmpty else { return nil }
        let error = queue.removeFirst()
        failures[key] = queue
        return error
    }

    /// Every request, with its method, headers and body.
    private(set) var sent: [HTTPRequest] = []

    /// POST (and other non-GET) requests answer from `routes["POST <url>"]`.
    func send(_ request: HTTPRequest) async throws -> Data {
        guard request.method != "GET" else { return try await data(from: request.url, headers: request.headers) }
        let key = "\(request.method) \(request.url.absoluteString)"
        let failure: Error? = lock.withLock {
            sent.append(request)
            requested.append(request.url)
            guard var queue = failures[key], !queue.isEmpty else { return nil }
            let error = queue.removeFirst()
            failures[key] = queue
            return error
        }
        if let failure { throw failure }
        guard let data = route(key) else { throw HTTPClientError.badStatus(404) }
        return data
    }

    /// The exact route, else the longest route the address starts with (`https://host/path?` for any query).
    private func route(_ key: String) -> Data? {
        routes[key] ?? routes.filter { $0.key.contains("://") && key.hasPrefix($0.key) }.max { $0.key.count < $1.key.count }?.value
    }

    func data(from url: URL, headers: [String: String]) async throws -> Data {
        lock.withLock { sent.append(HTTPRequest(url: url, headers: headers)) }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let action = components?.queryItems?.first(where: { $0.name == "action" })?.value ?? ""
        let key = url.path.hasSuffix("player_api.php") ? "api:\(action)" : url.absoluteString
        if let error = record(url, key: key) { throw error }
        guard let data = route(key) else { throw HTTPClientError.badStatus(404) }
        return data
    }
}

extension Date {
    /// 2025-10-09 HH:MM UTC
    static func utc(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }
}

#if os(Linux)
/// The process's resident memory, from /proc/self (Linux).
enum ResidentMemory {
    private static func value(_ key: String) -> Int {
        let status = (try? String(contentsOfFile: "/proc/self/status", encoding: .utf8)) ?? ""
        for line in status.split(separator: "\n") where line.hasPrefix(key + ":") {
            return (Int(line.split(separator: " ").dropFirst().first ?? "") ?? 0) * 1_024
        }
        return 0
    }

    static var current: Int { value("VmRSS") }

    /// The largest resident size since the last `resetPeak()`.
    static var peak: Int { value("VmHWM") }

    /// Starts measuring the peak from the current size.
    static func resetPeak() throws {
        try "5".write(toFile: "/proc/self/clear_refs", atomically: false, encoding: .utf8)
        guard peak < current + (8 << 20) else { throw XCTSkip("The peak resident size cannot be reset here.") }
    }
}
#endif

import Foundation
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
    private(set) var requested: [URL] = []
    private let lock = NSLock()

    private func record(_ url: URL) {
        lock.lock()
        requested.append(url)
        lock.unlock()
    }

    func data(from url: URL, headers: [String: String]) async throws -> Data {
        record(url)
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let action = components?.queryItems?.first(where: { $0.name == "action" })?.value ?? ""
        let key = url.path.hasSuffix("player_api.php") ? "api:\(action)" : url.absoluteString
        guard let data = routes[key] else { throw HTTPClientError.badStatus(404) }
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

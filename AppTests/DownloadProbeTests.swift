import XCTest
import IPTVCore
@testable import Ondo

/// The probe a stalled download runs: one ordinary ranged request that tells a refusal, a message sent instead of
/// the film or an unreachable server from a download the system simply has not started.
final class DownloadProbeTests: XCTestCase {
    private func probe(
        _ respond: @escaping @Sendable (LocalHTTPServer.Request) -> LocalHTTPServer.Response
    ) async throws -> (DownloadProblem, [LocalHTTPServer.Request]) {
        let server = try LocalHTTPServer(handler: respond)
        let base = try await server.start()
        let problem = await DownloadManager.probe(URLRequest(url: base.appending(path: "movie/1.mkv")))
        return (problem, server.requests)
    }

    func testARefusalIsNamedWithItsStatus() async throws {
        let (problem, requests) = try await probe { _ in LocalHTTPServer.Response(status: 403) }
        XCTAssertEqual(problem, .refused(status: 403))
        XCTAssertEqual(requests.first?.headers["range"], "bytes=0-511", "only the start of the file is asked for")
    }

    func testAConnectionLimitIsNamed() async throws {
        let (problem, _) = try await probe { _ in LocalHTTPServer.Response(status: 509) }
        XCTAssertEqual(problem, .tooManyConnections(status: 509))
    }

    func testAMessageSentWithA200IsAnErrorPage() async throws {
        let (problem, _) = try await probe { _ in .text("Max connections reached") }
        XCTAssertEqual(problem, .errorPage)
    }

    func testAVideoAnsweredNormallyMeansTheDownloadItselfStalled() async throws {
        var head = Data([0x00, 0x00, 0x00, 0x20]) + Data("ftypisom".utf8)
        head += Data(count: 512 - head.count)
        let body = head
        let (problem, _) = try await probe { _ in
            LocalHTTPServer.Response(
                status: 206,
                headers: ["Content-Type": "video/mp4", "Content-Range": "bytes 0-511/734003200"],
                body: body
            )
        }
        XCTAssertEqual(problem, .downloadStalled)
    }

    func testAnUnreachableServerIsSaidSo() async {
        let problem = await DownloadManager.probe(URLRequest(url: URL(string: "http://127.0.0.1:9/movie.mkv")!))
        XCTAssertEqual(problem, .unreachable)
    }
}

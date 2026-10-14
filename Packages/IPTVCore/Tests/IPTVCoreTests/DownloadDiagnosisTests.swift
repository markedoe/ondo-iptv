import Foundation
import XCTest
@testable import IPTVCore

final class DownloadDiagnosisTests: XCTestCase {
    func testStatusesSayWhoRefusedAndWhy() {
        XCTAssertNil(DownloadDiagnosis.problem(status: 200))
        XCTAssertNil(DownloadDiagnosis.problem(status: 206))
        XCTAssertEqual(DownloadDiagnosis.problem(status: 403), .refused(status: 403))
        XCTAssertEqual(DownloadDiagnosis.problem(status: 401), .refused(status: 401))
        XCTAssertEqual(DownloadDiagnosis.problem(status: 404), .notFound(status: 404))
        XCTAssertEqual(DownloadDiagnosis.problem(status: 509), .tooManyConnections(status: 509))
        XCTAssertEqual(DownloadDiagnosis.problem(status: 429), .tooManyConnections(status: 429))
        XCTAssertEqual(DownloadDiagnosis.problem(status: 500), .httpError(status: 500))
    }

    func testTransferErrorsHaveAPlainExplanation() {
        XCTAssertEqual(DownloadDiagnosis.problem(urlErrorCode: URLError.Code.notConnectedToInternet.rawValue), .offline)
        XCTAssertEqual(DownloadDiagnosis.problem(urlErrorCode: URLError.Code.cannotConnectToHost.rawValue), .unreachable)
        XCTAssertEqual(DownloadDiagnosis.problem(urlErrorCode: URLError.Code.timedOut.rawValue), .timedOut)
        XCTAssertEqual(DownloadDiagnosis.problem(urlErrorCode: URLError.Code.networkConnectionLost.rawValue), .connectionLost)
        XCTAssertNil(DownloadDiagnosis.problem(urlErrorCode: URLError.Code.badServerResponse.rawValue))
    }

    /// A download stuck at "Starting…" says what it is waiting for.
    func testAStalledDownloadSaysWhatItWaitsFor() {
        XCTAssertEqual(DownloadDiagnosis.stall(status: nil), .noResponse)
        XCTAssertEqual(DownloadDiagnosis.stall(status: 200), .noData)
        XCTAssertEqual(DownloadDiagnosis.stall(status: 403), .refused(status: 403))
    }

    func testAShortMessageIsNotSavedAsTheFilm() {
        let message = Data("Max connections reached".utf8)
        XCTAssertTrue(DownloadDiagnosis.isErrorPage(contentType: "application/octet-stream", prefix: message, size: Int64(message.count)))
        XCTAssertTrue(DownloadDiagnosis.isErrorPage(contentType: nil, prefix: Data("Accès refusé : abonnement expiré\n".utf8), size: 40))
        let page = Data("<html><body>Forbidden</body></html>".utf8)
        XCTAssertTrue(DownloadDiagnosis.isErrorPage(contentType: "text/html; charset=utf-8", prefix: page, size: 300))
        XCTAssertTrue(DownloadDiagnosis.isErrorPage(contentType: "video/x-matroska", prefix: Data(), size: 0), "nothing is not a film")
        XCTAssertTrue(DownloadDiagnosis.isErrorPage(contentType: "application/json", prefix: Data("{\"error\":1}".utf8), size: 11))
    }

    func testVideosAreNeverTakenForErrorPages() {
        var matroska = Data([0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00])
        matroska.append(Data(repeating: 0x42, count: 100))
        XCTAssertFalse(DownloadDiagnosis.isErrorPage(contentType: "text/plain", prefix: matroska, size: 700_000_000))
        let mp4 = Data([0x00, 0x00, 0x00, 0x20]) + Data("ftypisom".utf8) + Data(repeating: 0, count: 20)
        XCTAssertTrue(DownloadDiagnosis.looksLikeVideo(mp4))
        XCTAssertFalse(DownloadDiagnosis.isErrorPage(contentType: "video/mp4", prefix: mp4, size: 500))
        var transportStream = Data(repeating: 0xFF, count: 400)
        transportStream[0] = 0x47
        transportStream[188] = 0x47
        XCTAssertTrue(DownloadDiagnosis.looksLikeVideo(transportStream))
        // A text that happens to start with "G" is not a transport stream.
        XCTAssertFalse(DownloadDiagnosis.looksLikeVideo(Data("Gone".utf8)))
        // A large file is the film even when the server announces text, and a QuickTime file may open on `moov`.
        XCTAssertFalse(DownloadDiagnosis.isErrorPage(contentType: "text/plain", prefix: Data("plain words".utf8), size: 800_000_000))
        XCTAssertTrue(DownloadDiagnosis.looksLikeVideo(Data([0, 0, 0, 8]) + Data("moov".utf8)))
        // Binary data without a known signature (a large file of an unknown container) is kept.
        let binary = Data((0..<512).map { UInt8($0 % 256) })
        XCTAssertFalse(DownloadDiagnosis.isErrorPage(contentType: "application/octet-stream", prefix: binary, size: 900_000_000))
    }

    func testTotalSizeComesFromTheRangeOrTheLength() {
        XCTAssertEqual(DownloadDiagnosis.totalSize(contentRange: "bytes 0-511/734003200", contentLength: 512), 734_003_200)
        XCTAssertNil(DownloadDiagnosis.totalSize(contentRange: "bytes 0-511/*", contentLength: 512))
        XCTAssertEqual(DownloadDiagnosis.totalSize(contentRange: nil, contentLength: 1_000), 1_000)
        XCTAssertNil(DownloadDiagnosis.totalSize(contentRange: nil, contentLength: -1))
    }
}

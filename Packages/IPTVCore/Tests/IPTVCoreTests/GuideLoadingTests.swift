import XCTest
@testable import IPTVCore

/// Serves files: `download` copies them, as a streaming download writes its file; `data(from:)` is refused, so a test
/// fails if a large guide or list would be kept whole in memory.
final class FileHTTPClient: HTTPClient, @unchecked Sendable {
    let routes: [String: URL]

    init(_ routes: [String: URL]) {
        self.routes = routes
    }

    func data(from url: URL, headers: [String: String]) async throws -> Data {
        throw HTTPClientError.unsupportedRequest
    }

    func download(from url: URL, headers: [String: String], to file: URL) async throws {
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let action = components?.queryItems?.first(where: { $0.name == "action" })?.value
        let key = url.path.hasSuffix("player_api.php") ? "api:\(action ?? "")" : url.absoluteString
        guard let source = routes[key] else { throw HTTPClientError.badStatus(404) }
        try? FileManager.default.removeItem(at: file)
        try FileManager.default.copyItem(at: source, to: file)
    }
}

final class GuideLoadingTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("ondo-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
    }

    private func fixtureFile(_ name: String) throws -> URL {
        let file = folder.appendingPathComponent(name)
        try Fixture.data(name).write(to: file)
        return file
    }

    private let content = PlaylistContent(channels: [
        Channel(id: "c1", name: "TV1", categoryID: "live-1", streamURL: URL(string: "http://stream.example.com/1")!, epgID: "TV1.fr"),
        Channel(id: "c2", name: "Nation 2", categoryID: "live-1", streamURL: URL(string: "http://stream.example.com/2")!),
    ])

    /// Guides are downloaded to files and read from them, plain or gzip; a missing one is reported, the others kept.
    func testGuidesAreReadFromDownloadedFiles() async throws {
        let plain = URL(string: "http://epg.example.com/guide.xml")!
        let gzip = URL(string: "http://epg.example.com/guide.xml.gz")!
        let missing = URL(string: "http://epg.example.com/missing.xml")!
        let http = FileHTTPClient([plain.absoluteString: try fixtureFile("epg.xml"), gzip.absoluteString: try fixtureFile("epg.xml.gz")])
        let loader = PlaylistLoader(http: http, localFileURL: { URL(fileURLWithPath: $0) })
        let playlist = Playlist(name: "G", source: .remote(URL(string: "http://provider.example.com/list.m3u")!),
                                epgURLs: [missing, gzip, plain], useProviderEPG: false)
        let result = await loader.loadEPG(for: playlist, content: content, window: DateInterval(start: .utc(2025, 10, 9, 0), duration: 86_400))
        XCTAssertEqual(result.data.programs["TV1.fr"]?.map(\.title), ["Le JT", "Film du soir"])
        XCTAssertEqual(result.data.programs["nation2"]?.map(\.title), ["Chantez maintenant"], "matched by name")
        XCTAssertNil(result.data.programs["unused.fr"])
        XCTAssertEqual(result.failures.keys.map(\.absoluteString), [missing.absoluteString])
        XCTAssertEqual(result.failures[missing] as? HTTPClientError, .badStatus(404))
    }

    // MARK: - Memory budget

    /// 2025-10-09 20:20 UTC: programmes start on the hour.
    private let now = Date.utc(2025, 10, 9, 20, 20)

    func testTheWindowGrowsAroundNowWithinTheBudget() {
        let hour = 3_600.0
        let estimate = GuideEstimate(now: now, bytesByHour: Dictionary(uniqueKeysWithValues: (-48..<48).map { ($0, 100) }))
        let full = DateInterval(start: now.addingTimeInterval(-48 * hour), end: now.addingTimeInterval(48 * hour))
        let none = estimate.window(budget: 0, full: full)
        XCTAssertEqual(none.window, DateInterval(start: now, end: now.addingTimeInterval(2 * hour)), "what is on and next, whatever the budget")
        XCTAssertEqual(none.bytes, 300, "with what began in the hour before")
        let some = estimate.window(budget: 600, full: full)
        XCTAssertEqual(some.window, DateInterval(start: now.addingTimeInterval(-hour), end: now.addingTimeInterval(4 * hour)), "two hours ahead for one back")
        XCTAssertEqual(some.bytes, 600)
        XCTAssertEqual(estimate.window(budget: .max, full: full).window, full, "everything fits")
        let short = DateInterval(start: now.addingTimeInterval(-hour / 2), end: now.addingTimeInterval(1.5 * hour))
        XCTAssertEqual(estimate.window(budget: .max, full: short).window, short, "never wider than the window asked for")
    }

    /// Writes a guide of `channels` channels with one-hour programmes from `hours` hours before `now` to as many after.
    private func writeGuide(_ name: String, channels: Range<Int>, hours: Int, description: Int = 200) throws -> URL {
        let file = folder.appendingPathComponent(name)
        XCTAssertTrue(FileManager.default.createFile(atPath: file.path, contents: nil))
        let handle = try FileHandle(forWritingTo: file)
        defer { try? handle.close() }
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n"
        let text = String(repeating: "A programme description. ", count: description / 25 + 1).prefix(description)
        let first = Date.utc(2025, 10, 9, 20).addingTimeInterval(Double(-hours) * 3_600)
        for channel in channels {
            xml += "<channel id=\"ch\(channel).example\"><display-name>Channel \(channel)</display-name></channel>\n"
        }
        for channel in channels {
            for index in 0..<(2 * hours) {
                let start = first.addingTimeInterval(Double(index) * 3_600)
                let stamp = CatchupURLBuilder.format(start, pattern: "YmdHMS") + " +0000"
                let stop = CatchupURLBuilder.format(start.addingTimeInterval(3_600), pattern: "YmdHMS") + " +0000"
                xml += "<programme start=\"\(stamp)\" stop=\"\(stop)\" channel=\"ch\(channel).example\"><title>Programme \(index) on \(channel)</title>"
                xml += "<desc>\(text)</desc></programme>\n"
            }
            if xml.utf8.count > 1 << 20 {
                try handle.write(contentsOf: Data(xml.utf8))
                xml = ""
            }
        }
        try handle.write(contentsOf: Data((xml + "</tv>\n").utf8))
        return file
    }

    private func channels(_ range: Range<Int>) -> PlaylistContent {
        PlaylistContent(channels: range.map {
            Channel(id: "c\($0)", name: "Channel \($0)", categoryID: "live-1", streamURL: URL(string: "http://stream.example.com/\($0)")!, epgID: "ch\($0).example")
        })
    }

    private func load(_ guides: [URL], content: PlaylistContent, budget: Int?) async -> (data: EPGData, failures: [URL: Error]) {
        let urls = guides.map { URL(string: "http://epg.example.com/\($0.lastPathComponent)")! }
        let http = FileHTTPClient(Dictionary(uniqueKeysWithValues: zip(urls.map(\.absoluteString), guides)))
        let playlist = Playlist(name: "G", source: .remote(URL(string: "http://provider.example.com/list.m3u")!), epgURLs: urls, useProviderEPG: false)
        let window = DateInterval(start: now.addingTimeInterval(-7 * 86_400), end: now.addingTimeInterval(3 * 86_400))
        return await PlaylistLoader(http: http, localFileURL: { URL(fileURLWithPath: $0) })
            .loadEPG(for: playlist, content: content, window: window, budget: budget, now: now)
    }

    private func isOn(_ list: [EPGProgram]?) -> Bool { list?.contains { $0.isLive(at: now) } ?? false }

    /// A guide whose programmes would take more than the budget keeps a window around now that fits it: what is on and
    /// next on every channel first. One that fits, or no budget, keeps everything.
    func testABudgetKeepsWhatIsOnAroundNow() async throws {
        let guide = try writeGuide("guide.xml", channels: 0..<40, hours: 48)
        let content = channels(0..<40)
        let whole = await load([guide], content: content, budget: nil)
        XCTAssertEqual(whole.data.programCount, 40 * 96)
        let wholeBytes = GuideEstimate.bytes(of: whole.data)
        let fitting = await load([try gzip(guide)], content: content, budget: wholeBytes)
        XCTAssertEqual(fitting.data.programs, whole.data.programs, "a budget it fits changes nothing")

        let budget = wholeBytes / 4
        let narrowed = await load([guide], content: content, budget: budget)
        XCTAssertTrue(narrowed.failures.isEmpty, "\(narrowed.failures)")
        XCTAssertEqual(narrowed.data.programs.count, 40)
        XCTAssertTrue(narrowed.data.programs.values.allSatisfy(isOn), "what is on now, on every channel")
        XCTAssertLessThanOrEqual(GuideEstimate.bytes(of: narrowed.data), budget)
        XCTAssertGreaterThan(GuideEstimate.bytes(of: narrowed.data), budget * 9 / 10, "as much as fits")
        let starts = narrowed.data.programs.values.flatMap { $0.map(\.start) }
        let ahead = starts.filter { $0 > now }.count
        let back = starts.filter { $0 <= now }.count
        XCTAssertEqual(Double(ahead) / Double(back), 2, accuracy: 0.3, "two hours ahead for each hour back")
        XCTAssertEqual(narrowed.data.programs["ch0.example"], whole.data.programs["ch0.example"]?.filter { starts.contains($0.start) })

        let tiny = await load([guide], content: content, budget: 0)
        XCTAssertTrue(tiny.data.programs.values.allSatisfy { isOn($0) && $0.count <= 3 }, "at least what is on and next")
    }

    /// Guides after the first share what it left: the second is narrowed to fit, each keeps what is on.
    func testGuidesShareTheBudget() async throws {
        let first = try writeGuide("first.xml", channels: 0..<20, hours: 24)
        let second = try writeGuide("second.xml", channels: 20..<40, hours: 24)
        let content = channels(0..<40)
        let firstBytes = GuideEstimate.bytes(of: await load([first], content: content, budget: nil).data)
        let budget = firstBytes + firstBytes / 3
        let shared = await load([first, second], content: content, budget: budget)
        XCTAssertEqual(shared.data.programs.count, 40)
        XCTAssertEqual((0..<20).map { shared.data.programs["ch\($0).example"]?.count }, Array(repeating: 48, count: 20), "the first fits whole")
        XCTAssertTrue((20..<40).allSatisfy { (shared.data.programs["ch\($0).example"]?.count ?? 0) < 48 }, "the second is narrowed")
        XCTAssertTrue(shared.data.programs.values.allSatisfy(isOn))
        XCTAssertLessThanOrEqual(GuideEstimate.bytes(of: shared.data), budget)
    }

    /// A large guide takes what it keeps and little more, and within a budget about the budget: measured with the peak
    /// resident size (Linux). Before guides were read from disk a piece at a time, a guide took about 3.8 times its size
    /// at its peak (578 MB for 151 MB, kept 182 MB), whatever the device could give.
    func testALargeGuideLoadsInBoundedMemory() async throws {
        #if os(Linux)
        let guide = try writeGuide("large.xml", channels: 0..<600, hours: 72, description: 350)
        let size = try XCTUnwrap(guide.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertGreaterThan(size, 32 << 20)
        let content = channels(0..<600)
        let megabyte = 1 << 20

        try ResidentMemory.resetPeak()
        let before = ResidentMemory.current
        let whole = await load([guide], content: content, budget: nil)
        let wholeGrowth = ResidentMemory.peak - before
        let kept = GuideEstimate.bytes(of: whole.data)
        XCTAssertEqual(whole.data.programCount, 600 * 144)
        print("A \(size / megabyte) MB guide: \(wholeGrowth / megabyte) MB at its peak, keeping \(kept / megabyte) MB")
        XCTAssertLessThan(wholeGrowth, kept + kept / 4 + 16 * megabyte, "what it keeps, not the guide's size again")

        let budget = 16 * megabyte
        try ResidentMemory.resetPeak()
        let start = ResidentMemory.current
        let narrowed = await load([guide], content: content, budget: budget)
        let narrowedGrowth = ResidentMemory.peak - start
        print("Within \(budget / megabyte) MB: \(narrowedGrowth / megabyte) MB at its peak, keeping \(GuideEstimate.bytes(of: narrowed.data) / megabyte) MB")
        XCTAssertTrue(narrowed.data.programs.values.allSatisfy(isOn))
        XCTAssertLessThan(narrowedGrowth, 2 * budget + 16 * megabyte)
        #else
        throw XCTSkip("Measured with /proc/self, on Linux.")
        #endif
    }

    /// The file as gzip, in stored (uncompressed) blocks: a gzip guide is sized and read from one inflated copy.
    private func gzip(_ file: URL) throws -> URL {
        let data = try Data(contentsOf: file)
        var crc = UInt32.max
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = crc & 1 == 1 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1 }
        }
        crc = ~crc
        var gzip = Data([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 255])
        var offset = 0
        repeat {
            let length = min(65_535, data.count - offset)
            let last: UInt8 = offset + length == data.count ? 1 : 0
            gzip += [last, UInt8(length & 0xff), UInt8(length >> 8), UInt8(~length & 0xff), UInt8((~length >> 8) & 0xff)]
            gzip += data[offset..<(offset + length)]
            offset += length
        } while offset < data.count
        for value in [crc, UInt32(truncatingIfNeeded: data.count)] {
            gzip += [0, 8, 16, 24].map { UInt8((value >> $0) & 0xff) }
        }
        let compressed = folder.appendingPathComponent(file.lastPathComponent + ".gz")
        try gzip.write(to: compressed)
        XCTAssertEqual(try gzip.gunzipped(), data)
        return compressed
    }
}

import XCTest
@testable import IPTVCore

/// Xtream lists read from a file an element at a time give exactly what decoding them whole gave: the same elements
/// kept and dropped, the same answers refused, wherever the pieces of the file end.
final class JSONListFileTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("ondo-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
    }

    /// How the list was read before: held whole, then decoded whole.
    private func wholeDecoding<T: Decodable>(_ type: T.Type, _ data: Data) -> String {
        guard !data.isEmpty else { return "empty" }
        do {
            return "\(try JSONDecoder().decode(LossyArray<T>.self, from: data).elements.map { String(describing: $0) })"
        } catch {
            return "invalid"
        }
    }

    private var fastPath = 0

    /// Read from a file: an element at a time when it is a plain list, in pieces of `size` bytes.
    private func fromFile<T: Decodable>(_ type: T.Type, _ data: Data, pieceSize size: Int?) throws -> String {
        let file = folder.appendingPathComponent("list.json")
        try data.write(to: file)
        do {
            if let size {
                guard !data.isEmpty else { return "empty" }
                if let elements = try JSONListFile.elements(T.self, in: file, pieceSize: size, { String(describing: $0) }) {
                    fastPath += 1
                    return "\(elements)"
                }
                return wholeDecoding(T.self, data)
            }
            return "\(try XtreamClient.decodeList(T.self, in: file) { { String(describing: $0) } })"
        } catch HTTPClientError.emptyResponse {
            return "empty"
        } catch XtreamError.invalidResponse {
            return "invalid"
        }
    }

    private func assertSame<T: Decodable>(_ type: T.Type, _ inputs: [Data], file: StaticString = #filePath, line: UInt = #line) throws {
        for (index, input) in inputs.enumerated() {
            let expected = wholeDecoding(T.self, input)
            for size in [nil, 1, 7, 64, 1 << 16] {
                XCTAssertEqual(try fromFile(T.self, input, pieceSize: size), expected,
                               "input \(index) \(String(decoding: input.prefix(60), as: UTF8.self).debugDescription), pieces of \(size ?? 0)",
                               file: file, line: line)
            }
        }
    }

    /// A deterministic generator, so a failure can be replayed.
    private struct SplitMix: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    func testOddAnswersAreReadAsBefore() throws {
        let movie = #"{"stream_id":1,"name":"One","plot":"A \"quoted\" plot, with [brackets], {braces} and \\ a backslash"}"#
        let other = #"{"stream_id":"2","name":"Two ] , [","rating":"7,5"}"#
        let texts = [
            "[\(movie),\(other)]", " \n\t[ \(movie) ,\r\n \(other) ] \n", "[]", "[ ]", "{}", "null", "false", "\"text\"", "   ",
            "[\(movie),]", "[,\(movie)]", "[\(movie),,\(other)]", "[\(movie)]]", "[\(movie)] x", "[\(movie)", "[\(movie),",
            "{\"0\":\(movie),\"1\":\(other)}", "[1,\"a\",null,true,[],{},\(other)]", "[{\"stream_id\":1e999,\"name\":\"Big\"},\(other)]",
            "[{\"stream_id\":3,\"name\":\"Duplicated\",\"name\":\"Twice\"}]", "[{\"stream_id\":4}]", "[\(movie)\u{0C},\(other)]",
            "[{\"stream_id\":5,\"name\":\"Raw\nline\"},\(other)]", "[{'stream_id':6,'name':'Quoted'}]", "[\(movie)/*note*/]",
            "[" + String(repeating: "[", count: 40) + String(repeating: "]", count: 40) + ",\(other)]",
            "[" + String(repeating: "[", count: 600) + String(repeating: "]", count: 600) + ",\(other)]",
            "[{\"stream_id\":7,\"name\":\"Escaped \\u005d and \\\"\\\\\"},\(other)]",
        ]
        var inputs = texts.map { Data($0.utf8) }
        inputs.append(Data([0xEF, 0xBB, 0xBF]) + Data("[\(movie)]".utf8))
        inputs.append("[\(movie),\(other)]".data(using: .utf16LittleEndian)!)
        inputs.append("[\(movie),\(other)]".data(using: .utf16)!)
        inputs.append(Data("[\(movie),\(other)]".utf8) + Data([0]))
        inputs.append(Data(#"[{"stream_id":8,"name":"Nul "#.utf8) + Data([0]) + Data(#""}]"#.utf8))
        try assertSame(XtreamVODStreamDTO.self, inputs)
        XCTAssertGreaterThan(fastPath, 5 * 6, "plain lists are read an element at a time")
    }

    /// The fixtures, cut short anywhere and damaged anywhere with the characters that matter to JSON.
    func testDamagedListsAreReadAsBefore() throws {
        var generator = SplitMix(state: 20_251_009)
        // No backslash: JSONDecoder on Linux stops the process on a key with a bad escape, whole or element by element.
        let damage = Array("\",[]{}: x0".utf8)
        for name in ["xtream_vod_streams.json", "xtream_live_streams.json", "xtream_series.json", "xtream_live_categories.json"] {
            let list = try Fixture.data(name)
            var inputs = stride(from: 0, through: list.count, by: 5).map { list.prefix($0) }
            for _ in 0..<120 {
                var damaged = list
                for _ in 0..<Int.random(in: 1...3, using: &generator) {
                    damaged[Int.random(in: 0..<damaged.count, using: &generator)] = damage.randomElement(using: &generator)!
                }
                inputs.append(damaged)
            }
            switch name {
            case "xtream_vod_streams.json": try assertSame(XtreamVODStreamDTO.self, inputs)
            case "xtream_live_streams.json": try assertSame(XtreamLiveStreamDTO.self, inputs)
            case "xtream_series.json": try assertSame(XtreamSeriesDTO.self, inputs)
            default: try assertSame(XtreamCategoryDTO.self, inputs)
            }
        }
        XCTAssertGreaterThan(fastPath, 100)
    }

    /// A large list read in pieces that end inside its elements, strings and escapes.
    func testALongListReadsAsBefore() throws {
        let elements = (0..<2_000).map { index in
            #"{"stream_id":\#(index % 1_500),"name":"Film \#(index) \"\\[\#(index)]\" , {x}","rating_5based":\#(Double(index % 50) / 10),"#
                + #""plot":"\#(String(repeating: "é", count: index % 40)) said \"stop]\", then {left \\","year":"20\#(index % 30)"}"#
        }
        try assertSame(XtreamVODStreamDTO.self, [Data(("[" + elements.joined(separator: ",\n") + "]").utf8)])
        XCTAssertEqual(fastPath, 4, "read an element at a time in pieces of every size: strings, escapes and brackets in them followed")
    }

    /// A list found irregular only at its end (a trailing comma, a damaged last element) is decoded whole again: its
    /// films are kept or refused as before, none of them taken for one already listed.
    func testAListDecodedWholeAfterAllKeepsItsFilms() async throws {
        let films = (1...3).map { #"{"stream_id":\#($0),"name":"Film \#($0)"}"# }.joined(separator: ",")
        for text in ["[\(films),]", "[\(films),{\"stream_id\":4,\"name\":\"Damaged\"]", "[\(films),{\"stream_id\":1,\"name\":\"Again\"}] x", "[\(films)]"] {
            let data = Data(text.utf8)
            let http = MockHTTPClient()
            http.routes = ["api:get_vod_streams": data]
            let client = XtreamClient(credentials: XtreamCredentials(server: URL(string: "http://panel.example.com")!, username: "demo", password: "demo"), http: http)
            let expected: [String]?
            if let whole = try? JSONDecoder().decode(LossyArray<XtreamVODStreamDTO>.self, from: data).elements {
                var seen = Set<Int>()
                expected = whole.filter { seen.insert($0.streamID).inserted }.map(\.name)
            } else {
                expected = nil
            }
            let movies: [String]?
            do {
                movies = try await client.movies().map(\.name)
            } catch {
                XCTAssertEqual(error as? XtreamError, .invalidResponse, text)
                movies = nil
            }
            XCTAssertEqual(movies, expected, text)
        }
    }

    /// A large panel's list of films takes what its films take and little more: measured with the peak resident size
    /// (Linux). Held whole and decoded whole, 47 MB of JSON for 100,000 films took 159 MB beyond its data at the peak,
    /// for 123 MB of films.
    func testALargeListTakesWhatItKeeps() async throws {
        #if os(Linux)
        let file = folder.appendingPathComponent("get_vod_streams.json")
        XCTAssertTrue(FileManager.default.createFile(atPath: file.path, contents: nil))
        let handle = try FileHandle(forWritingTo: file)
        var text = "["
        for index in 0..<100_000 {
            text += index == 0 ? "" : ","
            text += #"{"num":\#(index + 1),"name":"Movie number \#(index) (20\#(10 + index % 15))","stream_type":"movie","stream_id":\#(100_000 + index),"#
            text += #""stream_icon":"http://img.example.com/posters/\#(index).jpg","rating":"\#(index % 10).\#(index % 7)","added":"\#(1_600_000_000 + index)","#
            text += #""category_id":"\#(index % 120)","container_extension":"mkv","plot":"A plot of some length for movie \#(index), told in a sentence or two."}"#
            if text.utf8.count > 1 << 20 {
                try handle.write(contentsOf: Data(text.utf8))
                text = ""
            }
        }
        try handle.write(contentsOf: Data((text + "]").utf8))
        try handle.close()
        let client = XtreamClient(credentials: XtreamCredentials(server: URL(string: "http://panel.example.com")!, username: "demo", password: "demo"),
                                  http: FileHTTPClient(["api:get_vod_streams": file]))
        try ResidentMemory.resetPeak()
        let before = ResidentMemory.current
        let movies = try await client.movies()
        let peak = ResidentMemory.peak - before
        let kept = ResidentMemory.current - before
        XCTAssertEqual(movies.count, 100_000)
        print("100,000 films: \(peak >> 20) MB at the peak, \(kept >> 20) MB kept")
        XCTAssertLessThan(peak, kept + (16 << 20), "the films, not the list's size again")
        #else
        throw XCTSkip("Measured with /proc/self, on Linux.")
        #endif
    }
}

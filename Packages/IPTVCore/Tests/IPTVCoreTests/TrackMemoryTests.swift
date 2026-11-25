import XCTest
@testable import IPTVCore

/// The tracks kept per film and series (`fixtures/track-memory`, the same cases as the Kotlin `TrackMemoryTest`).
final class TrackMemoryTests: XCTestCase {
    private struct Track: Decodable {
        var language: String?
        var label: String?
        var forced: Bool?
        var hearingImpaired: Bool?
        var codec: String?

        var stream: StreamTrack {
            StreamTrack(language: language, label: label, forced: forced ?? false, hearingImpaired: hearingImpaired ?? false, codec: codec)
        }
    }

    private struct Match: Decodable {
        var name: String
        var tracks: [Track]
        var pick: TrackPick
        var current: Int?
        var expected: Int?
    }

    private struct Plan: Decodable {
        struct Expected: Decodable {
            var audio: Int?
            var subtitles: Int?
        }
        var name: String
        var audio: [Track]
        var subtitles: [Track]
        var memory: TitleTracks?
        var defaults: TrackDefaults?
        var expected: Expected
    }

    private struct Remember: Decodable {
        var name: String
        var memories: [TitleTracks]
        var keys: [String]
        var audio: TrackPick?
        var subtitles: TrackPick?
        var subtitlesOff: Bool?
        var at: Int64
        var expected: [TitleTracks]
    }

    private struct Off: Decodable {
        var name: String
        var memory: TitleTracks?
        var defaults: TrackDefaults?
        var expected: Bool
    }

    private struct Cases: Decodable {
        var subtitlesOff: [Off]
        var languages: [[String?]]
        var matches: [Match]
        var plans: [Plan]
        var remember: [Remember]
        var titleKeys: [[String?]]
    }

    private func cases() throws -> Cases {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("fixtures/track-memory/cases.json")
        return try JSONDecoder().decode(Cases.self, from: Data(contentsOf: file))
    }

    func testLanguagesAreComparedAsBothCoresDo() throws {
        for pair in try cases().languages {
            XCTAssertEqual(TrackMemory.language(pair[0]), pair[1], "\(pair[0] ?? "nil")")
        }
    }

    func testAChoiceFindsItsTrackByLanguageAndKind() throws {
        for test in try cases().matches {
            XCTAssertEqual(TrackMemory.match(test.tracks.map(\.stream), test.pick, current: test.current), test.expected, test.name)
        }
    }

    func testAStreamStartsWithTheTitlesTracksThenTheProfilesDefaults() throws {
        for test in try cases().plans {
            let plan = TrackMemory.plan(audio: test.audio.map(\.stream), subtitles: test.subtitles.map(\.stream),
                                        memory: test.memory, defaults: test.defaults)
            XCTAssertEqual(plan, TrackPlan(audio: test.expected.audio, subtitles: test.expected.subtitles), test.name)
        }
    }

    func testAChoiceIsRememberedForItsTitle() throws {
        for test in try cases().remember {
            let result = TrackMemory.remember(test.memories, keys: test.keys, audio: test.audio, subtitles: test.subtitles,
                                              subtitlesOff: test.subtitlesOff ?? false, at: test.at)
            XCTAssertEqual(result, test.expected, test.name)
        }
    }

    func testSubtitlesAreOffOnlyWhenTheTitleOrTheProfileSaysSo() throws {
        for test in try cases().subtitlesOff {
            XCTAssertEqual(TrackMemory.subtitlesOff(memory: test.memory, defaults: test.defaults), test.expected, test.name)
        }
    }

    /// VLC's language names are read once: many track names cost no more than a few look-ups.
    func testVLCLanguageNamesAreReadOnce() {
        TrackMemory.prepareVLCNames()
        let start = Date()
        for n in 0..<2_000 { _ = TrackMemory.vlcLanguage("Track \(n) - [French]") }
        XCTAssertLessThan(Date().timeIntervalSince(start), 0.5)
        XCTAssertEqual(TrackMemory.vlcLanguage("Track 3 - [french]"), "fr")
    }

    func testASeriesKeepsItsTracksForAllItsEpisodes() throws {
        // Apple keys a title by `MediaReference.itemKey`, which is the series' for an episode: the rule is the Kotlin one.
        for pair in try cases().titleKeys {
            let parts = pair[0]?.split(separator: "|", omittingEmptySubsequences: false).map(String.init) ?? []
            let key = parts.count >= 3 && ["MOVIE", "SERIES"].contains(parts[1]) ? parts.prefix(3).joined(separator: "|") : nil
            XCTAssertEqual(key, pair[1], pair[0] ?? "nil")
        }
        let playlist = UUID()
        let episode = MediaReference(playlistID: playlist, type: .series, itemID: "s1", episodeID: "e2")
        XCTAssertEqual(episode.itemKey, MediaReference(playlistID: playlist, type: .series, itemID: "s1", episodeID: "e1").itemKey)
    }

    /// French sound and English subtitles chosen on episode 1: episode 2, whose tracks come in another order, starts with them.
    func testEpisodeTwoStartsWithWhatWasChosenOnEpisodeOne() {
        let key = "p|series|s1"
        let episode1Audio = [StreamTrack(language: "eng"), StreamTrack(language: "fre")]
        let episode1Subtitles = [StreamTrack(language: "fre"), StreamTrack(language: "eng")]
        var memories = TrackMemory.remember([], keys: [key], audio: TrackPick(episode1Audio[1]), at: 1)
        memories = TrackMemory.remember(memories, keys: [key], subtitles: TrackPick(episode1Subtitles[1]), at: 2)
        let plan = TrackMemory.plan(audio: [StreamTrack(language: "fr"), StreamTrack(language: "en"), StreamTrack(language: "de")],
                                    subtitles: [StreamTrack(language: "de"), StreamTrack(language: "en"), StreamTrack(language: "fr")],
                                    memory: TrackMemory.remembered(memories, keys: [key]), defaults: TrackDefaults(audio: "en"))
        XCTAssertEqual(plan, TrackPlan(audio: 0, subtitles: 1))
    }

    func testOnlyTheLatestTitlesAreKept() {
        var memories: [TitleTracks] = []
        for n in 0...TrackMemory.maxTitles {
            memories = TrackMemory.remember(memories, keys: ["p|movie|\(n)"], audio: TrackPick(language: "fr"), at: Int64(n))
        }
        XCTAssertEqual(memories.count, TrackMemory.maxTitles)
        XCTAssertEqual(memories.first?.key, "p|movie|\(TrackMemory.maxTitles)")
        XCTAssertNil(TrackMemory.remembered(memories, keys: ["p|movie|0"]))
    }

    /// Kept in the library document and read back; a library from before reads with none, and a damaged entry loses no other.
    func testTheLibraryKeepsTheTracksAndReadsOlderDocuments() throws {
        var library = UserLibrary()
        library.titleTracks = [TitleTracks(key: "p|series|s1", audio: TrackPick(language: "fr"),
                                           subtitles: TrackPick(language: "en", forced: true), at: 3)]
        library.trackDefaults = TrackDefaults(audio: "es")
        let read = try JSONDecoder().decode(UserLibrary.self, from: JSONEncoder().encode(library))
        XCTAssertEqual(read.titleTracks, library.titleTracks)
        XCTAssertEqual(read.trackDefaults, library.trackDefaults)

        let old = try JSONDecoder().decode(UserLibrary.self, from: Data(#"{"hiddenCategoryKeys":["p|c"]}"#.utf8))
        XCTAssertEqual(old.hiddenCategoryKeys, ["p|c"])
        XCTAssertEqual(old.titleTracks, [])
        XCTAssertNil(old.trackDefaults)

        let damaged = try JSONDecoder().decode(UserLibrary.self, from: Data(
            #"{"titleTracks":[{"audio":{"language":"fr"}},{"key":"p|movie|m1","subtitlesOff":true}]}"#.utf8))
        XCTAssertEqual(damaged.titleTracks, [TitleTracks(key: "p|movie|m1", subtitlesOff: true)])
    }

    func testVLCTrackNamesGiveTheirLanguage() {
        XCTAssertEqual(TrackMemory.vlcLanguage("Track 1 - [French]"), "fr")
        XCTAssertEqual(TrackMemory.vlcLanguage("English - [English]"), "en")
        XCTAssertEqual(TrackMemory.vlcLanguage("Piste 2 - [fre]"), "fr")
        XCTAssertNil(TrackMemory.vlcLanguage("Track 2"))
        XCTAssertNil(TrackMemory.vlcLanguage("Commentary - [Klingonish]"))
    }
}

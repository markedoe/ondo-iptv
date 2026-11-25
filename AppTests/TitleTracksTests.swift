import XCTest
import IPTVCore
@testable import Ondo

/// The sound and subtitles chosen for a film or a series, and each profile's languages (docs/TRACKS.md): kept in the
/// open profile's library, found again by the title's keys, and what the player starts each item with.
@MainActor
final class TitleTracksTests: XCTestCase {
    private var root: URL!
    private let playlist = UUID()

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("OndoTracks-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeModel(settings: AppSettings? = nil) async throws -> AppModel {
        if let settings {
            try FileStore(directory: root.appendingPathComponent("Support", isDirectory: true)).save(settings, name: "settings")
        }
        let model = AppModel(storageRoot: root, secrets: InMemorySecretStore(), reminderScheduler: InMemoryReminderScheduler())
        await model.bootstrap()
        return model
    }

    private func episode(_ id: String) -> PlaybackItem {
        PlaybackItem(reference: MediaReference(playlistID: playlist, type: .series, itemID: "s1", episodeID: id),
                     title: "Series", streamURL: URL(string: "http://127.0.0.1:9/\(id).mkv")!, isLive: false)
    }

    private var film: PlaybackItem {
        PlaybackItem(reference: MediaReference(playlistID: playlist, type: .movie, itemID: "m1"),
                     title: "Film", streamURL: URL(string: "http://127.0.0.1:9/m1.mp4")!, isLive: false)
    }

    func testTheTracksChosenOnEpisodeOneAreThoseOfEpisodeTwo() async throws {
        let model = try await makeModel()
        model.rememberTracks(for: episode("e1"), audio: TrackPick(language: "fr"))
        model.rememberTracks(for: episode("e1"), subtitles: TrackPick(language: "en"))
        let wanted = model.titleTracks(for: episode("e2"))
        XCTAssertEqual(wanted.memory?.audio?.language, "fr")
        XCTAssertEqual(wanted.memory?.subtitles?.language, "en")
        // Episode 2 lists its tracks in another order: it starts with French sound and English subtitles.
        let plan = TrackMemory.plan(audio: [StreamTrack(language: "en"), StreamTrack(language: "fr")],
                                    subtitles: [StreamTrack(language: "fr"), StreamTrack(language: "en")],
                                    memory: wanted.memory, defaults: wanted.defaults)
        XCTAssertEqual(plan, TrackPlan(audio: 1, subtitles: 1))
    }

    func testAFilmStartedAgainKeepsItsTracks() async throws {
        let model = try await makeModel()
        model.rememberTracks(for: film, audio: TrackPick(language: "fr"), subtitlesOff: true)
        var again = film
        again.startPosition = 0
        let wanted = model.titleTracks(for: again)
        XCTAssertEqual(wanted.memory?.audio?.language, "fr")
        XCTAssertEqual(wanted.memory?.subtitlesOff, true)
        // A channel keeps nothing.
        let channel = PlaybackItem(reference: MediaReference(playlistID: playlist, type: .live, itemID: "c1"),
                                   title: "News", streamURL: URL(string: "http://127.0.0.1:9/c1.m3u8")!, isLive: true)
        XCTAssertEqual(model.trackKeys(for: channel), [])
    }

    func testWithNothingChosenTheProfilesLanguagesApply() async throws {
        let model = try await makeModel()
        XCTAssertNil(model.titleTracks(for: film).memory)
        XCTAssertNil(model.titleTracks(for: film).defaults)
        model.setTrackDefaults(TrackDefaults(audio: "es", subtitles: "fr"))
        XCTAssertEqual(model.titleTracks(for: film).defaults, TrackDefaults(audio: "es", subtitles: "fr"))
        let plan = TrackMemory.plan(audio: [StreamTrack(language: "en"), StreamTrack(language: "spa")],
                                    subtitles: [StreamTrack(language: "fre")], memory: nil, defaults: model.titleTracks(for: film).defaults)
        XCTAssertEqual(plan, TrackPlan(audio: 1, subtitles: 0))
    }

    func testARememberedLanguageTheStreamLacksFallsBack() async throws {
        let model = try await makeModel()
        model.setTrackDefaults(TrackDefaults(audio: "de"))
        model.rememberTracks(for: film, audio: TrackPick(language: "it"))
        let wanted = model.titleTracks(for: film)
        let plan = TrackMemory.plan(audio: [StreamTrack(language: "en"), StreamTrack(language: "de")], subtitles: [],
                                    memory: wanted.memory, defaults: wanted.defaults)
        XCTAssertEqual(plan.audio, 1)
    }

    func testAnotherProfileDoesNotInheritTheChoicesNorTheLanguages() async throws {
        let model = try await makeModel()
        model.setTrackDefaults(TrackDefaults(audio: "fr"))
        model.rememberTracks(for: episode("e1"), audio: TrackPick(language: "fr"), subtitles: TrackPick(language: "en"))
        let main = model.activeProfile
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: false, pin: nil)
        model.switchProfile(to: guest)
        XCTAssertNil(model.titleTracks(for: episode("e2")).memory)
        XCTAssertNil(model.titleTracks(for: episode("e2")).defaults)
        XCTAssertEqual(model.trackDefaults, TrackDefaults())
        model.switchProfile(to: main)
        XCTAssertEqual(model.titleTracks(for: episode("e2")).memory?.audio?.language, "fr")
    }

    /// Saved with the profile's library: a new start of the app finds them.
    func testTheTracksChosenAreThereAfterARestart() async throws {
        let model = try await makeModel()
        model.setTrackDefaults(TrackDefaults(audio: "es"))
        model.rememberTracks(for: episode("e1"), audio: TrackPick(language: "fr"), subtitlesOff: true)
        model.flush()
        let again = try await makeModel()
        XCTAssertEqual(again.titleTracks(for: episode("e2")).memory?.audio?.language, "fr")
        XCTAssertEqual(again.titleTracks(for: episode("e2")).memory?.subtitlesOff, true)
        XCTAssertEqual(again.trackDefaults, TrackDefaults(audio: "es"))
    }

    /// A choice made after another profile opened during playback goes to the profile the playback began in.
    func testAChoiceGoesToTheProfileThePlaybackBelongsTo() async throws {
        let model = try await makeModel()
        let main = model.activeProfile
        model.recordStart(episode("e1"))
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: false, pin: nil)
        model.switchProfile(to: guest)
        model.rememberTracks(for: episode("e1"), audio: TrackPick(language: "fr"))
        XCTAssertEqual(model.titleTracks(for: episode("e1")).memory?.audio?.language, "fr", "read from the playback's profile")
        XCTAssertTrue(model.library.titleTracks.isEmpty, "not the guest's")
        model.switchProfile(to: main)
        XCTAssertEqual(model.library.titleTracks.first?.audio?.language, "fr")
    }

    /// The languages the player learned on the device before each profile had its own are the main profile's, until it sets some.
    func testTheLanguagesLearnedBeforeAreTheMainProfilesOnly() async throws {
        var settings = AppSettings()
        settings.preferredAudioLanguage = "fr"
        settings.preferredSubtitleLanguage = "en"
        settings.subtitlesEnabled = true
        let model = try await makeModel(settings: settings)
        XCTAssertEqual(model.trackDefaults, TrackDefaults(audio: "fr", subtitles: "en"))
        model.setTrackDefaults(TrackDefaults(audio: "de"))
        XCTAssertEqual(model.trackDefaults, TrackDefaults(audio: "de"))
        let guest = model.addProfile(name: "Guest", colorIndex: 1, symbol: "star.fill", isKids: false, pin: nil)
        model.switchProfile(to: guest)
        XCTAssertNil(model.titleTracks(for: film).defaults)
    }
}

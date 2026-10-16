import XCTest
@testable import IPTVCore

/// Freezes told apart: the data came in too slowly (network or provider), or it was on time and the picture still
/// stopped or lost frames (player or device).
final class PlaybackHealthTests: XCTestCase {
    /// A 3 Mb/s stream (375 kB per second of media), sampled every half second.
    private let bytesPerSecond = 375_000

    /// Plays `seconds` of wall clock; `moving(t)` says whether the picture moves at t, `receiving(t)` the share of
    /// the bitrate received at t.
    private func play(
        _ seconds: Double, moving: (Double) -> Bool = { _ in true }, receiving: (Double) -> Double = { _ in 1 },
        lost: (Double) -> Int = { _ in 0 }
    ) -> PlaybackHealth {
        var health = PlaybackHealth()
        var media = 0.0, received = 0.0, consumed = 0.0, shown = 0
        var clock = 0.0
        while clock <= seconds {
            if moving(clock) {
                media += 0.5
                consumed += Double(bytesPerSecond) / 2
                shown += 12
            }
            received += Double(bytesPerSecond) / 2 * receiving(clock)
            health.add(PlaybackSample(
                clock: clock, mediaTime: media, receivedBytes: Int(received), consumedBytes: Int(consumed),
                displayedPictures: shown, lostPictures: lost(clock), isPlaying: true
            ))
            clock += 0.5
        }
        return health
    }

    func testASmoothPlaybackHasNoFreeze() {
        let health = play(30)
        XCTAssertTrue(health.freezes.isEmpty)
        XCTAssertEqual(health.verdict, .smooth)
        XCTAssertEqual(health.contentRate ?? 0, Double(bytesPerSecond), accuracy: 1)
        XCTAssertEqual(health.receivedRate() ?? 0, Double(bytesPerSecond), accuracy: 1)
        XCTAssertEqual(health.consumedRate() ?? 0, Double(bytesPerSecond), accuracy: 1)
    }

    func testTheFirstSecondsAreStillBeingMeasured() {
        XCTAssertEqual(play(5).verdict, .measuring)
    }

    /// The provider sends 60 % of what the video needs: the picture stops every so often.
    func testFreezesWithTooLittleDataAreTheNetworks() {
        let health = play(40, moving: { t in !(10..<13).contains(t) && !(25..<29).contains(t) }, receiving: { t in t < 8 ? 1 : 0.6 })
        XCTAssertEqual(health.freezes.count, 2)
        XCTAssertEqual(health.freezes.map(\.duration), [3, 4])
        XCTAssertEqual(health.frozenSeconds, 7)
        XCTAssertEqual(health.verdict, .network)
    }

    /// The data keeps coming at the full rate, yet the picture stops: the player is to blame.
    func testFreezesWithDataOnTimeAreThePlayers() {
        let health = play(120, moving: { t in !(60..<62).contains(t) })
        XCTAssertEqual(health.freezes.count, 1)
        XCTAssertEqual(health.verdict, .player)
    }

    /// The provider sends slightly less than the video needs (95 %): the picture stops for a second every 20 s.
    func testASlowProviderIsTheNetwork() {
        let health = play(100, moving: { t in t.truncatingRemainder(dividingBy: 20) < 19 }, receiving: { _ in 0.95 })
        XCTAssertEqual(health.freezes.count, 5)
        XCTAssertEqual(health.verdict, .network)
    }

    /// libVLC as seen from the app: its position stands still while no data comes in and catches up when data
    /// arrives, while the picture goes on from a `buffer` of 2 s. `stalled(t)` says whether data comes in at t.
    private func vlc(_ seconds: Double, buffer: Double = 2, stalled: (Double) -> Bool) -> PlaybackHealth {
        var health = PlaybackHealth(buffer: buffer)
        var media = 0.0, received = 0.0, clock = 0.0
        while clock <= seconds {
            if !stalled(clock) {
                // Data arrived: everything since the last arrival, and the position catches up with it.
                received = clock * Double(bytesPerSecond)
                media = clock
            }
            health.add(PlaybackSample(
                clock: clock, mediaTime: media, receivedBytes: Int(received), consumedBytes: Int(received),
                displayedPictures: Int(media * 25), lostPictures: 0, isPlaying: true
            ))
            clock += 0.5
        }
        return health
    }

    /// A provider that sends in bursts every 2.5 s: libVLC's position moves in steps, but its 2 s buffer covers the
    /// gaps and the picture never stops (measured on screen, tools/playback-lab).
    func testBurstsCoveredByTheBufferAreSmooth() {
        let health = vlc(60) { t in t.truncatingRemainder(dividingBy: 2.5) != 0 }
        XCTAssertTrue(health.freezes.isEmpty)
        XCTAssertEqual(health.pace ?? 0, 1, accuracy: 0.05)
        XCTAssertEqual(health.contentRate ?? 0, Double(bytesPerSecond), accuracy: 1)
        XCTAssertEqual(health.verdict, .smooth)
    }

    /// A 5 s stall of the provider: the 2 s buffer covers the start, the picture stops for the rest.
    func testAStallLongerThanTheBufferFreezes() {
        let short = vlc(40) { t in (20..<25).contains(t) }
        XCTAssertEqual(short.freezes.map(\.duration), [3])
        XCTAssertEqual(short.freezes.first?.longestGap ?? 0, 5, accuracy: 0.01)
        XCTAssertEqual(short.verdict, .network)
        let covered = vlc(40, buffer: 5) { t in (20..<24.5).contains(t) }
        XCTAssertTrue(covered.freezes.isEmpty)
        XCTAssertEqual(covered.verdict, .smooth)
    }

    /// During a stall, the freeze shows (and the spinner with it) only once the buffer has run out.
    func testAStallFreezesOnlyOnceTheBufferIsEmpty() {
        var health = vlc(23) { t in t >= 20 }
        XCTAssertEqual(health.currentStall, 3, accuracy: 0.01)
        XCTAssertEqual(health.currentFreeze, 1, accuracy: 0.01)
        XCTAssertTrue(health.isFrozen)
        health = vlc(21.5) { t in t >= 20 }
        XCTAssertFalse(health.isFrozen)
        // A stall the buffer still covers changes nothing: neither the pace nor the verdict.
        XCTAssertEqual(health.pace ?? 0, 1, accuracy: 0.01)
        XCTAssertEqual(health.verdict, .smooth)
    }

    /// A 4K film at 12.2 Mb/s from a provider sending 9 Mb/s, with 5 s of buffer (a viewer's report): the data never
    /// stops, the player runs out and refills, and the picture stands still for the whole stall, not for what is
    /// left after the buffer. The spinner shows at once.
    func testARefillFreezesThePictureForTheWholeStall() {
        let bytesPerSecond = 1_525_000.0
        var health = PlaybackHealth(buffer: 5)
        var media = 0.0, received = 0.0, clock = 0.0
        func add() {
            health.add(PlaybackSample(
                clock: clock, mediaTime: media, receivedBytes: Int(received), consumedBytes: Int(received),
                displayedPictures: Int(media * 25), lostPictures: 0, isPlaying: true
            ))
            clock += 0.5
        }
        for _ in 0..<60 {
            media += 0.5
            received += bytesPerSecond / 2
            add()
        }
        // The reserve is empty: 6.5 s still while 9 Mb/s keep arriving.
        for _ in 0..<13 {
            received += 1_125_000.0 / 2
            add()
        }
        XCTAssertEqual(health.currentFreeze, 6, accuracy: 0.01, "frozen from the first still sample")
        XCTAssertTrue(health.isFrozen)
        media += 0.5
        received += bytesPerSecond / 2
        add()
        XCTAssertEqual(health.freezes.map(\.duration), [6.5])
        XCTAssertEqual(health.freezes.first?.longestGap ?? 1, 0, accuracy: 0.01)
        XCTAssertEqual(health.verdict, .network)
    }

    /// The picture keeps moving but stutters (short stops, under `minimumFreeze`): data too slow for a film.
    func testStutterWithoutLongFreezesIsTheNetwork() {
        let health = play(60, moving: { t in t.truncatingRemainder(dividingBy: 5) != 0 }, receiving: { _ in 0.9 })
        XCTAssertTrue(health.freezes.isEmpty)
        XCTAssertEqual(health.pace ?? 0, 0.9, accuracy: 0.02)
        XCTAssertEqual(health.verdict, .network)
    }

    /// Waiting for the first picture (the player fills its buffer) and after a seek is not a freeze.
    func testTheWaitBeforeTheFirstPictureAndAfterASeekIsNotAFreeze() {
        var health = PlaybackHealth()
        var clock = 0.0
        func add(_ media: Double, _ received: Int) {
            health.add(PlaybackSample(
                clock: clock, mediaTime: media, receivedBytes: received, consumedBytes: received, displayedPictures: Int(media * 25),
                lostPictures: 0, isPlaying: true
            ))
            clock += 0.5
        }
        for step in 0..<10 { add(0, step * 1_000) }
        for step in 0..<20 { add(Double(step) / 2, 10_000 + step * 1_000) }
        health.restart()
        for step in 0..<6 { add(600, 30_000 + step * 1_000) }
        for step in 0..<10 { add(600 + Double(step) / 2, 36_000 + step * 1_000) }
        XCTAssertTrue(health.freezes.isEmpty)
        XCTAssertFalse(health.isFrozen)
        XCTAssertEqual(health.verdict, .smooth)
    }

    /// A 10 s skip: the position jumps at once, then waits for the picture. Neither the jump (it is no video shown)
    /// nor the wait (the picture had not moved yet) counts.
    func testASkipIsNeitherPlaybackNorAFreeze() {
        var health = PlaybackHealth(buffer: 1)
        var clock = 0.0
        var received = 0
        func add(_ media: Double) {
            received += 1_000
            health.add(PlaybackSample(
                clock: clock, mediaTime: media, receivedBytes: received, consumedBytes: received, displayedPictures: Int(media * 25),
                lostPictures: 0, isPlaying: true
            ))
            clock += 0.5
        }
        for step in 0..<20 { add(Double(step) / 2) }
        health.restart()
        for _ in 0..<8 { add(19.5) }
        for step in 1...20 { add(19.5 + Double(step) / 2) }
        XCTAssertTrue(health.freezes.isEmpty)
        XCTAssertFalse(health.isFrozen)
        XCTAssertEqual(health.pace ?? 0, 1, accuracy: 0.05, "the jump is not counted as video shown")
        XCTAssertEqual(health.verdict, .smooth)
    }

    /// A provider sending 70 % of what the film needs: VLC loses pictures too as it runs out (tools/playback-lab, an
    /// MKV at 70 %: 4 % lost). With the data short, they are the data's.
    func testPicturesLostWhileTheDataIsShortAreTheNetworks() {
        let health = play(60, moving: { t in t.truncatingRemainder(dividingBy: 5) >= 1.5 }, receiving: { _ in 0.7 }, lost: { t in Int(t) })
        XCTAssertFalse(health.freezes.isEmpty)
        XCTAssertEqual(health.verdict, .network)
    }

    /// Pictures dropped (the device cannot decode in time) without any freeze.
    func testLostPicturesAreThePlayers() {
        let health = play(30, lost: { t in Int(t * 2) })
        XCTAssertTrue(health.freezes.isEmpty)
        XCTAssertEqual(health.verdict, .player)
    }

    /// A freeze still going on counts, and short hiccups (under 0.7 s) are not freezes.
    func testAFreezeInProgressAndShortHiccups() {
        let ongoing = play(30, moving: { t in t < 24 }, receiving: { t in t < 20 ? 1 : 0 })
        XCTAssertTrue(ongoing.isFrozen)
        XCTAssertEqual(ongoing.currentFreeze, 6, accuracy: 0.01)
        XCTAssertEqual(ongoing.verdict, .network)
        let hiccup = play(30, moving: { t in t != 12 })
        XCTAssertTrue(hiccup.freezes.isEmpty)
    }

    /// A reconnection starts the counters again: the rates do not go negative.
    func testAReconnectionStartsOver() {
        var health = play(20)
        health.add(PlaybackSample(clock: 21, mediaTime: 0, receivedBytes: 1_000, consumedBytes: 0, displayedPictures: 0, lostPictures: 0, isPlaying: true))
        XCTAssertGreaterThanOrEqual(health.receivedRate() ?? 0, 0)
        XCTAssertEqual(health.verdict, .measuring)
    }

    /// libVLC's counters are 32-bit: one that wraps (here the bytes handed to the decoders) starts over too.
    func testACounterThatWrapsStartsOver() {
        var health = play(20)
        health.add(PlaybackSample(
            clock: 21, mediaTime: 21, receivedBytes: 20_000_000, consumedBytes: -2_000_000, displayedPictures: 600, lostPictures: 0, isPlaying: true
        ))
        XCTAssertEqual(health.verdict, .measuring)
        XCTAssertNil(health.contentRate)
    }
}

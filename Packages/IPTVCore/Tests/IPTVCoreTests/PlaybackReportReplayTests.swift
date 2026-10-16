import XCTest
@testable import IPTVCore

/// A viewer's report replayed: a 4K film in MKV whose file carries 12.4 Mb/s while the provider sent 8 to 9 Mb/s, played
/// by VLC with 1 s of buffer. The data never stopped; VLC ran out, refilled with the position standing still, then
/// started the picture again at a key frame while the sound went on: the picture stood still for 6 and 8 s more with
/// the position moving. "Used" is lower than "received" throughout: VLC uses the tracks it plays only (the film's
/// other languages are received and dropped), which is no sign of data piling up.
final class PlaybackReportReplayTests: XCTestCase {
    /// Seconds since opening, position, received kB, used kB, pictures shown, lost: the report's samples.
    private static let samples = """
        53.8 388.4 47635 27122 278 3
        54.3 388.8 48251 27503 289 3
        54.8 388.8 48891 27903 289 3
        55.4 388.8 49449 28264 289 3
        55.9 388.8 49965 28583 289 3
        56.4 388.8 50541 28955 289 3
        56.9 388.8 51162 29353 289 3
        57.5 388.8 51806 29726 289 3
        58.0 389.1 52466 30145 289 3
        58.5 389.6 53024 30492 289 3
        59.1 390.1 53550 30831 289 3
        59.6 390.6 54129 31166 289 3
        60.1 391.2 54749 31554 289 3
        60.6 391.7 55393 31945 289 3
        61.1 392.2 55838 32205 289 3
        61.6 392.7 56318 32479 289 3
        62.2 393.3 56864 32855 289 3
        62.7 393.8 57412 33207 289 3
        63.2 394.2 57827 33456 289 3
        63.7 394.8 58401 33820 289 3
        64.2 395.4 58999 34167 298 3
        64.7 395.9 59466 34431 310 3
        65.3 396.4 59973 34684 323 3
        65.8 396.9 60318 34858 334 3
        66.3 397.4 60568 34981 347 3
        66.8 397.6 60857 35126 352 4
        67.3 397.6 61187 35291 352 4
        67.9 397.6 61504 35451 352 4
        68.4 397.6 61860 35623 352 4
        68.9 397.6 62245 35821 352 4
        69.4 397.6 62656 36029 352 4
        69.9 397.6 63087 36247 352 4
        70.4 397.6 63509 36471 354 4
        70.9 398.0 63918 36692 364 4
        71.5 398.5 64273 36885 372 4
        72.0 398.9 64654 37100 377 4
        72.5 399.4 65111 37349 382 4
        73.0 400.0 65576 37618 390 4
        73.5 400.5 66066 37878 404 4
        74.1 401.0 66549 38141 416 4
        74.6 401.5 67054 38403 429 4
        75.1 402.1 67586 38682 442 4
        75.6 402.6 68101 38944 454 4
        76.1 403.1 68534 39174 467 4
        76.6 403.6 69022 39441 479 4
        77.1 404.2 69462 39685 491 4
        77.7 404.6 70001 40019 503 4
        78.2 405.1 70518 40338 515 4
        78.7 405.7 71126 40757 528 4
        79.2 406.2 71689 41133 540 4
        79.7 406.7 72228 41511 553 4
        80.2 406.8 72588 41744 559 4
        80.7 406.8 73147 42174 559 4
        81.2 406.8 73631 42483 560 4
        81.7 406.8 74165 42852 560 4
        82.2 406.8 74644 43153 560 4
        82.8 406.8 75244 43560 560 4
        83.3 406.8 75862 43958 560 4
        83.8 406.9 76482 44352 597 4
        84.3 407.5 77087 44734 602 4
        84.8 408.0 77730 45140 615 4
        85.4 408.5 78377 45558 627 4
        85.9 409.0 79050 46002 640 4
        86.4 409.6 79613 46336 652 4
        87.0 410.1 80174 46732 665 4
        87.5 410.6 80775 47104 677 4
        88.0 411.1 81321 47467 690 4
        88.5 411.7 81830 47767 702 4
        89.0 412.2 82362 48182 715 4
        89.6 412.7 82920 48592 728 4
        90.1 413.2 83423 48959 740 4
        90.6 413.8 84005 49379 753 4
        91.1 414.1 84497 49718 762 4
        91.6 414.8 84984 50083 778 4
        92.1 415.3 85334 50339 790 4
        92.7 415.7 85700 50590 799 4
        93.2 415.7 86095 50896 799 4
        93.7 415.7 86510 51192 799 4
        94.2 415.7 86913 51475 799 4
        94.8 415.7 87412 51861 799 4
        95.3 415.7 87819 52133 799 4
        95.8 415.7 88333 52525 799 4
        96.3 415.7 88830 52881 799 4
        96.8 415.7 89437 53294 799 4
        97.3 415.7 90029 53738 799 4
        97.8 415.8 90642 54190 799 4
        98.4 416.3 91285 54653 799 4
        98.9 416.8 91932 55110 799 4
        99.4 417.3 92525 55528 799 4
        99.9 417.9 93102 55947 799 4
        100.5 418.4 93851 56508 799 4
        101.0 418.9 94404 56887 799 4
        101.5 419.4 94973 57316 799 4
        102.0 420.0 95538 57726 799 4
        102.5 420.5 96135 58175 799 4
        103.1 421.0 96738 58595 799 4
        103.6 421.5 97356 59014 799 4
        104.1 422.1 97993 59446 799 4
        104.6 422.6 98672 59970 799 4
        105.2 423.1 99237 60384 799 4
        105.7 423.5 99739 60766 799 4
        106.2 423.5 100318 61191 799 4
        106.7 423.5 101002 61703 800 4
        107.3 423.5 101704 62242 800 4
        107.8 423.5 102395 62760 800 4
        108.3 423.5 102984 63234 800 4
        108.8 423.5 103545 63675 800 4
        109.3 423.5 104144 64117 800 4
        109.8 423.5 104685 64536 800 4
        110.4 423.5 105125 64843 800 4
        110.9 424.0 105696 65275 852 4
        111.4 424.5 106237 65666 863 4
        111.9 425.0 106708 65996 876 4
        112.4 425.5 107340 66464 888 4
        112.9 426.1 107874 66823 900 4
        113.5 426.6 108555 67262 913 4
        114.0 427.1 109199 67680 926 4
        114.5 427.6 109902 68168 939 4
        115.1 428.2 110555 68569 951 4
        115.6 428.6 111125 68904 962 4
        """

    private func replay() -> PlaybackHealth {
        var health = PlaybackHealth(buffer: 1)
        for line in Self.samples.split(separator: "\n") {
            let values = line.split(separator: " ").compactMap { Double($0) }
            health.add(PlaybackSample(
                clock: values[0], mediaTime: values[1], receivedBytes: Int(values[2]) * 1000, consumedBytes: Int(values[3]) * 1000,
                displayedPictures: Int(values[4]), lostPictures: Int(values[5]), isPlaying: true
            ))
        }
        return health
    }

    func testThePictureStandingStillWhileTheSoundGoesOnIsAFreeze() {
        let health = replay()
        let picturesOnly = health.freezes.filter(\.picturesOnly)
        XCTAssertEqual(picturesOnly.map(\.start), [58.0, 97.8])
        // Until the pictures moved (64.2 s), or until the position stopped too (106.2 s).
        XCTAssertEqual(picturesOnly.map { ($0.duration * 10).rounded() / 10 }, [6.2, 8.4])
        XCTAssertEqual(health.frozenSeconds, 31.2, accuracy: 0.1, "the position's stalls alone came to 16.6 s")
        // The position's stalls and the picture's freezes follow each other without counting a moment twice.
        let ordered = health.freezes.sorted { $0.start < $1.start }
        for (previous, next) in zip(ordered, ordered.dropFirst()) {
            XCTAssertLessThanOrEqual(previous.start + previous.duration, next.start + 0.01)
        }
    }

    func testTheyAreTheDatasAndSoIsTheVerdict() throws {
        let health = replay()
        XCTAssertEqual(health.verdict, .network)
        let needed = try XCTUnwrap(health.contentRate)
        XCTAssertEqual(needed * 8 / 1_000_000, 12.4, accuracy: 0.6, "the file's rate, every track included")
        for freeze in health.freezes {
            guard case .slowData(let received, let rate?) = health.cause(of: freeze) else {
                return XCTFail("freeze at \(freeze.start): \(health.cause(of: freeze))")
            }
            XCTAssertLessThan(received, rate * PlaybackHealth.enoughData)
        }
        XCTAssertTrue(ProblemReport.describe(health.freezes.first { $0.picturesOnly }!, in: health).hasPrefix("picture only, sound went on; data too slow"))
    }

    func testTheShareOfTheDataUsedIsTheTracksPlayed() throws {
        let share = try XCTUnwrap(replay().usedShare)
        XCTAssertEqual(share, 0.66, accuracy: 0.05)
    }
}

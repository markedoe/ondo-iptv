import XCTest
@testable import IPTVCore

/// Android and Apple devices sync with each other (fixtures/sync/interop/README.md): for each scenario, an Android device
/// scripted with the Kotlin core (`SyncInteropTest`, android/core) and an iPhone scripted here with the Swift core each
/// wrote their snapshot as they send it (`android.json`, `apple.json`); the Android device answered the iPhone's session
/// with the Kotlin merge (`answer.json`) and the iPhone kept that answer merged with the Swift merge (`kept.json`).
///
/// This test makes the iPhone and what it kept again and finds them as committed (any change to the Swift core's format
/// or merge shows here), reads what the Kotlin core wrote, does the Android device's half of the session itself and finds
/// the answer the Kotlin core gave, and checks that both devices end with the same data and the same canonical digest.
/// `ONDO_WRITE_INTEROP=1` writes the Apple files instead (see the README for the order).
final class SyncInteropTests: XCTestCase {
    private var writing: Bool { ProcessInfo.processInfo.environment["ONDO_WRITE_INTEROP"] == "1" }

    private func root() throws -> URL {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("fixtures/sync/interop").path) {
            let parent = directory.deletingLastPathComponent()
            guard parent.path != directory.path else { throw XCTSkip("fixtures/sync/interop not found") }
            directory = parent
        }
        return directory.appendingPathComponent("fixtures/sync/interop")
    }

    func testProfilesSyncBetweenAndroidAndApple() throws { try check("profiles") }

    func testSharedListsSyncBetweenAndroidAndApple() throws { try check("shared-lists") }

    func testDeletionsSyncBetweenAndroidAndApple() throws { try check("deletions") }

    func testHiddenCategoriesSyncBetweenAndroidAndApple() throws { try check("hidden") }

    /// An iPhone shares a list; an older Android TV (which does not share lists) relays it to an iPad: the iPad keeps the
    /// list where it was, never loses it.
    func testAListSharedBehindAnOlderAndroidDeviceIsNotLost() throws {
        try checkOldRelay("old-relay-share", expectedOnIpad: InteropScenarios.sam)
    }

    /// An iPhone takes a shared list back; an older Android TV relays it to an iPad: the list stays with that profile.
    func testAListTakenBackBehindAnOlderAndroidDeviceStaysTakenBack() throws {
        try checkOldRelay("old-relay-take-back", expectedOnIpad: "main")
    }

    /// The older relay scenarios: `android.json` and `answer.json` are what a TV of the previous version keeps (no
    /// household, no `placedAt`; written by the Kotlin core), and `kept.json` is what an iPad that only meets that TV keeps
    /// of its answer.
    private func checkOldRelay(_ scenario: String, expectedOnIpad: String) throws {
        let directory = try root().appendingPathComponent(scenario)
        var apple = InteropScenarios.apple(scenario)
        apple.digest = SHA256.hex(try apple.contentForDigest())
        let appleFile = directory.appendingPathComponent("apple.json")
        if writing {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try apple.encoded().write(to: appleFile)
        }
        XCTAssertEqual(try read(appleFile), apple, "\(scenario): apple.json")
        if writing && !FileManager.default.fileExists(atPath: directory.appendingPathComponent("answer.json").path) { return }
        let tv = try read(directory.appendingPathComponent("android.json"))
        // The TV's answer, done here as the previous version does it: what the Kotlin core wrote is the same.
        var serverClock = SyncClock()
        let received = try SharedListsTests.asOlderDevice(serverClock.receive(apple, wall: InteropScenarios.sessionWall), knowsProfiles: true)
        let answer = try SharedListsTests.asOlderDevice(SyncMerge.merge(tv, received), knowsProfiles: true)
        let answerByKotlin = try read(directory.appendingPathComponent("answer.json"))
        XCTAssertEqual(content(answer), content(answerByKotlin), "\(scenario): answer.json")
        // The iPad, which only meets the TV, keeps its answer merged with its own data: the list is there.
        var script = InteropScenarios.Script()
        var ipad = InteropScenarios.base(scenario, &script)
        ipad.device = InteropScenarios.ipadDevice
        ipad.deviceName = "Bedroom iPad"
        var clientClock = SyncClock()
        var kept = SyncMerge.merge(ipad, clientClock.receive(answerByKotlin, wall: InteropScenarios.sessionWall))
        kept.digest = SHA256.hex(try kept.contentForDigest())
        let keptFile = directory.appendingPathComponent("kept.json")
        if writing { try kept.encoded().write(to: keptFile) }
        XCTAssertEqual(try read(keptFile), kept, "\(scenario): kept.json")
        let id = InteropScenarios.movieNight
        XCTAssertEqual(SharedListsTests.places(of: id, in: kept), [expectedOnIpad], "\(scenario): on the iPad")
        // And the iPhone, which keeps the TV's answer, still has it where it put it.
        var iphoneClock = SyncClock()
        let iphone = SyncMerge.merge(apple, iphoneClock.receive(answerByKotlin, wall: InteropScenarios.sessionWall))
        XCTAssertEqual(SharedListsTests.places(of: id, in: iphone), SharedListsTests.places(of: id, in: apple), "\(scenario): on the iPhone")
        let digests = ["apple": SHA256.hex(try apple.contentForDigest()), "kept": SHA256.hex(try kept.contentForDigest()),
                       "canonical": try Self.canonicalDigest(kept)]
        let digestFile = directory.appendingPathComponent("apple-digests.json")
        if writing {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try (encoder.encode(digests) + Data("\n".utf8)).write(to: digestFile)
        }
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: Data(contentsOf: digestFile)), digests, "\(scenario): apple-digests.json")
        if writing && !FileManager.default.fileExists(atPath: directory.appendingPathComponent("android-digests.json").path) { return }
        let kotlinDigests = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: directory.appendingPathComponent("android-digests.json")))
        XCTAssertEqual(kotlinDigests["canonical"], digests["canonical"], "\(scenario): the same canonical digest on both cores")
    }

    private func check(_ scenario: String) throws {
        let directory = try root().appendingPathComponent(scenario)
        var apple = InteropScenarios.apple(scenario)
        apple.digest = SHA256.hex(try apple.contentForDigest())
        let appleFile = directory.appendingPathComponent("apple.json")
        if writing {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try apple.encoded().write(to: appleFile)
        }
        // The iPhone as committed: this core writes the same snapshot (and reads it back the same).
        XCTAssertEqual(try read(appleFile), apple, "\(scenario): apple.json")
        // Writing: the Kotlin core's answer comes first (see the README).
        if writing && !FileManager.default.fileExists(atPath: directory.appendingPathComponent("answer.json").path) { return }
        let android = try read(directory.appendingPathComponent("android.json"))
        // The Android device's half, done here with the Swift core: the answer the Kotlin core gave is the same.
        var serverClock = SyncClock()
        let answer = SyncMerge.merge(android, serverClock.receive(apple, wall: InteropScenarios.sessionWall))
        let answerByKotlin = try read(directory.appendingPathComponent("answer.json"))
        XCTAssertEqual(content(answer), content(answerByKotlin), "\(scenario): answer.json")
        // The iPhone keeps the answer merged with its own data.
        var clientClock = SyncClock()
        var kept = SyncMerge.merge(apple, clientClock.receive(answerByKotlin, wall: InteropScenarios.sessionWall))
        kept.digest = SHA256.hex(try kept.contentForDigest())
        let keptFile = directory.appendingPathComponent("kept.json")
        if writing { try kept.encoded().write(to: keptFile) }
        XCTAssertEqual(try read(keptFile), kept, "\(scenario): kept.json")
        // Both devices end with the same data, whichever merges first.
        XCTAssertEqual(content(kept), content(answerByKotlin), "\(scenario): both devices alike")
        XCTAssertEqual(content(SyncMerge.merge(apple, android)), content(answerByKotlin), "\(scenario): either order")
        // Digests: each core's own (stable from one run to the next), and the canonical one, the same on both cores.
        let digests = [
            "apple": SHA256.hex(try apple.contentForDigest()),
            "kept": SHA256.hex(try kept.contentForDigest()),
            "canonical": try Self.canonicalDigest(kept),
        ]
        let digestFile = directory.appendingPathComponent("apple-digests.json")
        if writing {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try (encoder.encode(digests) + Data("\n".utf8)).write(to: digestFile)
        }
        XCTAssertEqual(try JSONDecoder().decode([String: String].self, from: Data(contentsOf: digestFile)), digests, "\(scenario): apple-digests.json")
        if writing && !FileManager.default.fileExists(atPath: directory.appendingPathComponent("android-digests.json").path) { return }
        let kotlinDigests = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: directory.appendingPathComponent("android-digests.json")))
        XCTAssertEqual(kotlinDigests["canonical"], digests["canonical"], "\(scenario): the same canonical digest on both cores")
        XCTAssertEqual(try Self.canonicalDigest(answerByKotlin), digests["canonical"], "\(scenario): canonical digest of the answer")
    }

    private func read(_ file: URL) throws -> SyncSnapshot {
        try XCTUnwrap(SyncSnapshot.decode(Data(contentsOf: file)), file.path)
    }

    /// What both devices must hold alike: the snapshot without who holds it, when, and its digest.
    private func content(_ snapshot: SyncSnapshot) -> SyncSnapshot {
        var copy = snapshot
        copy.device = ""
        copy.deviceName = nil
        copy.clock = nil
        copy.digest = nil
        return copy
    }

    // MARK: Canonical digest

    /// Any JSON value, read strictly (a boolean is never a number, as in Kotlin).
    indirect enum JSON: Decodable {
        case object([String: JSON])
        case array([JSON])
        case string(String)
        case bool(Bool)
        case integer(Int64)
        case number(Double)
        case null

        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if c.decodeNil() {
                self = .null
            } else if let value = try? c.decode(Bool.self) {
                self = .bool(value)
            } else if let value = try? c.decode(Int64.self) {
                self = .integer(value)
            } else if let value = try? c.decode(Double.self) {
                self = .number(value)
            } else if let value = try? c.decode(String.self) {
                self = .string(value)
            } else if let value = try? c.decode([JSON].self) {
                self = .array(value)
            } else {
                self = .object(try c.decode([String: JSON].self))
            }
        }

        var isEmpty: Bool {
            switch self {
            case .null: return true
            case .array(let values): return values.isEmpty
            case .object(let members): return members.isEmpty
            default: return false
            }
        }

        func write(to out: inout String) {
            switch self {
            case .object(let members):
                out += "{"
                let keys = members.filter { !$0.value.isEmpty }.keys.sorted { $0.isBefore($1) }
                for (index, key) in keys.enumerated() {
                    if index > 0 { out += "," }
                    Self.string(key, to: &out)
                    out += ":"
                    members[key]?.write(to: &out)
                }
                out += "}"
            case .array(let values):
                out += "["
                for (index, value) in values.enumerated() {
                    if index > 0 { out += "," }
                    value.write(to: &out)
                }
                out += "]"
            case .string(let text): Self.string(text, to: &out)
            case .bool(let value): out += value ? "true" : "false"
            case .integer(let value): out += String(value)
            case .number(let value):
                out += value == value.rounded() && abs(value) < 9.007199254740992e15 ? String(Int64(value)) : String(value)
            case .null: out += "null"
            }
        }

        static func string(_ text: String, to out: inout String) {
            out += "\""
            for unit in text.unicodeScalars {
                switch unit {
                case "\"": out += "\\\""
                case "\\": out += "\\\\"
                case _ where unit.value < 0x20: out += String(format: "\\u%04x", unit.value)
                default: out.unicodeScalars.append(unit)
                }
            }
            out += "\""
        }
    }

    /// The digest both cores give the same snapshot (fixtures/sync/interop/README.md): SHA-256 of its canonical JSON,
    /// written as each core writes it, then read back and written again with members sorted, empty ones and who sends it
    /// left out, whole numbers without a decimal point and strings escaped the same way.
    static func canonicalDigest(_ snapshot: SyncSnapshot) throws -> String {
        guard case .object(var members) = try JSONDecoder().decode(JSON.self, from: snapshot.encoded()) else { return "" }
        for key in ["device", "deviceName", "clock", "digest", "seen", "probe", "unchanged"] { members[key] = nil }
        var out = ""
        JSON.object(members).write(to: &out)
        return SHA256.hex(Data(out.utf8))
    }
}

/// SHA-256 (FIPS 180-4), for the tests on Linux, where CryptoKit is missing.
enum SHA256 {
    private static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func hex(_ data: Data) -> String {
        var h: [UInt32] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
        var bytes = [UInt8](data)
        let length = UInt64(bytes.count) * 8
        bytes.append(0x80)
        while bytes.count % 64 != 56 { bytes.append(0) }
        for shift in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: length >> UInt64(shift))) }
        func rotate(_ x: UInt32, _ n: UInt32) -> UInt32 { (x >> n) | (x << (32 - n)) }
        for chunk in stride(from: 0, to: bytes.count, by: 64) {
            var w = [UInt32](repeating: 0, count: 64)
            for i in 0..<16 {
                w[i] = (0..<4).reduce(UInt32(0)) { ($0 << 8) | UInt32(bytes[chunk + i * 4 + $1]) }
            }
            for i in 16..<64 {
                let s0 = rotate(w[i - 15], 7) ^ rotate(w[i - 15], 18) ^ (w[i - 15] >> 3)
                let s1 = rotate(w[i - 2], 17) ^ rotate(w[i - 2], 19) ^ (w[i - 2] >> 10)
                w[i] = w[i - 16] &+ s0 &+ w[i - 7] &+ s1
            }
            var (a, b, c, d, e, f, g, x) = (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7])
            for i in 0..<64 {
                let t1 = x &+ (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) &+ ((e & f) ^ (~e & g)) &+ k[i] &+ w[i]
                let t2 = (rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) &+ ((a & b) ^ (a & c) ^ (b & c))
                (x, g, f, e, d, c, b, a) = (g, f, e, d &+ t1, c, b, a, t1 &+ t2)
            }
            for (index, value) in [a, b, c, d, e, f, g, x].enumerated() { h[index] = h[index] &+ value }
        }
        return h.map { String(format: "%08x", $0) }.joined()
    }
}

/// The scenarios of the interop test, scripted the same way as the Kotlin `InteropScenarios` (android/core): what both
/// devices had in common after an earlier session, then what the iPhone changed on its own. Every time is given by a
/// `SyncClock` at a known wall time, as the app dates a change.
enum InteropScenarios {
    static let wall: Int64 = 1_760_000_000_000
    static let sessionWall = wall + 300_000
    static let appleDevice = "b0b0b0b0-0000-4000-8000-00000000000b"
    static let ipadDevice = "c0c0c0c0-0000-4000-8000-00000000000c"
    static let main = SyncProfile.main
    static let sam = "11111111-1111-4111-8111-111111111111"
    static let kids = "22222222-2222-4222-8222-222222222222"
    static let guest = "33333333-3333-4333-8333-333333333333"
    static let home = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    static let sports = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    static let homeAgain = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    static let movieNight = "c1c1c1c1-0000-4000-8000-000000000001"
    static let documentaries = "c2c2c2c2-0000-4000-8000-000000000002"
    static let roadTrip = "c3c3c3c3-0000-4000-8000-000000000003"
    static let cartoons = "c4c4c4c4-0000-4000-8000-000000000004"

    /// Dates changes as a device does: each at `max(wall clock, last + 1)`.
    struct Script {
        private var clock = SyncClock()

        /// The time of a change made `seconds` after `wall`.
        mutating func at(_ seconds: Int64) -> Int64 { clock.stamp(wall: wall + seconds * 1000) }
    }

    static func item(_ playlist: String, _ kind: String, _ id: String, _ title: String, _ time: Int64) -> SyncListItem {
        SyncListItem(playlist: playlist, kind: kind, item: id, title: title, addedAt: time)
    }

    static func list(_ id: String, _ name: String, _ items: [SyncListItem], _ time: Int64, placedAt: Int64? = nil) -> SyncList {
        SyncList(id: id, name: name, items: items, updatedAt: time, renamedAt: time, orderedAt: time, order: items.map(\.key), placedAt: placedAt)
    }

    /// What both devices had after their last session, for `scenario`.
    static func base(_ scenario: String, _ script: inout Script) -> SyncSnapshot {
        let t0 = script.at(0)
        let homePlaylist = SyncPlaylist(id: home, name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/home.m3u", addedAt: t0, updatedAt: t0)
        let profiles = [
            SyncProfile(id: sam, name: "Sam", colorIndex: 3, symbol: "star.fill", updatedAt: script.at(1)),
            SyncProfile(id: kids, name: "Kids", colorIndex: 4, symbol: "pawprint.fill", kids: true, updatedAt: script.at(2)),
        ]
        switch scenario {
        case "profiles":
            let guestProfile = SyncProfile(id: guest, name: "Guest", colorIndex: 6, symbol: "🎬", updatedAt: script.at(3))
            let favorite = SyncFavorite(playlist: home, kind: "movie", item: "m1", title: "Northern Lights", image: "https://example.com/m1.jpg", addedAt: script.at(10))
            let person = SyncPerson(name: "Ada Lovelace", addedAt: script.at(11))
            let rows = SyncRows(tab: SyncRows.movies, order: ["continue", "all"], hidden: [], updatedAt: script.at(12))
            let samFavorite = SyncFavorite(playlist: home, kind: "movie", item: "m2", title: "Glass City", addedAt: script.at(20))
            let samProgress = SyncProgress(playlist: home, kind: "series", item: "s1", episode: "e1", title: "Harbour Lights", subtitle: "S1 E1",
                                           position: 600.5, duration: 2400, watchedAt: script.at(21))
            let samRows = SyncRows(tab: SyncRows.movies, order: ["mylist", "continue", "all"], hidden: ["trending"], updatedAt: script.at(22))
            let t30 = script.at(30)
            let kidsList = list(cartoons, "Cartoons", [item(home, "series", "k1", "Paper Boats", t30)], t30)
            let guestFavorite = SyncFavorite(playlist: home, kind: "movie", item: "m3", title: "Quiet Valley", addedAt: script.at(40))
            return SyncSnapshot(
                device: "", playlists: [homePlaylist], favorites: [favorite], people: [person], rows: [rows], profiles: profiles + [guestProfile],
                profileData: [
                    SyncProfileData(profile: sam, favorites: [samFavorite], progress: [samProgress], rows: [samRows]),
                    SyncProfileData(profile: kids, lists: [kidsList]),
                    SyncProfileData(profile: guest, favorites: [guestFavorite]),
                ]
            )
        case "shared-lists":
            let t10 = script.at(10)
            let documentaryList = list(documentaries, "Documentaries", [item(home, "movie", "d1", "Deep Sea", t10)], t10)
            let first = item(home, "movie", "m1", "Northern Lights", script.at(20))
            let t21 = script.at(21)
            let movieList = list(movieNight, "Movie night", [first, item(home, "movie", "m2", "Glass City", t21)], t21)
            let r1 = item(home, "movie", "r1", "Open Road", script.at(40))
            let t41 = script.at(41)
            let roadList = list(roadTrip, "Road trip", [r1, item(home, "series", "r2", "Coastline", t41)], t41, placedAt: script.at(42))
            let t50 = script.at(50)
            let cartoonList = list(cartoons, "Cartoons", [item(home, "series", "k1", "Paper Boats", t50)], t50, placedAt: t50)
            return SyncSnapshot(
                device: "", playlists: [homePlaylist], lists: [documentaryList], profiles: profiles,
                profileData: [SyncProfileData(profile: sam, lists: [movieList])],
                household: SyncHousehold(lists: [roadList, cartoonList])
            )
        case "hidden":
            // Each profile's hidden categories, languages and styles.
            let drama = SyncHidden.forCategory(playlistID: home, categoryID: "movie-12", hiddenAt: script.at(10))
            let portuguese = SyncHidden(kind: SyncHidden.tag, value: "PT", hiddenAt: script.at(11))
            let manga = SyncHidden(kind: SyncHidden.word, value: "manga", hiddenAt: script.at(20))
            let horror = SyncHidden.forCategory(playlistID: home, categoryID: "movie-horror", hiddenAt: script.at(30))
            return SyncSnapshot(
                device: "", playlists: [homePlaylist], hiddenCategories: [drama, portuguese], profiles: profiles,
                profileData: [SyncProfileData(profile: sam, hiddenCategories: [manga]), SyncProfileData(profile: kids, hiddenCategories: [horror])]
            )
        case "old-relay-share", "old-relay-take-back":
            // Sam's list (before the iPhone shares it), or the same list shared (before the iPhone takes it back).
            let first = item(home, "movie", "m1", "Northern Lights", script.at(20))
            let t21 = script.at(21)
            let items = [first, item(home, "movie", "m2", "Glass City", t21)]
            if scenario == "old-relay-share" {
                return SyncSnapshot(device: "", playlists: [homePlaylist], profiles: profiles,
                                    profileData: [SyncProfileData(profile: sam, lists: [list(movieNight, "Movie night", items, t21)])])
            }
            return SyncSnapshot(device: "", playlists: [homePlaylist], profiles: profiles,
                                household: SyncHousehold(lists: [list(movieNight, "Movie night", items, t21, placedAt: script.at(22))]))
        default:
            let t5 = script.at(5)
            let sportsPlaylist = SyncPlaylist(id: sports, name: "Sports", kind: SyncPlaylist.xtream, url: "http://example.com:8080", username: "viewer",
                                              password: "secret", addedAt: t5, updatedAt: t5)
            let favorite = SyncFavorite(playlist: home, kind: "movie", item: "p1", title: "Northern Lights", addedAt: script.at(10))
            let progress = SyncProgress(playlist: sports, kind: "movie", item: "q1", title: "Final Whistle", position: 1200, duration: 5400, watchedAt: script.at(11))
            let channel = SyncFavorite(playlist: sports, kind: "live", item: "c7", title: "Stadium One", addedAt: script.at(20))
            return SyncSnapshot(
                device: "", playlists: [homePlaylist, sportsPlaylist], favorites: [favorite], progress: [progress], profiles: profiles,
                profileData: [SyncProfileData(profile: sam, favorites: [channel])]
            )
        }
    }

    /// The iPhone of `scenario`: the base, then what it changed on its own (between 100 and 199 seconds).
    static func apple(_ scenario: String) -> SyncSnapshot {
        var script = Script()
        var snapshot = base(scenario, &script)
        snapshot.device = appleDevice
        snapshot.deviceName = "Kitchen iPhone"
        switch scenario {
        case "profiles":
            // Sam takes a movie out of My List; the Guest profile adds one (before the Android device deletes it); the
            // Kids profile is renamed; Sam reorders the Movies tab; the main profile keeps a director.
            let samIndex = snapshot.profileData.firstIndex { $0.profile == sam } ?? 0
            let t105 = script.at(105)
            snapshot.profileData[samIndex].favorites.removeAll { $0.item == "m2" }
            snapshot.profileData[samIndex].deleted["favorite:\(home)|movie|m2"] = t105
            let guestIndex = snapshot.profileData.firstIndex { $0.profile == guest } ?? 0
            snapshot.profileData[guestIndex].favorites.append(SyncFavorite(playlist: home, kind: "movie", item: "m5", title: "Winter Coast", addedAt: script.at(108)))
            let t115 = script.at(115)
            snapshot.profiles = snapshot.profiles.map { profile in
                guard profile.id == kids else { return profile }
                var renamed = profile
                renamed.name = "Little ones"
                renamed.updatedAt = t115
                return renamed
            }
            snapshot.profileData[samIndex].rows = [SyncRows(tab: SyncRows.movies, order: ["all", "continue"], hidden: [], updatedAt: script.at(125))]
            snapshot.people.append(SyncPerson(name: "Bea Arthur", image: "https://example.com/bea.jpg", addedAt: script.at(135)))
        case "shared-lists":
            // Sam adds a movie to Movie night (not told yet that it was shared); the main profile shares Documentaries;
            // Road trip is renamed; a series is added to Cartoons (before the Android device deletes it for everyone).
            let t105 = script.at(105)
            snapshot.profileData[0].lists[0].items.append(item(home, "movie", "m3", "Silent Harbour", t105))
            snapshot.profileData[0].lists[0].updatedAt = t105
            let removedAt = script.at(108)
            snapshot = SyncSharing.share(snapshot, list: documentaries, from: main, removedAt: removedAt, placedAt: script.at(109))
            let t111 = script.at(111)
            if let index = snapshot.household?.lists.firstIndex(where: { $0.id == cartoons }) {
                snapshot.household?.lists[index].items.append(item(home, "series", "k2", "Rain Song", t111))
                snapshot.household?.lists[index].updatedAt = t111
            }
            let t112 = script.at(112)
            if let index = snapshot.household?.lists.firstIndex(where: { $0.id == roadTrip }) {
                snapshot.household?.lists[index].name = "Summer trip"
                snapshot.household?.lists[index].renamedAt = t112
                snapshot.household?.lists[index].updatedAt = t112
            }
        case "hidden":
            // The main profile shows Portuguese again; Sam hides a series category.
            let t105 = script.at(105)
            snapshot.hiddenCategories.removeAll { $0.kind == SyncHidden.tag && $0.value == "PT" }
            snapshot.deleted[SyncHidden.keyPrefix + "tag:PT"] = t105
            let samIndex = snapshot.profileData.firstIndex { $0.profile == sam } ?? 0
            snapshot.profileData[samIndex].hiddenCategories.append(
                SyncHidden.forCategory(playlistID: home, categoryID: "series-4", hiddenAt: script.at(115)))
        case "old-relay-share":
            snapshot = SyncSharing.share(snapshot, list: movieNight, from: sam, removedAt: script.at(100), placedAt: script.at(101))
        case "old-relay-take-back":
            snapshot = SyncSharing.unshare(snapshot, list: movieNight, to: main, removedAt: script.at(100), placedAt: script.at(101))
        default:
            // The same Home playlist added again (it becomes the one already there), with a movie of it in My List; a
            // channel of Sports kept in Sam's favourites after the Android device deleted Sports.
            let t102 = script.at(102)
            snapshot.playlists.append(SyncPlaylist(id: homeAgain, name: "Home (TV)", kind: SyncPlaylist.m3u, url: "http://EXAMPLE.com/home.m3u ",
                                                   addedAt: t102, updatedAt: t102))
            snapshot.favorites.append(SyncFavorite(playlist: homeAgain, kind: "movie", item: "r1", title: "River Bend", addedAt: script.at(103)))
            snapshot.profileData[0].favorites.append(SyncFavorite(playlist: sports, kind: "live", item: "c8", title: "Stadium Two", addedAt: script.at(104)))
        }
        snapshot.clock = script.at(199)
        return snapshot
    }
}

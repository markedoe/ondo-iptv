import XCTest
@testable import IPTVCore

/// The hybrid logical clock of sync (docs/SYNC.md, "Clocks") on devices whose clocks are wrong, with sessions whose
/// latency varies: no time is ever moved, nothing loops and nothing is lost. The same cases as the Kotlin
/// `SyncClockTest`.
final class SyncClockTests: XCTestCase {
    typealias Random = SyncFuzzTests.Random

    static let start: Int64 = 1_760_000_000_000
    static let minute: Int64 = 60_000
    static let hour: Int64 = 3_600_000
    static let day: Int64 = 86_400_000

    // MARK: The clock

    func testAStampIsLaterThanEverythingGivenOrSeen() {
        var clock = SyncClock()
        XCTAssertEqual(clock.stamp(wall: Self.start), Self.start)
        XCTAssertEqual(clock.stamp(wall: Self.start), Self.start + 1, "the same millisecond: one later")
        // A wall clock gone back (a box reset to 1970): still after what it gave.
        XCTAssertEqual(clock.stamp(wall: 5_000), Self.start + 2)
        // A time received, later than the clock: the next stamp follows it.
        clock.observe(Self.start + Self.hour, wall: Self.start)
        XCTAssertEqual(clock.stamp(wall: Self.start + 10), Self.start + Self.hour + 1)
        XCTAssertEqual(clock.now(wall: Self.start), Self.start + Self.hour + 1, "now never goes back")
        XCTAssertEqual(clock.now(wall: Self.start + 2 * Self.hour), Self.start + 2 * Self.hour)
    }

    func testATimeMoreThanADayAheadIsNotTakenIn() {
        var clock = SyncClock(last: Self.start)
        clock.observe(Self.start + Self.day + 1, wall: Self.start)
        XCTAssertEqual(clock.last, Self.start)
        clock.observe(Self.start + Self.day, wall: Self.start)
        XCTAssertEqual(clock.last, Self.start + Self.day, "a day ahead at most")
        // A clock that is not set (1970) has no idea of the time: it takes everything in.
        var unset = SyncClock()
        unset.observe(Self.start + 365 * Self.day, wall: 1_000)
        XCTAssertEqual(unset.last, Self.start + 365 * Self.day)
        // At the ends of the range: no overflow.
        var end = SyncClock(last: .max)
        XCTAssertEqual(end.stamp(wall: .max), .max)
        XCTAssertEqual(SyncClock.latestAccepted(wall: .max), .max)
    }

    func testTheSendersClockFieldIsIgnoredAndTimesAreNeverMoved() {
        let now = Self.start
        for skew in [2 * Self.hour, -2 * Self.hour, -now] {
            let sent = SyncSnapshot(
                device: "box",
                favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "Glass City", addedAt: now + skew - 60_000)],
                people: [SyncPerson(name: "Tom", addedAt: now + skew)],
                deleted: ["favorite:p|movie|m2": now + skew - 5],
                clock: now + skew
            )
            XCTAssertEqual(sent.withoutRecordsAhead(wall: now), sent, "skew \(skew)")
        }
    }

    func testWhatIsDatedMoreThanADayAheadOfTheReceiverIsLeftOut() {
        let now = Self.start
        let ahead = now + SyncClock.maxAheadMilliseconds + 1
        let near = now + SyncClock.maxAheadMilliseconds
        let snapshot = SyncSnapshot(
            device: "box",
            playlists: [SyncPlaylist(id: "p", name: "Home", kind: SyncPlaylist.m3u, url: "http://example.com/a.m3u", addedAt: 1, updatedAt: ahead),
                        SyncPlaylist(id: "q", name: "News", kind: SyncPlaylist.m3u, url: "http://example.com/b.m3u", addedAt: 1, updatedAt: near)],
            favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "Glass City", addedAt: ahead)],
            progress: [SyncProgress(playlist: "p", kind: "movie", item: "m1", title: "Glass City", position: 1, duration: 2, watchedAt: near)],
            lists: [SyncList(id: "l", name: "L", updatedAt: ahead),
                    SyncList(id: "k", name: "K", items: [SyncListItem(playlist: "p", kind: "movie", item: "m1", title: "T", addedAt: ahead)], updatedAt: near)],
            people: [SyncPerson(name: "Tom", addedAt: ahead), SyncPerson(name: "Ada", addedAt: now)],
            keys: [SyncKey(service: SyncKey.tmdb, value: "k", updatedAt: ahead)],
            deleted: ["favorite:p|movie|m2": ahead, "favorite:p|movie|m3": 5]
        )
        let received = snapshot.withoutRecordsAhead(wall: now)
        XCTAssertEqual(received.playlists.map(\.id), ["q"])
        XCTAssertEqual(received.progress.map(\.watchedAt), [near], "a day ahead at most: kept as it is")
        XCTAssertEqual(received.people.map(\.name), ["Ada"])
        XCTAssertEqual(received.lists.map(\.id), ["k"])
        XCTAssertEqual(received.lists.first?.items, [])
        XCTAssertTrue(received.favorites.isEmpty && received.keys.isEmpty)
        XCTAssertEqual(received.deleted, ["favorite:p|movie|m3": 5])
        XCTAssertEqual(snapshot.withoutRecordsAhead(wall: 1_000), snapshot, "a clock not set leaves nothing out")
        XCTAssertEqual(SyncSnapshot(device: "box", deleted: ["k": .max]).withoutRecordsAhead(wall: .max - 1).deleted, ["k": .max])
    }

    // MARK: Simulated devices

    /// A device: its wall clock (`offset` from the true time), its hybrid clock and its data.
    struct Device {
        let name: String
        var offset: Int64
        var clock = SyncClock()
        var data: SyncSnapshot

        init(_ name: String, offset: Int64) {
            self.name = name
            self.offset = offset
            data = SyncSnapshot(device: name)
        }

        func wall(_ now: Int64) -> Int64 { now + offset }
    }

    /// Devices that edit and sync, and what every edit gave: each time given, and each edit as a snapshot of its own.
    struct World {
        var devices: [Device]
        var now = SyncClockTests.start
        var random: Random
        var made: Set<Int64> = []
        var edits: [SyncSnapshot] = []
        /// The latest time of each record seen on each device (by device, then record): it never goes back.
        var stored: [[String: Int64]]
        /// Each removal of a record the device held: its key, its time, and the time of the record it removed.
        var removals: [(key: String, at: Int64, removed: Int64)] = []
        var positions: [[String: Double]]
        var lastWatcher: [String: Int] = [:]
        let items = ["m1", "m2", "m3", "m4"]

        init(_ devices: [Device], seed: UInt64) {
            self.devices = devices
            random = Random(state: seed)
            stored = Array(repeating: [:], count: devices.count)
            positions = Array(repeating: [:], count: devices.count)
        }

        /// The expected data: every edit merged.
        var expected: SyncSnapshot { edits.reduce(SyncSnapshot(device: "expected")) { SyncMerge.merge($0, $1) } }

        /// A change made on `index` now, dated by its clock and kept there.
        @discardableResult
        mutating func edit(_ index: Int, _ make: (Int64, SyncSnapshot) -> SyncSnapshot) -> Int64 {
            let stamp = devices[index].clock.stamp(wall: devices[index].wall(now))
            made.insert(stamp)
            let edit = make(stamp, devices[index].data)
            edits.append(edit)
            devices[index].data = SyncMerge.merge(devices[index].data, edit)
            check(index)
            // The change shows at once on the device where it is made: its time is later than all it holds.
            let held = Self.records(devices[index].data)
            for (key, time) in Self.records(edit) where time == stamp {
                XCTAssertTrue(held.contains { $0 == (key, time) }, "\(devices[index].name): its own change \(key) lost at once")
                if key.hasPrefix("deleted ") {
                    let removed = String(key.dropFirst("deleted ".count))
                    XCTAssertFalse(held.contains { $0.0 == removed }, "\(devices[index].name): \(removed) still there after its removal")
                }
            }
            return stamp
        }

        /// A random change on a random device: My List, a person, a list, or where a film was left.
        mutating func randomEdit() {
            let index = random.int(devices.count)
            let item = random.pick(items)
            switch random.int(5) {
            case 0:
                let key = "favorite:p|movie|" + item
                if let held = devices[index].data.favorites.first(where: { $0.key == key }) {
                    let at = edit(index) { stamp, _ in SyncSnapshot(device: "edit", deleted: [key: stamp]) }
                    removals.append((key, at, held.addedAt))
                } else {
                    let title = random.pick(["Glass City", "Quiet Water"])
                    edit(index) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: item, title: title, addedAt: stamp)]) }
                }
            case 1:
                let name = random.pick(["Ada", "Bea"])
                if let held = devices[index].data.people.first(where: { $0.name == name }) {
                    let at = edit(index) { stamp, _ in SyncSnapshot(device: "edit", deleted: ["person:" + name.lowercased(): stamp]) }
                    removals.append(("person:" + name.lowercased(), at, held.addedAt))
                } else {
                    edit(index) { stamp, _ in SyncSnapshot(device: "edit", people: [SyncPerson(name: name, addedAt: stamp)]) }
                }
            case 2:
                let rename = random.chance(30)
                let name = random.pick(["Weekend", "Kids"])
                edit(index) { stamp, data in
                    var list = data.lists.first { $0.id == "l" } ?? SyncList(id: "l", name: name, updatedAt: stamp, renamedAt: stamp, orderedAt: stamp)
                    list.updatedAt = stamp
                    var deleted: [String: Int64] = [:]
                    if rename {
                        list.name = name
                        list.renamedAt = stamp
                    } else if let at = list.items.firstIndex(where: { $0.item == item }) {
                        deleted[list.itemKey(list.items[at].key)] = stamp
                        list.items.remove(at: at)
                    } else {
                        list.items.append(SyncListItem(playlist: "p", kind: "movie", item: item, title: item, addedAt: stamp))
                    }
                    list.order = nil
                    return SyncSnapshot(device: "edit", lists: [list], deleted: deleted)
                }
            default:
                watch(index, item)
            }
        }

        /// The viewer goes on with `item` on `index`: that device first syncs with the one it was watched on last, as
        /// the app does at launch and every minute, so it carries on from there.
        mutating func watch(_ index: Int, _ item: String) {
            if let last = lastWatcher[item], last != index { session(client: index, server: last) }
            let key = "progress:p|movie|\(item)|"
            let position = (devices[index].data.progress.first { $0.key == key }?.position ?? 0) + Double(10 + random.int(300))
            edit(index) { stamp, _ in
                SyncSnapshot(device: "edit", progress: [SyncProgress(playlist: "p", kind: "movie", item: item, title: item, position: position,
                                                                     duration: 100_000, watchedAt: stamp)])
            }
            lastWatcher[item] = index
        }

        /// One session: the client's snapshot reaches the server after some latency, the server keeps the merge and
        /// answers with it, and the answer reaches the client after some more. Each side leaves out what is dated more
        /// than a day after its own clock, takes the rest into its clock, and merges.
        mutating func session(client: Int, server: Int, latency: ClosedRange<Int> = 20...500) {
            let offer = devices[client].data
            now += Int64(latency.lowerBound + random.int(latency.count))
            receive(offer, on: server)
            let answer = devices[server].data
            now += Int64(latency.lowerBound + random.int(latency.count))
            receive(answer, on: client)
        }

        mutating func receive(_ snapshot: SyncSnapshot, on index: Int) {
            let wall = devices[index].wall(now)
            let received = snapshot.withoutRecordsAhead(wall: wall)
            devices[index].clock.observe(received, wall: wall)
            devices[index].data = SyncMerge.merge(devices[index].data, received)
            check(index)
        }

        /// No time on the device is one an edit did not give, none goes back, and no position goes back.
        mutating func check(_ index: Int) {
            let data = devices[index].data
            for (key, time) in Self.records(data) {
                XCTAssertTrue(made.contains(time), "\(devices[index].name): \(key) at \(time), a time no edit gave")
                if let before = stored[index][key] { XCTAssertGreaterThanOrEqual(time, before, "\(devices[index].name): \(key) went back") }
                stored[index][key] = time
            }
            for progress in data.progress {
                if let before = positions[index][progress.key] {
                    XCTAssertGreaterThanOrEqual(progress.position, before, "\(devices[index].name): \(progress.key) went back")
                }
                positions[index][progress.key] = progress.position
            }
        }

        /// Each time of a snapshot, by what it dates.
        static func records(_ data: SyncSnapshot) -> [(String, Int64)] {
            var records: [(String, Int64)] = data.favorites.map { ($0.key, $0.addedAt) } + data.progress.map { ($0.key, $0.watchedAt) }
                + data.people.map { ($0.key, $0.addedAt) } + data.deleted.map { ("deleted " + $0.key, $0.value) }
            for list in data.lists {
                records += [(list.key, list.updatedAt), ("name " + list.key, list.renamedAt ?? list.updatedAt),
                            ("order " + list.key, list.orderedAt ?? list.updatedAt)]
                records += list.items.map { (list.itemKey($0.key), $0.addedAt ?? list.updatedAt) }
            }
            return records
        }

        func digest(_ index: Int) -> Data { Self.content(devices[index].data) }

        static func content(_ snapshot: SyncSnapshot) -> Data { (try? snapshot.contentForDigest()) ?? Data() }

        /// Sessions between every pair, both ways, until every device has everything.
        mutating func settle() {
            for _ in 0..<3 {
                for client in devices.indices {
                    for server in devices.indices where server != client {
                        now += SyncClockTests.minute
                        session(client: client, server: server)
                    }
                }
            }
        }
    }

    /// Three devices two hours apart (behind, on time, ahead) and a box whose clock reads 1970, 500 sessions with
    /// latency from 20 to 500 ms and random edits: no time moves, nothing is lost, no position goes back, a removal
    /// made after an add wins everywhere, and once the edits stop the data stays the same at every session.
    func testWrongClocksAndVaryingLatencyMoveNoTimeAndLoseNothing() {
        for seed in 0..<4 {
            var world = World([Device("behind", offset: -2 * Self.hour), Device("on-time", offset: 0),
                               Device("ahead", offset: 2 * Self.hour), Device("1970", offset: 1_000 - Self.start)], seed: UInt64(seed))
            for _ in 0..<500 {
                world.now += Self.minute - 5_000 + Int64(world.random.int(10_000))
                for _ in 0..<world.random.int(3) { world.randomEdit() }
                let client = world.random.int(4)
                let server = (client + 1 + world.random.int(3)) % 4
                world.session(client: client, server: server)
            }
            world.settle()
            let expected = World.content(world.expected)
            for index in world.devices.indices {
                XCTAssertEqual(world.digest(index), expected, "seed \(seed): \(world.devices[index].name) has every change, and only those")
            }
            // A removal made on a device that had the record (after its add, in causal order) is dated after it, and
            // the record it removed, or an older one, is back nowhere (only a later add brings the key back).
            for removal in world.removals {
                XCTAssertGreaterThan(removal.at, removal.removed, "seed \(seed): \(removal.key) removed before its add")
                for device in world.devices {
                    let back = World.records(device.data).contains { $0.0 == removal.key && $0.1 <= removal.at }
                    XCTAssertFalse(back, "seed \(seed): \(removal.key) is back on \(device.name)")
                }
            }
            // Quiet: sessions change nothing on either side.
            for _ in 0..<100 {
                world.now += Self.minute
                let client = world.random.int(4)
                let server = (client + 1 + world.random.int(3)) % 4
                let before = (world.digest(client), world.digest(server))
                world.session(client: client, server: server)
                XCTAssertEqual(world.digest(client), before.0, "seed \(seed): a quiet session changed the client")
                XCTAssertEqual(world.digest(server), before.1, "seed \(seed): a quiet session changed the server")
            }
            XCTAssertGreaterThan(world.edits.count, 300)
        }
    }

    /// A removal made on a box hours behind, after it saw the add made on a box hours ahead, wins on both: the box
    /// behind dates it after the add it saw.
    func testARemovalOnABoxBehindAfterAnAddOnABoxAheadWins() {
        var world = World([Device("ahead", offset: 2 * Self.hour), Device("behind", offset: -2 * Self.hour), Device("1970", offset: 1_000 - Self.start)], seed: 7)
        let key = "favorite:p|movie|m1"
        world.edit(0) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", addedAt: stamp)]) }
        for remover in [1, 2] {
            world.now += Self.minute
            world.session(client: remover, server: 0)
            XCTAssertTrue(world.devices[remover].data.favorites.contains { $0.key == key })
            world.edit(remover) { stamp, _ in SyncSnapshot(device: "edit", deleted: [key: stamp]) }
            world.settle()
            for device in world.devices { XCTAssertFalse(device.data.favorites.contains { $0.key == key }, "back on \(device.name)") }
            world.edit(0) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", addedAt: stamp)]) }
        }
    }

    /// The review's probe: a client 5 minutes and 200 ms ahead (and one 10 minutes behind), 500 sessions with latency
    /// from 20 to 500 ms: after the first session no time moves and nothing changes on either side.
    func testSkewedClientsWithVaryingLatencySettleAtOnce() {
        for offset in [5 * Self.minute + 200, -10 * Self.minute] {
            var world = World([Device("client", offset: offset), Device("server", offset: 0)], seed: 3)
            world.edit(0) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", addedAt: stamp)]) }
            world.edit(1) { stamp, _ in
                SyncSnapshot(device: "edit", progress: [SyncProgress(playlist: "p", kind: "movie", item: "m2", title: "T", position: 600, duration: 6_000, watchedAt: stamp)])
            }
            world.session(client: 0, server: 1)
            let settled = (world.digest(0), world.digest(1))
            for round in 0..<500 {
                world.now += Self.minute
                world.session(client: 0, server: 1)
                XCTAssertEqual(world.digest(0), settled.0, "offset \(offset), round \(round)")
                XCTAssertEqual(world.digest(1), settled.1, "offset \(offset), round \(round)")
            }
            XCTAssertEqual(world.digest(0), World.content(world.expected))
        }
    }

    /// A box a day and an hour ahead: the others leave out what it dates (and their clocks do not follow it), while
    /// it gets theirs. Once its clock is right again, its changes reach them as soon as their time is within a day.
    func testADeviceMoreThanADayAheadIsLeftOutUntilItsTimesComeWithin() {
        var world = World([Device("tv", offset: 0), Device("phone", offset: 0), Device("ahead", offset: Self.day + Self.hour)], seed: 11)
        world.edit(2) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m1", title: "T", addedAt: stamp)]) }
        world.edit(2) { stamp, _ in SyncSnapshot(device: "edit", people: [SyncPerson(name: "Ada", addedAt: stamp)]) }
        world.edit(0) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m2", title: "T", addedAt: stamp)]) }
        let aheadStamps = Set(World.records(world.devices[2].data).map(\.1))
        for _ in 0..<10 {
            world.now += Self.minute
            world.session(client: 2, server: 0)
            world.session(client: 1, server: 0)
            world.session(client: 2, server: 1)
        }
        for index in [0, 1] {
            let device = world.devices[index]
            XCTAssertEqual(device.data.favorites.map(\.item), ["m2"], device.name)
            XCTAssertTrue(device.data.people.isEmpty, device.name)
            XCTAssertLessThan(device.clock.last, Self.start + Self.hour, "\(device.name)'s clock did not follow")
            XCTAssertLessThan(device.clock.last, aheadStamps.min() ?? .max)
        }
        XCTAssertEqual(Set(world.devices[2].data.favorites.map(\.item)), ["m1", "m2"], "it gets the others' changes")

        // Its clock is put right; a change it makes then is still dated after its own.
        world.devices[2].offset = 0
        world.edit(2) { stamp, _ in SyncSnapshot(device: "edit", favorites: [SyncFavorite(playlist: "p", kind: "movie", item: "m3", title: "T", addedAt: stamp)]) }
        // Two hours of sessions every minute: past the first hour, its times are within a day of the others' clocks.
        for _ in 0..<120 {
            world.now += Self.minute
            world.session(client: 2, server: 0)
            world.session(client: 1, server: 0)
        }
        let expected = World.content(world.expected)
        for index in world.devices.indices {
            XCTAssertEqual(world.digest(index), expected, world.devices[index].name)
        }
        XCTAssertEqual(Set(world.devices[1].data.favorites.map(\.item)), ["m1", "m2", "m3"])
    }
}

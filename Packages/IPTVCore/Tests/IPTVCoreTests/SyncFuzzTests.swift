import XCTest
@testable import IPTVCore

/// Three devices whose clocks differ by hours, syncing in a random order (docs/SYNC.md): they end with the same data,
/// times as they were made, and nothing is lost that no later deletion removed. The same cases as the Kotlin
/// `SyncMergeFuzzTest`.
final class SyncFuzzTests: XCTestCase {
    /// SplitMix64: the same numbers for a seed on every platform.
    struct Random {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
        mutating func int(_ bound: Int) -> Int { Int(next() % UInt64(bound)) }
        mutating func chance(_ percent: Int) -> Bool { int(100) < percent }
        mutating func pick<T>(_ values: [T]) -> T { values[int(values.count)] }
    }

    static let start: Int64 = 1_760_000_000_000
    static let hour: Int64 = 3_600_000

    /// A device's data, dated by its own clock (`skew` ahead of the true time).
    static func device(_ name: String, skew: Int64, random: inout Random) -> SyncSnapshot {
        func time() -> Int64 { start + Int64(random.int(1_000)) + skew }
        let items = ["m1", "m2", "m3"]
        var snapshot = SyncSnapshot(device: name)
        for item in items where random.chance(40) {
            snapshot.favorites.append(SyncFavorite(playlist: "p", kind: "movie", item: item, title: random.pick(["T", "U"]),
                                                   image: random.pick([nil, "i1"]), addedAt: time()))
        }
        for item in items where random.chance(40) {
            snapshot.progress.append(SyncProgress(playlist: "p", kind: "movie", item: item, title: "T", position: Double(random.int(3) * 10),
                                                  duration: Double(100 + random.int(2) * 100), watchedAt: time()))
        }
        for name in ["Ada", "Bea"] where random.chance(40) {
            snapshot.people.append(SyncPerson(name: name, image: random.pick([nil, "i1"]), addedAt: time()))
        }
        if random.chance(60) {
            let listTime = time()
            let keys = items.filter { _ in random.chance(50) }.shuffledWith(&random)
            snapshot.lists.append(SyncList(id: "l", name: random.pick(["Kids", "Weekend"]),
                                           items: keys.map { SyncListItem(playlist: "p", kind: "movie", item: $0, title: "T", addedAt: listTime - Int64(random.int(5))) },
                                           updatedAt: listTime, renamedAt: listTime - Int64(random.int(3)), orderedAt: listTime - Int64(random.int(3))))
        }
        for item in items where random.chance(15) { snapshot.deleted["favorite:p|movie|" + item] = time() }
        for item in items where random.chance(10) { snapshot.deleted["progress:p|movie|\(item)|"] = time() }
        for item in items where random.chance(10) { snapshot.deleted["listitem:l|p|movie|" + item] = time() }
        if random.chance(10) { snapshot.deleted["person:ada"] = time() }
        return snapshot
    }

    /// One session: what each side receives is left out when dated more than a day after its own clock, taken into
    /// its clock, and merged; no time is moved (docs/SYNC.md, "Clocks").
    static func session(client: SyncSnapshot, clientClock: inout SyncClock, clientWall: Int64,
                        server: SyncSnapshot, serverClock: inout SyncClock, serverWall: Int64) -> (SyncSnapshot, SyncSnapshot) {
        let received = client.withoutRecordsAhead(wall: serverWall)
        serverClock.observe(received, wall: serverWall)
        let kept = SyncMerge.merge(server, received)
        let answer = kept.withoutRecordsAhead(wall: clientWall)
        clientClock.observe(answer, wall: clientWall)
        return (SyncMerge.merge(client, answer), kept)
    }

    /// The snapshot without who holds it.
    static func content(_ snapshot: SyncSnapshot) -> SyncSnapshot {
        var copy = snapshot
        copy.device = ""
        copy.clock = nil
        return copy
    }

    func testThreeDevicesWithSkewedClocksAgreeAndLoseNothing() {
        for seed in 0..<2_000 {
            var random = Random(state: UInt64(seed))
            let skews = (0..<3).map { _ in random.pick([-2 * Self.hour, 0, 2 * Self.hour]) }
            let start = (0..<3).map { Self.device(["a", "b", "c"][$0], skew: skews[$0], random: &random) }
            var devices = start
            var clocks = (0..<3).map { _ in SyncClock() }
            var now = Self.start + 10_000
            // Random sessions, then each pair once more both ways: everything has reached everyone.
            let pairs = (0..<random.int(6)).map { _ in (random.int(3), random.int(3)) }.filter { $0.0 != $0.1 }
                + [(0, 1), (1, 2), (2, 0), (1, 0), (2, 1), (0, 2)]
            for (client, server) in pairs {
                now += 1_000
                var (clientClock, serverClock) = (clocks[client], clocks[server])
                (devices[client], devices[server]) = Self.session(client: devices[client], clientClock: &clientClock, clientWall: now + skews[client],
                                                                  server: devices[server], serverClock: &serverClock, serverWall: now + skews[server])
                (clocks[client], clocks[server]) = (clientClock, serverClock)
            }
            let seen = devices.map(Self.content)
            XCTAssertEqual(seen[0], seen[1], "seed \(seed)")
            XCTAssertEqual(seen[1], seen[2], "seed \(seed)")
            Self.assertNothingLost(from: start.map(Self.content), in: seen[0], seed: seed)
        }
    }

    /// Each record of a key is kept unless a deletion of that key is at least as late as its latest version.
    static func assertNothingLost(from start: [SyncSnapshot], in result: SyncSnapshot, seed: Int) {
        var deleted: [String: Int64] = [:]
        for snapshot in start { deleted.merge(snapshot.deleted, uniquingKeysWith: max) }
        func check(_ key: String, _ time: Int64, _ present: Bool, _ what: String) {
            guard deleted[key].map({ time > $0 }) ?? true else { return }
            XCTAssertTrue(present, "seed \(seed): \(what) lost")
        }
        for favorite in start.flatMap(\.favorites) {
            check(favorite.key, favorite.addedAt, result.favorites.contains { $0.key == favorite.key && $0.addedAt >= favorite.addedAt }, favorite.key)
        }
        for progress in start.flatMap(\.progress) {
            check(progress.key, progress.watchedAt, result.progress.contains { $0.key == progress.key && $0.watchedAt >= progress.watchedAt }, progress.key)
        }
        for person in start.flatMap(\.people) {
            check(person.key, person.addedAt, result.people.contains { $0.key == person.key && $0.addedAt >= person.addedAt }, person.key)
        }
        let lists = start.flatMap(\.lists)
        guard let list = lists.first else { return }
        check(list.key, lists.map(\.updatedAt).max() ?? 0, !result.lists.isEmpty, "list")
        guard let kept = result.lists.first else { return }
        for source in lists {
            for item in source.items {
                let time = item.addedAt ?? source.updatedAt
                check(list.itemKey(item.key), time, kept.items.contains { $0.key == item.key && ($0.addedAt ?? 0) >= time }, "list item \(item.key)")
            }
        }
    }
}

private extension Array {
    func shuffledWith(_ random: inout SyncFuzzTests.Random) -> [Element] {
        var copy = self
        for index in copy.indices.reversed() where index > 0 {
            copy.swapAt(index, random.int(index + 1))
        }
        return copy
    }
}

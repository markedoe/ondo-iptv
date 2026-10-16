import Foundation
import IPTVCore

/// Writes the user's library (favourites, history, progress…) off the main thread: encoding it and writing the
/// file takes a moment once the history is long, and progress changes it twice a minute while a film plays.
///
/// Writes run one at a time on a serial queue and always take the latest version: a burst of changes is written
/// once, and an older version can never land after a newer one.
final class LibraryWriter: @unchecked Sendable {
    private let store: FileStore
    private let queue = DispatchQueue(label: "Ondo.LibraryWriter", qos: .utility)
    /// Guards `pending`.
    private let lock = NSLock()
    private var pending: (library: UserLibrary, name: String)?

    init(store: FileStore) {
        self.store = store
    }

    /// Writes `library` as `name` after `delay`, unless a newer version replaces it meanwhile.
    func schedule(_ library: UserLibrary, name: String, after delay: Duration = .milliseconds(600)) {
        lock.withLock { pending = (library, name) }
        let (seconds, attoseconds) = delay.components
        queue.asyncAfter(deadline: .now() + Double(seconds) + Double(attoseconds) / 1e18) { [self] in writePending() }
    }

    /// Writes the latest version now, after any write under way (the app goes to the background, another profile
    /// opens). Blocks the caller for that long.
    func flush() {
        queue.sync { writePending() }
    }

    /// Drops a version not written yet of `name` (its profile is deleted), after any write under way.
    func discard(name: String) {
        queue.sync {
            lock.withLock { if pending?.name == name { pending = nil } }
        }
    }

    private func writePending() {
        guard let job = lock.withLock({ () -> (library: UserLibrary, name: String)? in
            defer { pending = nil }
            return pending
        }) else { return }
        try? store.save(job.library, name: job.name)
    }
}

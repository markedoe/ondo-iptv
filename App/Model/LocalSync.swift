import Foundation
import IPTVCore
import Network
import Observation

/// Sync on the local network, the network side (docs/SYNC.md): finds the Android devices that offer sync with Bonjour
/// and syncs with each of them, as a client only. iPhone, iPad and Mac never listen for connections (the Mac App Store
/// sandbox has no `network.server`), and they connect only to local addresses, checked once connected and before
/// anything is sent.
///
/// When: as soon as a device is found, every 60 seconds while the app is in the foreground, and a few seconds after
/// a change here (`scheduleSoon`). After a failed session, the device is tried again after 15 seconds, 1 minute, then
/// every 5 minutes while Bonjour still finds it. Turning sync off, or the app going to the background, stops everything.
@MainActor
@Observable
final class LocalSync {
    /// A device found on the network, and how syncing with it went.
    struct Device: Identifiable, Equatable {
        /// Its sync device ID (TXT `d`).
        let id: String
        /// Its name for the device list (TXT `n`).
        var name: String
        var endpoint: NWEndpoint
        var syncedAt: Date?
        /// Failed sessions in a row.
        var failures = 0
    }

    enum Problem: Equatable {
        /// The system did not let Ondo use the local network (Settings → Privacy & Security → Local Network).
        case localNetworkDenied
    }

    private(set) var isRunning = false
    /// The devices found, by name.
    private(set) var devices: [Device] = []
    private(set) var problem: Problem?

    @ObservationIgnored weak var model: AppModel?
    @ObservationIgnored private var deviceID = ""
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var browserRestart: Task<Void, Never>?
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var pendingChange: Task<Void, Never>?
    @ObservationIgnored private var retries: [String: Task<Void, Never>] = [:]
    /// Devices waiting for a session, in order; one session at a time.
    @ObservationIgnored private var queue: [String] = []
    @ObservationIgnored private var worker: Task<Void, Never>?
    /// Bumped by each start and stop: work of an earlier run ends without touching the current one.
    @ObservationIgnored private var generation = 0

    static let interval: Duration = .seconds(60)
    static let changeDelay: Duration = .seconds(3)
    static let retryDelays: [Duration] = [.seconds(15), .seconds(60), .seconds(300)]

    func start(deviceID: String) {
        guard !isRunning else { return }
        isRunning = true
        generation += 1
        self.deviceID = deviceID
        problem = nil
        browse()
        let generation = self.generation
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.interval)
                guard let self, !Task.isCancelled, self.generation == generation else { return }
                self.syncAll()
            }
        }
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        generation += 1
        browser?.cancel()
        browser = nil
        for task in [browserRestart, ticker, pendingChange, worker] { task?.cancel() }
        browserRestart = nil
        ticker = nil
        pendingChange = nil
        worker = nil
        retries.values.forEach { $0.cancel() }
        retries = [:]
        queue = []
        devices = []
        problem = nil
    }

    /// Something sync carries changed on this device: the devices hear of it a few seconds later (a burst of
    /// changes makes one session).
    func scheduleSoon() {
        guard isRunning else { return }
        pendingChange?.cancel()
        pendingChange = Task { [weak self] in
            try? await Task.sleep(for: Self.changeDelay)
            guard !Task.isCancelled else { return }
            self?.syncAll()
        }
    }

    // MARK: - Discovery

    private func browse() {
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: SyncNetwork.serviceType, domain: nil), using: parameters)
        let generation = self.generation
        // Both handlers are called on the main queue.
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated {
                guard let self, self.generation == generation else { return }
                self.found(results)
            }
        }
        browser.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                guard let self, self.generation == generation else { return }
                self.browserChanged(state)
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    private func browserChanged(_ state: NWBrowser.State) {
        switch state {
        case .ready:
            problem = nil
        case .waiting(let error):
            problem = Self.isLocalNetworkDenied(error) ? .localNetworkDenied : nil
        case .failed(let error):
            problem = Self.isLocalNetworkDenied(error) ? .localNetworkDenied : nil
            // Browsing stopped: start again a little later.
            browser?.cancel()
            browser = nil
            let generation = self.generation
            browserRestart = Task { [weak self] in
                try? await Task.sleep(for: Self.retryDelays[0])
                guard let self, !Task.isCancelled, self.generation == generation, self.isRunning else { return }
                self.browse()
            }
        default:
            break
        }
    }

    /// Local network access refused by the system (`kDNSServiceErr_PolicyDenied`).
    private static func isLocalNetworkDenied(_ error: NWError) -> Bool {
        if case .dns(let code) = error { return code == -65_570 }
        return false
    }

    private func found(_ results: Set<NWBrowser.Result>) {
        var seen: [String: (name: String, endpoint: NWEndpoint)] = [:]
        for result in results {
            guard case .bonjour(let record) = result.metadata,
                  record[SyncNetwork.versionKey] == SyncNetwork.protocolVersion,
                  let id = record[SyncNetwork.deviceKey]?.lowercased(), !id.isEmpty, id != deviceID, seen[id] == nil
            else { continue }
            var name = record[SyncNetwork.nameKey]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if name.isEmpty, case .service(let service, _, _, _) = result.endpoint { name = service }
            seen[id] = (String(name.prefix(40)), result.endpoint)
        }
        let known = Dictionary(devices.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        devices = seen.map { id, value in
            var device = known[id] ?? Device(id: id, name: value.name, endpoint: value.endpoint)
            device.name = value.name
            device.endpoint = value.endpoint
            return device
        }
        .sorted { ($0.name.localizedLowercase, $0.id) < ($1.name.localizedLowercase, $1.id) }
        // Gone: no more attempts. New: a session at once.
        for id in retries.keys where seen[id] == nil {
            retries.removeValue(forKey: id)?.cancel()
        }
        queue.removeAll { seen[$0] == nil }
        for id in seen.keys.sorted() where known[id] == nil {
            enqueue(id)
        }
    }

    // MARK: - Sessions

    /// Every device found, except those waiting for their next attempt after a failure.
    private func syncAll() {
        for device in devices where retries[device.id] == nil {
            enqueue(device.id)
        }
    }

    private func enqueue(_ id: String) {
        guard isRunning else { return }
        if !queue.contains(id) { queue.append(id) }
        guard worker == nil else { return }
        let generation = self.generation
        worker = Task { [weak self] in
            await self?.work(generation: generation)
        }
    }

    private func work(generation: Int) async {
        while self.generation == generation, !queue.isEmpty {
            let id = queue.removeFirst()
            guard let device = devices.first(where: { $0.id == id }) else { continue }
            await session(with: device, generation: generation)
        }
        if self.generation == generation { worker = nil }
    }

    private func session(with device: Device, generation: Int) async {
        guard let snapshot = model?.syncSnapshotForSending() else { return }
        do {
            let frame = try await Task.detached(priority: .utility) { try SyncNetwork.frame(snapshot.encoded()) }.value
            // A new connection to the Bonjour service: its address is found again each time, after a failure too.
            let answer = try await SyncExchange(endpoint: device.endpoint).run(sending: frame)
            guard let merged = await Task.detached(priority: .utility, operation: { SyncSnapshot.decode(answer) }).value else {
                throw SyncExchange.Failure.unreadable
            }
            guard self.generation == generation, let model else { return }
            await model.applySync(merged)
            guard self.generation == generation else { return }
            succeeded(device.id)
        } catch {
            guard self.generation == generation else { return }
            failed(device.id)
        }
    }

    private func succeeded(_ id: String) {
        retries.removeValue(forKey: id)?.cancel()
        guard let index = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[index].failures = 0
        devices[index].syncedAt = Date()
    }

    /// Tries the device again after 15 s, 1 min, then every 5 min, as long as Bonjour still finds it.
    private func failed(_ id: String) {
        guard let index = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[index].failures += 1
        let delay = Self.retryDelays[min(devices[index].failures, Self.retryDelays.count) - 1]
        let generation = self.generation
        retries.removeValue(forKey: id)?.cancel()
        retries[id] = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, !Task.isCancelled, self.generation == generation else { return }
            self.retries[id] = nil
            self.enqueue(id)
        }
    }

    /// Whether an endpoint is an address of the local network (`SyncNetwork.isLocal`) and not this device's loopback:
    /// another device is never reached there, while any process on this one could advertise the service there. A
    /// name is not an address.
    nonisolated static func isLocal(_ endpoint: NWEndpoint) -> Bool {
        guard case .hostPort(let host, _) = endpoint else { return false }
        let bytes: [UInt8]
        switch host {
        case .ipv4(let address): bytes = [UInt8](address.rawValue)
        case .ipv6(let address): bytes = [UInt8](address.rawValue)
        default: return false
        }
        return SyncNetwork.isLocal(bytes) && !isLoopback(bytes)
    }

    /// 127/8 and ::1, also an IPv4 loopback address mapped in IPv6.
    nonisolated static func isLoopback(_ bytes: [UInt8]) -> Bool {
        switch bytes.count {
        case 4:
            return bytes[0] == 127
        case 16:
            let mapped = bytes[0..<10].allSatisfy { $0 == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF
            return (mapped && bytes[12] == 127) || (bytes[0..<15].allSatisfy { $0 == 0 } && bytes[15] == 1)
        default:
            return false
        }
    }
}

/// One session with a device (docs/SYNC.md, "Transport"): connect (10 s), check that the address reached is local,
/// send this device's snapshot in one frame, read the merged one in one frame, close; 30 s in all.
final class SyncExchange: @unchecked Sendable {
    enum Failure: Error {
        case notLocal, timedOut, closed, cancelled, unreadable
    }

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "Ondo.LocalSync.session")
    // Only used on `queue`.
    private var continuation: CheckedContinuation<Data, Error>?
    private var isReady = false
    private var isFinished = false

    init(endpoint: NWEndpoint) {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = Int(SyncNetwork.connectTimeout)
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.includePeerToPeer = false
        parameters.prohibitedInterfaceTypes = [.cellular]
        connection = NWConnection(to: endpoint, using: parameters)
    }

    /// Sends `frame` and returns the payload of the frame the device answers with.
    func run(sending frame: Data) async throws -> Data {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                queue.async { self.begin(frame, continuation) }
            }
        } onCancel: {
            queue.async { self.finish(.failure(Failure.cancelled)) }
        }
    }

    private func begin(_ frame: Data, _ continuation: CheckedContinuation<Data, Error>) {
        guard !isFinished else {
            continuation.resume(throwing: Failure.cancelled)
            return
        }
        self.continuation = continuation
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: ready(frame)
            case .waiting(let error), .failed(let error): finish(.failure(error))
            case .cancelled: finish(.failure(Failure.closed))
            default: break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + SyncNetwork.connectTimeout) { [weak self] in
            guard let self, !isReady else { return }
            finish(.failure(Failure.timedOut))
        }
        queue.asyncAfter(deadline: .now() + SyncNetwork.sessionTimeout) { [weak self] in
            self?.finish(.failure(Failure.timedOut))
        }
    }

    private func ready(_ frame: Data) {
        guard !isReady, !isFinished else { return }
        isReady = true
        // Only to the local network: the address the service led to is checked before anything is sent.
        guard let remote = connection.currentPath?.remoteEndpoint, LocalSync.isLocal(remote) else {
            finish(.failure(Failure.notLocal))
            return
        }
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            if let error { self?.finish(.failure(error)) }
        })
        receive(4) { [weak self] header in
            guard let self else { return }
            do {
                let length = try SyncNetwork.frameLength(header)
                guard length > 0 else {
                    finish(.success(Data()))
                    return
                }
                receive(length) { [weak self] body in self?.finish(.success(body)) }
            } catch {
                finish(.failure(error))
            }
        }
    }

    /// Exactly `count` bytes; fewer (the device closed the connection) is a frame cut short.
    private func receive(_ count: Int, then handle: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, _, error in
            guard let self else { return }
            if let error {
                finish(.failure(error))
            } else if let data, data.count == count {
                handle(data)
            } else {
                finish(.failure(SyncNetwork.FrameError.cutShort))
            }
        }
    }

    private func finish(_ result: Result<Data, Error>) {
        guard !isFinished else { return }
        isFinished = true
        connection.stateUpdateHandler = nil
        connection.cancel()
        continuation?.resume(with: result)
        continuation = nil
    }
}

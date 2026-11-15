import Foundation
import IPTVCore

/// What a film's versions are made of (docs/VERSIONS.md, "What a version is made of"): what the player measured, else
/// the provider's details (`get_vod_info`'s video and audio), else the tags of the name. The same rules as Android's.
extension AppModel {
    /// The document of what the player measured, with the caches.
    static let measuredSpecsDocument = "measured-specs"

    /// The key of stream `id` of the open playlist in `measuredSpecs`.
    private func measuredKey(_ id: String) -> String? {
        activePlaylist.map { MeasuredSpecs.key(playlistID: $0.id.uuidString, streamID: id) }
    }

    /// What is known of stream `id`'s picture and sound, best first: what the player measured, else (field by field) the
    /// provider's details; nil when nothing. Reads what is kept: never a request.
    func streamSpecs(for id: String) -> StreamSpecs? {
        let provider = movieDetailsCache[id]?.streamSpecs.flatMap { $0.isEmpty ? nil : $0 }
        guard let key = measuredKey(id), let measured = measuredSpecs[key], !measured.isEmpty else { return provider }
        return measured.orElse(provider)
    }

    /// Where `id`'s specs come from (`streamSpecs(for:)`): measured, the provider's, or nil (its name's tags only).
    func specSource(for id: String) -> SpecSource? {
        if let key = measuredKey(id), let measured = measuredSpecs[key], !measured.isEmpty { return .measured }
        if let provider = movieDetailsCache[id]?.streamSpecs, !provider.isEmpty { return .provider }
        return nil
    }

    /// The facts line's badges of `version`, the one Play plays ("4K", "Dolby Vision", "HEVC", "5.1 AAC"), and what
    /// VoiceOver says of them: its specs where known, the tags of its name (as its chip reads them, among `versions`)
    /// elsewhere.
    func specBadges(for version: Movie, among versions: [Movie]) -> (badges: [String], description: String) {
        let names = categoryNames
        let tags: [VersionTag]
        if versions.count > 1, let index = versions.firstIndex(where: { $0.id == version.id }) {
            tags = FilmVersions.labelTags(versions, categoryName: { names[$0] })[index]
        } else {
            tags = FilmVersions.labelTags(name: version.name, categoryName: names[version.categoryID])
        }
        let specs = streamSpecs(for: version.id)
        _ = detailsRevision  // redraw the page when details arrive
        return (StreamSpecs.badges(specs, nameTags: tags), StreamSpecs.description(specs, nameTags: tags, words: Self.specWords))
    }

    /// The words VoiceOver says of a version's sound, in the app's language, read once.
    static let specWords = StreamSpecs.Words.app()

    /// Reads what the player measured from the device, once, off the main thread: the first time a film's page or the
    /// player needs it, never at launch.
    func loadMeasuredSpecs() async {
        if measuredSpecsLoad == nil {
            let store = cacheStore
            let name = Self.measuredSpecsDocument
            measuredSpecsLoad = Task {
                let kept = await Task.detached(priority: .utility) { store.load(MeasuredSpecs.self, name: name) }.value
                // What was measured meanwhile is newer.
                if let kept {
                    var merged = kept
                    for key in measuredSpecs.keys { if let specs = measuredSpecs[key] { merged.record(key, specs) } }
                    measuredSpecs = merged
                }
            }
        }
        await measuredSpecsLoad?.value
    }

    /// The player read `specs` of film `movieID`'s stream in the open playlist: kept (the films played last) and written on
    /// the device when they changed, off the main thread.
    func recordMeasuredSpecs(_ specs: StreamSpecs, movieID: String) {
        guard let key = measuredKey(movieID), !specs.isEmpty else { return }
        Task {
            await loadMeasuredSpecs()
            var kept = measuredSpecs
            guard kept.record(key, specs) else { return }
            measuredSpecs = kept
            versionChoicesCache = [:]
            let store = cacheStore
            let name = Self.measuredSpecsDocument
            let previous = measuredSpecsWrite
            let document = kept
            measuredSpecsWrite = Task.detached(priority: .utility) {
                await previous?.value
                try? store.save(document, name: name, keepingPrevious: false)
            }
        }
    }

    /// A film's page is open and idle: the details of its other `versions` not known yet, for what their streams are made
    /// of, one at a time, in the caller's task (the page's), so cancelled when it closes. Never while browsing. A version
    /// already asked (its details kept, specs or not) is not asked again. Returns how many were asked.
    @discardableResult
    func loadVersionDetails(_ versions: [Movie]) async -> Int {
        await storedDetailsLoaded()
        var asked = 0
        for version in versions {
            guard !Task.isCancelled else { break }
            guard movieDetailsCache[version.id] == nil, version.xtreamStreamID != nil else { continue }
            _ = await details(for: version)
            asked += 1
        }
        if asked > 0 { versionChoicesCache = [:] }
        return asked
    }
}

extension StreamSpecs.Words {
    /// The words VoiceOver says of a version's sound in the app's language (`bundle`: another language's, for tests).
    static func app(bundle: Bundle = .main) -> StreamSpecs.Words {
        let surround = String(localized: "\("%@") surround sound", bundle: bundle)
        return StreamSpecs.Words(
            surround: { layout in surround.replacingOccurrences(of: "%@", with: layout) },
            stereo: String(localized: "Stereo sound", bundle: bundle),
            mono: String(localized: "Mono sound", bundle: bundle),
            atmos: String(localized: "Dolby Atmos sound", bundle: bundle)
        )
    }
}

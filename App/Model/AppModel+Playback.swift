import Foundation
import IPTVCore

/// Playback: builds sessions (zapping queues, replays, downloads) and presents them.
extension AppModel {
    private var factory: PlaybackFactory? {
        activePlaylist.map { PlaybackFactory(playlist: $0, account: content.account) }
    }

    func play(_ channel: Channel, in list: [Channel]? = nil) {
        guard let session = liveSession(for: channel, in: list) else { return }
        present(session)
    }

    /// Replays (or restarts) a programme; the player keeps the channel so "Live" returns to it.
    func playCatchup(_ program: EPGProgram, on channel: Channel) {
        guard let factory, factory.catchupItem(for: channel, program: program) != nil,
              let session = liveSession(for: channel, in: nil) else {
            bannerMessage = String(localized: "Replay is not available for this programme.")
            return
        }
        present(session, startingAt: program)
    }

    private func liveSession(for channel: Channel, in list: [Channel]?) -> PlaybackSession? {
        guard let factory else { return nil }
        let queueChannels = list ?? index.channelsByCategory[channel.categoryID] ?? [channel]
        // Lookups only: the queue can hold every channel of the playlist.
        let guideIDs = guideIDsByChannelID()
        let now = Date()
        let queue = queueChannels.map { channel in
            factory.item(
                for: channel,
                currentProgram: guideIDs[channel.id].flatMap { epg.current(guideID: $0, at: now) },
                alternates: index.matchKeyByChannelID[channel.id].flatMap { index.channelsByMatchKey[$0] } ?? []
            )
        }
        let position = queueChannels.firstIndex { $0.id == channel.id } ?? 0
        return PlaybackSession(queue: queue.isEmpty ? [factory.item(for: channel)] : queue, index: queue.isEmpty ? 0 : position)
    }

    // MARK: Multiview

    static var supportsMultiview: Bool { Platform.isMac || Platform.isPad }

    /// Adds a channel to the multiview and shows it.
    func addToMultiview(_ channel: Channel) {
        guard Self.supportsMultiview, let factory else { return }
        let item = factory.item(for: channel, currentProgram: epg.current(for: channel))
        if multiview.isFull && !multiview.contains(item) {
            bannerMessage = String(localized: "Multiview shows up to 4 channels. Close one to add another.")
        } else {
            multiview.add(item) { session in configureMultiviewTile(session) }
        }
        #if os(iOS)
        if let current = playbackRequest {
            // Close the player first: two full-screen covers cannot animate at once.
            endSession(current)
            Task {
                try? await Task.sleep(for: .milliseconds(500))
                isMultiviewPresented = true
            }
            return
        }
        #endif
        isMultiviewPresented = true
    }

    /// Replaces the channel of a tile.
    func setMultiviewTile(_ index: Int, to channel: Channel) {
        guard let factory else { return }
        multiview.replace(at: index, with: factory.item(for: channel, currentProgram: epg.current(for: channel)))
    }

    func closeMultiview() {
        multiview.stopAll()
        isMultiviewPresented = false
    }

    /// Opens a tile in the regular player, with the tile's channel list for zapping.
    func expandMultiviewTile(_ index: Int) {
        guard multiview.tiles.indices.contains(index), let item = multiview.tiles[index].current,
              let channel = self.index.channelsByID[item.reference.itemID] else { return }
        closeMultiview()
        #if os(iOS)
        // Let the multiview cover go away before presenting the player.
        Task {
            try? await Task.sleep(for: .milliseconds(500))
            play(channel)
        }
        #else
        play(channel)
        #endif
    }

    private func configureMultiviewTile(_ session: PlaybackSession) {
        session.controller.autoRetry = settings.autoRetryStreams
        session.controller.enginePreference = settings.playerEngine
        session.controller.liveBufferSetting = settings.liveBuffer
        session.controller.vodBufferSetting = settings.vodBuffer
        session.controller.aspect = .fit
        session.controller.allowsPictureInPicture = false
    }

    /// Catch-up item playing the live `item`'s channel from `start` (nil without archive).
    private func archiveItem(for item: PlaybackItem, start: Date, end: Date, title: String?) -> PlaybackItem? {
        guard item.isLive, let factory, let channel = index.channelsByID[item.reference.itemID] else { return nil }
        return factory.catchupItem(for: channel, start: start, end: end, title: title)
    }

    func canReplay(_ program: EPGProgram, on channel: Channel) -> Bool {
        CatchupURLBuilder.isAvailable(channel: channel, program: program)
    }

    func play(_ movie: Movie, fromStart: Bool = false) {
        guard let factory else { return }
        var item = Self.preferringDownload(factory.item(for: movie))
        item.startPosition = fromStart ? nil : resumePosition(for: item)
        present(PlaybackSession(queue: [item], index: 0))
    }

    /// Plays the local copy when the item was downloaded (the stream stays as a fallback). The App Store edition has no
    /// downloads: the item as it is.
    private static func preferringDownload(_ item: PlaybackItem) -> PlaybackItem {
        #if TESTFLIGHT_DOWNLOADS || DEBUG
        guard let local = DownloadManager.shared.localFile(for: item) else { return item }
        var copy = item
        copy.fallbackURLs = [item.streamURL] + item.fallbackURLs
        copy.streamURL = local
        return copy
        #else
        return item
        #endif
    }

    func downloadItem(for movie: Movie) -> PlaybackItem? {
        factory?.item(for: movie)
    }

    func downloadItem(for episode: Episode, in series: Series) -> PlaybackItem? {
        factory?.item(for: episode, in: series)
    }

    #if TESTFLIGHT_DOWNLOADS || DEBUG
    func playDownload(_ record: DownloadRecord) {
        var item = Self.preferringDownload(record.item)
        item.startPosition = resumePosition(for: item)
        present(PlaybackSession(queue: [item], index: 0))
    }
    #endif

    func play(_ episode: Episode, in series: Series, season: [Episode], fromStart: Bool = false) {
        guard let factory else { return }
        let queue = season.map { Self.preferringDownload(factory.item(for: $0, in: series)) }
        let position = season.firstIndex { $0.id == episode.id } ?? 0
        var items = queue
        if items.indices.contains(position) {
            items[position].startPosition = fromStart ? nil : resumePosition(for: items[position])
        }
        present(PlaybackSession(queue: items, index: position))
    }

    /// Re-opens something from the history / continue-watching rows.
    func resume(_ entry: HistoryEntry) {
        switch entry.reference.type {
        case .live:
            if let channel = index.channelsByID[entry.reference.itemID] { play(channel) }
        case .movie:
            if let movie = index.moviesByID[entry.reference.itemID] { play(movie) }
        case .series:
            guard let series = index.seriesByID[entry.reference.itemID] else { return }
            if let item = quickEpisodeItem(entry, in: series) {
                // Plays at once; the season follows for the next episodes.
                let session = PlaybackSession(queue: [item], index: 0)
                present(session)
                Task { [weak session] in
                    guard let factory, let details = await episodes(of: series),
                          let season = details.seasons.first(where: { $0.episodes.contains { $0.id == entry.reference.episodeID } })
                    else { return }
                    session?.adoptQueue(season.episodes.map { Self.preferringDownload(factory.item(for: $0, in: series)) })
                }
                return
            }
            Task {
                guard let seriesDetails = await episodes(of: series) else { return }
                for season in seriesDetails.seasons {
                    if let episode = season.episodes.first(where: { $0.id == entry.reference.episodeID }) {
                        play(episode, in: series, season: season.episodes)
                        return
                    }
                }
                // The provider renumbered its episodes: the next one to watch rather than nothing.
                if let next = nextEpisode(in: series, details: seriesDetails) {
                    play(next.episode, in: series, season: next.season.episodes)
                }
            }
        }
    }

    /// An episode of the history as an item, without asking the provider for the season: Xtream builds an episode's
    /// address from its id and container, both kept in the history (older entries have no container: nil).
    private func quickEpisodeItem(_ entry: HistoryEntry, in series: Series) -> PlaybackItem? {
        guard let factory, let episodeID = entry.reference.episodeID, let container = entry.streamExtension,
              let credentials = activePlaylist?.source.xtreamCredentials else { return nil }
        let url = XtreamURLBuilder(credentials: credentials).episodeURL(episodeID: episodeID, containerExtension: container)
        var item = factory.item(for: Episode(id: episodeID, title: "", season: 0, number: 0, streamURL: url), in: series)
        item.subtitle = entry.subtitle
        item = Self.preferringDownload(item)
        item.startPosition = resumePosition(for: item)
        return item
    }

    /// "Play last channel at launch".
    func autoplayLastChannel() {
        guard let playlist = activePlaylist, let last = library.lastChannel(playlistID: playlist.id) else { return }
        resume(last)
    }

    /// Category of a playlist item (nil for an item of another playlist).
    func categoryID(of item: PlaybackItem) -> String? {
        guard item.reference.playlistID == activePlaylist?.id else { return nil }
        switch item.reference.type {
        case .live: return index.channelsByID[item.reference.itemID]?.categoryID
        case .movie: return index.moviesByID[item.reference.itemID]?.categoryID
        case .series: return index.seriesByID[item.reference.itemID]?.categoryID
        }
    }

    /// Whether playing this item needs the parental PIN first: a locked category, or one a kids profile does not see.
    func needsPIN(for item: PlaybackItem) -> Bool {
        guard let categoryID = categoryID(of: item) else { return false }
        if isLocked(categoryID: categoryID) { return true }
        let category = content.liveCategories.first { $0.id == categoryID }
            ?? content.movieCategories.first { $0.id == categoryID }
            ?? content.seriesCategories.first { $0.id == categoryID }
        return category.map(isHiddenForKids) ?? false
    }

    #if TESTFLIGHT_DOWNLOADS || DEBUG
    /// Downloads of locked or hidden categories are left out of the Downloads list, like their category.
    func isVisible(_ record: DownloadRecord) -> Bool {
        guard let categoryID = categoryID(of: record.item) else { return true }
        return !excludedCategoryIDs.contains(categoryID)
    }
    #endif

    /// Plays what waited for the PIN (called once it was entered).
    func resumeLockedPlayback(_ pending: PendingPlayback) {
        pendingLockedPlayback = nil
        guard !needsPIN(for: pending.item) else { return }
        present(pending.session, startingAt: pending.program)
    }

    private func present(_ session: PlaybackSession, startingAt program: EPGProgram? = nil) {
        // Every way to play goes through here: the lists already leave locked categories out, but a reminder, a
        // download or the last channel at launch could reach one.
        if let item = session.queue.indices.contains(session.index) ? session.queue[session.index] : nil, needsPIN(for: item) {
            pendingLockedPlayback = PendingPlayback(session: session, item: item, program: program)
            return
        }
        let controller = session.controller
        controller.autoRetry = settings.autoRetryStreams
        controller.aspect = settings.defaultAspect
        controller.preferredAudioLanguage = settings.preferredAudioLanguage
        controller.preferredSubtitleLanguage = settings.preferredSubtitleLanguage
        controller.subtitlesEnabledByDefault = settings.subtitlesEnabled
        controller.enginePreference = settings.playerEngine
        controller.liveBufferSetting = settings.liveBuffer
        controller.vodBufferSetting = settings.vodBuffer
        controller.onProgress = { [weak self] item, position, duration in
            self?.recordProgress(item, position: position, duration: duration)
        }
        controller.onEnded = { [weak session] item in
            guard let session else { return }
            if session.isTimeshifted {
                // The replay caught up with the live edge (or the archive ended).
                session.returnToLive()
            } else if item.reference.type == .series, session.hasNext {
                session.zap(1)
            }
        }
        controller.onPictureInPictureChange = { [weak self, weak session] active in
            guard let self, let session else { return }
            #if os(iOS)
            if active {
                // Close the full-screen UI, playback continues in the PiP window.
                self.pictureInPictureSession = session
                if self.playbackRequest === session { self.playbackRequest = nil }
            } else if self.pictureInPictureSession === session {
                self.pictureInPictureSession = nil
                if self.playbackRequest !== session { self.endSession(session) }
            }
            #endif
        }
        controller.restoreUserInterface = { [weak self, weak session] completion in
            guard let self, let session else { return completion(false) }
            self.playbackRequest = session
            completion(true)
        }
        session.onItemStarted = { [weak self, weak session] item in
            self?.recordStart(item)
            if let session { NowPlayingCenter.shared.activate(session) }
        }
        session.resumePosition = { [weak self] item in self?.resumePosition(for: item) }
        session.catchupItem = { [weak self] item, start, end, title in
            self?.archiveItem(for: item, start: start, end: end, title: title)
        }

        // A new playback replaces the iOS one (single full-screen player); macOS opens a new window.
        #if os(iOS)
        if let current = playbackRequest, current !== session { endSession(current) }
        if let pip = pictureInPictureSession { endSession(pip) }
        #endif
        sessions[session.id] = session
        session.start(from: program)
        playbackRequest = session
    }

    func session(id: UUID) -> PlaybackSession? { sessions[id] }

    func endSession(_ session: PlaybackSession) {
        // Remember the user's track choices for the next streams.
        updateSettings {
            $0.preferredAudioLanguage = session.controller.preferredAudioLanguage ?? $0.preferredAudioLanguage
            $0.preferredSubtitleLanguage = session.controller.preferredSubtitleLanguage ?? $0.preferredSubtitleLanguage
            $0.subtitlesEnabled = session.controller.subtitlesEnabledByDefault
        }
        session.controller.stop()
        NowPlayingCenter.shared.deactivate(session)
        sessions[session.id] = nil
        if pictureInPictureSession === session { pictureInPictureSession = nil }
        if playbackRequest === session { playbackRequest = nil }
        // Where the movie or episode was left goes to the other devices once playback ends (not at every save).
        if session.current?.isLive == false { syncSoon() }
    }

    /// Called when the iOS full-screen player is dismissed by the user.
    func playerDismissed(_ session: PlaybackSession) {
        if session.controller.isPictureInPictureActive { return }
        endSession(session)
    }
}

/// A playback waiting for the parental PIN.
struct PendingPlayback: Identifiable {
    let id = UUID()
    let session: PlaybackSession
    let item: PlaybackItem
    let program: EPGProgram?
}

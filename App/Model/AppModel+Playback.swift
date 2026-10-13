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
        let queue = queueChannels.map { channel in
            factory.item(
                for: channel,
                currentProgram: epg.current(for: channel),
                alternates: index.channelsByMatchKey[TextNormalizer.channelMatchKey(channel.name)] ?? []
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

    /// Plays the local copy when the item was downloaded (the stream stays as a fallback).
    private static func preferringDownload(_ item: PlaybackItem) -> PlaybackItem {
        guard let local = DownloadManager.shared.localFile(for: item) else { return item }
        var copy = item
        copy.fallbackURLs = [item.streamURL] + item.fallbackURLs
        copy.streamURL = local
        return copy
    }

    func downloadItem(for movie: Movie) -> PlaybackItem? {
        factory?.item(for: movie)
    }

    func downloadItem(for episode: Episode, in series: Series) -> PlaybackItem? {
        factory?.item(for: episode, in: series)
    }

    func playDownload(_ record: DownloadRecord) {
        var item = Self.preferringDownload(record.item)
        item.startPosition = resumePosition(for: item)
        present(PlaybackSession(queue: [item], index: 0))
    }

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
            Task {
                guard let seriesDetails = try? await details(for: series) else { return }
                for season in seriesDetails.seasons {
                    if let episode = season.episodes.first(where: { $0.id == entry.reference.episodeID }) {
                        play(episode, in: series, season: season.episodes)
                        return
                    }
                }
            }
        }
    }

    private func present(_ session: PlaybackSession, startingAt program: EPGProgram? = nil) {
        let controller = session.controller
        controller.autoRetry = settings.autoRetryStreams
        controller.aspect = settings.defaultAspect
        controller.preferredAudioLanguage = settings.preferredAudioLanguage
        controller.preferredSubtitleLanguage = settings.preferredSubtitleLanguage
        controller.subtitlesEnabledByDefault = settings.subtitlesEnabled
        controller.enginePreference = settings.playerEngine
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
    }

    /// Called when the iOS full-screen player is dismissed by the user.
    func playerDismissed(_ session: PlaybackSession) {
        if session.controller.isPictureInPictureActive { return }
        endSession(session)
    }
}

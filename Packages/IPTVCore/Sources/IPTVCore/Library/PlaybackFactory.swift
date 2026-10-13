import Foundation

/// Builds `PlaybackItem`s (with fallback URLs and headers) for anything playable in a playlist.
public struct PlaybackFactory: Sendable {
    public var playlist: Playlist
    public var account: XtreamAccountInfo?

    public init(playlist: Playlist, account: XtreamAccountInfo? = nil) {
        self.playlist = playlist
        self.account = account
    }

    /// - Parameter alternates: other versions of the same channel (HD/FHD/backup), tried when the stream is dead.
    public func item(for channel: Channel, currentProgram: EPGProgram? = nil, alternates: [Channel] = []) -> PlaybackItem {
        var fallbacks: [URL] = []
        // AVPlayer cannot play raw MPEG-TS: offer the HLS variant of Xtream style URLs (and vice versa).
        if let alternate = XtreamURLBuilder.alternateFormatURL(for: channel.streamURL) {
            fallbacks.append(alternate)
        }
        for other in alternates.prefix(3) where other.id != channel.id && other.streamURL != channel.streamURL {
            fallbacks.append(other.streamURL)
        }
        return PlaybackItem(
            reference: MediaReference(playlistID: playlist.id, type: .live, itemID: channel.id),
            title: channel.name,
            subtitle: currentProgram?.title,
            artworkURL: channel.logoURL,
            streamURL: channel.streamURL,
            fallbackURLs: fallbacks,
            headers: playlist.headers.merging(channel.headers),
            isLive: true
        )
    }

    public func catchupItem(for channel: Channel, program: EPGProgram, now: Date = Date()) -> PlaybackItem? {
        catchupItem(for: channel, start: program.start, end: program.end, title: program.title, artworkURL: program.iconURL, now: now)
    }

    /// Replays `channel` from `start` (restart of the current programme, live pause…).
    /// Nil when the channel has no catch-up or `start` is outside its archive.
    public func catchupItem(
        for channel: Channel,
        start: Date,
        end: Date,
        title: String? = nil,
        artworkURL: URL? = nil,
        now: Date = Date()
    ) -> PlaybackItem? {
        guard CatchupURLBuilder.isAvailable(channel: channel, from: start, now: now) else { return nil }
        let context = CatchupURLBuilder.Context(
            xtream: playlist.source.xtreamCredentials,
            serverTimeZone: account?.serverTimeZone.flatMap(TimeZone.init(identifier:)),
            outputFormat: playlist.xtreamOutput
        )
        let urls = CatchupURLBuilder.urls(channel: channel, start: start, end: max(end, start.addingTimeInterval(60)), now: now, context: context)
        guard let first = urls.first else { return nil }
        return PlaybackItem(
            reference: MediaReference(playlistID: playlist.id, type: .live, itemID: channel.id),
            title: title ?? channel.name,
            subtitle: channel.name,
            artworkURL: artworkURL ?? channel.logoURL,
            streamURL: first,
            fallbackURLs: Array(urls.dropFirst()),
            headers: playlist.headers.merging(channel.headers),
            isLive: false,
            isCatchup: true
        )
    }

    public func item(for movie: Movie, startPosition: Double? = nil) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: playlist.id, type: .movie, itemID: movie.id),
            title: movie.name,
            subtitle: movie.year.map(String.init),
            artworkURL: movie.posterURL,
            streamURL: movie.streamURL,
            headers: playlist.headers.merging(movie.headers),
            isLive: false,
            startPosition: startPosition
        )
    }

    public func item(for episode: Episode, in series: Series, startPosition: Double? = nil) -> PlaybackItem {
        PlaybackItem(
            reference: MediaReference(playlistID: playlist.id, type: .series, itemID: series.id, episodeID: episode.id),
            title: series.name,
            subtitle: "\(episode.code) · \(episode.title)",
            artworkURL: episode.posterURL ?? series.coverURL,
            streamURL: episode.streamURL,
            headers: playlist.headers.merging(episode.headers),
            isLive: false,
            startPosition: startPosition
        )
    }
}

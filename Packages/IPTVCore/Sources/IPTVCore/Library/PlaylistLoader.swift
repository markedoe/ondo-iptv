import Foundation

public enum PlaylistLoaderError: Error, LocalizedError, Equatable {
    case unsupportedFormat
    case emptyPlaylist

    public var errorDescription: String? {
        switch self {
        case .unsupportedFormat: return "This file is not a playlist the app understands (M3U, XSPF or JSON)."
        case .emptyPlaylist: return "The playlist was downloaded but contains no channels."
        }
    }
}

/// Where a loading error comes from — shown to the user so they know whom to contact.
public enum ErrorAttribution: String, Sendable {
    case provider
    case network
    case app

    public static func classify(_ error: Error) -> ErrorAttribution {
        if error is HTTPClientError || error is XtreamError || error is PlaylistLoaderError
            || error is GzipError || error is XMLTVParser.ParseError { return .provider }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            switch nsError.code {
            case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorBadServerResponse,
                 NSURLErrorBadURL, NSURLErrorUnsupportedURL, NSURLErrorResourceUnavailable,
                 NSURLErrorZeroByteResource, NSURLErrorFileDoesNotExist:
                return .provider
            default:
                return .network
            }
        }
        return .app
    }
}

/// Downloads and parses any playlist source into `PlaylistContent`.
public struct PlaylistLoader: Sendable {
    public let http: HTTPClient
    /// Resolves a `.local(fileName:)` source to its location in the app container.
    public let localFileURL: @Sendable (String) -> URL

    public init(http: HTTPClient, localFileURL: @escaping @Sendable (String) -> URL) {
        self.http = http
        self.localFileURL = localFileURL
    }

    public func load(_ playlist: Playlist) async throws -> PlaylistContent {
        switch playlist.source {
        case .xtream(let credentials):
            let client = XtreamClient(credentials: credentials, http: http, headers: playlist.headers)
            if playlist.xtreamForceM3U {
                // Some panels have a broken JSON API but a working M3U export.
                let account = try? await client.authenticate()
                let data = try await http.data(from: client.urls.m3uURL(format: playlist.xtreamOutput), headers: playlist.headers.dictionary)
                var content = try parse(data, playlistHeaders: playlist.headers)
                content.providerEPGURLs.removeAll(where: \.isFileURL)
                content.account = account
                if content.providerEPGURLs.isEmpty { content.providerEPGURLs = [client.urls.xmltvURL] }
                return content
            }
            var content = try await client.fetchContent(format: playlist.xtreamOutput)
            // An account that lists nothing at all is a provider fault: keep the saved catalogue.
            guard !content.channels.isEmpty || !content.movies.isEmpty || !content.series.isEmpty else {
                throw PlaylistLoaderError.emptyPlaylist
            }
            content.fetchedAt = Date()
            return content

        case .remote(let url):
            let data = try await http.data(from: url, headers: playlist.headers.dictionary)
            var content = try parse(data, playlistHeaders: playlist.headers)
            // A playlist from the internet cannot make the app read files on the device as its guide.
            if !url.isFileURL { content.providerEPGURLs.removeAll(where: \.isFileURL) }
            return content

        case .realDebrid(let token):
            var content = try await RealDebridClient(token: token, http: http).fetchContent()
            content.fetchedAt = Date()
            return content

        case .local(let fileName):
            let data = try Data(contentsOf: localFileURL(fileName))
            return try parse(data, playlistHeaders: playlist.headers)
        }
    }

    public func parse(_ rawData: Data, playlistHeaders: HTTPHeaders = .none) throws -> PlaylistContent {
        let data = try rawData.gunzippedIfNeeded()
        guard !data.isEmpty else { throw PlaylistLoaderError.emptyPlaylist }
        guard let format = PlaylistFormat.detect(data) else { throw PlaylistLoaderError.unsupportedFormat }
        let parsed: M3UPlaylist
        switch format {
        case .m3u: parsed = M3UParser.parse(data)
        case .xspf: parsed = XSPFParser.parse(data)
        case .json: parsed = JSONPlaylistParser.parse(data)
        case .plainList: parsed = PlainListParser.parse(data)
        }
        guard !parsed.entries.isEmpty else { throw PlaylistLoaderError.emptyPlaylist }
        var content = M3UContentBuilder.build(from: parsed, playlistHeaders: playlistHeaders)
        content.fetchedAt = Date()
        return content
    }

    /// Loads, merges and indexes every guide of a playlist. Individual source failures are reported, not thrown.
    public func loadEPG(
        for playlist: Playlist,
        content: PlaylistContent,
        window: DateInterval = EPGFilter.standard()
    ) async -> (data: EPGData, failures: [URL: Error]) {
        var sources = playlist.epgURLs
        if playlist.useProviderEPG {
            sources += content.providerEPGURLs.filter { !sources.contains($0) }
        }
        let filter = EPGFilter(
            window: window,
            // Guide channels picked by hand are kept even when nothing else matches them.
            channelIDs: Set(content.channels.compactMap(\.epgID)).union(playlist.epgChannelOverrides.values.filter { !$0.isEmpty }),
            channelNameKeys: Set(content.channels.flatMap { [$0.name, $0.epgName].compactMap { $0 } }.map(TextNormalizer.channelMatchKey))
        )
        let shift = playlist.epgTimeShiftHours * 3600
        var merged = EPGData()
        var failures: [URL: Error] = [:]
        for url in sources {
            do {
                let raw = try await http.data(from: url, headers: playlist.headers.dictionary)
                let parsed = try XMLTVParser.parse(raw, filter: filter, timeShift: shift)
                merged.merge(parsed)
            } catch {
                failures[url] = error
            }
        }
        merged.fetchedAt = Date()
        return (merged, failures)
    }
}

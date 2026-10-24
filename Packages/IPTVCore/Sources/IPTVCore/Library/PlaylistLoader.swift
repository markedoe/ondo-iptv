import Foundation
#if os(iOS) || os(tvOS)
import os
#endif

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

    /// Memory the programmes of a playlist's guides may take: a quarter of what the system still lets the app use, on
    /// the devices that stop an app past a limit (iPhone, iPad, Apple TV). No limit elsewhere: a Mac compresses and
    /// swaps memory instead.
    public static func guideMemoryBudget() -> Int? {
        #if os(iOS) || os(tvOS)
        let available = os_proc_available_memory()
        return available > 0 ? available / 4 : nil
        #else
        return nil
        #endif
    }

    /// Loads, merges and indexes every guide of a playlist. Individual source failures are reported, not thrown.
    /// With a `budget` (bytes), a guide whose programmes would take more is kept within a narrower window around `now`
    /// (what is on and what comes next first), and the guides after it share what is left.
    public func loadEPG(
        for playlist: Playlist,
        content: PlaylistContent,
        window: DateInterval = EPGFilter.standard(),
        budget: Int? = PlaylistLoader.guideMemoryBudget(),
        now: Date = Date()
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
        var remaining = budget
        for url in sources {
            // On disk, then read while parsed: a large guide (hundreds of megabytes) never sits whole in memory.
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("guide-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: file) }
            do {
                try await http.download(from: url, headers: playlist.headers.dictionary, to: file)
                let budget = remaining
                let parsed = try XMLTVParser.withPlainXML(at: file) { xml in
                    let sized = try Self.filter(filter, within: budget, for: xml, timeShift: shift, now: now)
                    return try XMLTVParser.parsePlainXML(at: xml, filter: sized, timeShift: shift)
                }
                if let budget { remaining = max(budget - GuideEstimate.bytes(of: parsed), 0) }
                merged.merge(parsed)
            } catch {
                failures[url] = error
            }
        }
        merged.fetchedAt = Date()
        return (merged, failures)
    }

    /// `filter`, its window narrowed when the guide's programmes would take more than `budget`. A guide too small to
    /// reach the budget is not sized first: a kept programme takes at most eight times its XML (`GuideEstimate.bytes`).
    static func filter(_ filter: EPGFilter, within budget: Int?, for xml: URL, timeShift: TimeInterval, now: Date) throws -> EPGFilter {
        guard let budget else { return filter }
        let size = (try? xml.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? Int.max
        guard size > budget / 8 else { return filter }
        let estimate = try XMLTVParser.estimatePlainXML(at: xml, filter: filter, timeShift: timeShift, now: now)
        guard estimate.totalBytes > budget else { return filter }
        var narrowed = filter
        narrowed.window = estimate.window(budget: budget, full: filter.window).window
        return narrowed
    }
}

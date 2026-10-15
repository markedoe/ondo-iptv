import Foundation

public enum XtreamError: Error, LocalizedError, Equatable {
    case authenticationFailed(String?)
    case accountNotActive(String)
    case invalidResponse

    public var errorDescription: String? {
        switch self {
        case .authenticationFailed(let message):
            return message?.nilIfBlank ?? "Login failed. Check the server address, username and password."
        case .accountNotActive(let status):
            return "The provider reports this account as “\(status)”."
        case .invalidResponse:
            return "The server did not answer like an Xtream Codes panel."
        }
    }
}

/// Client for the Xtream Codes `player_api.php` API.
public struct XtreamClient: Sendable {
    public let credentials: XtreamCredentials
    public let http: HTTPClient
    public var headers: HTTPHeaders
    public var urls: XtreamURLBuilder { XtreamURLBuilder(credentials: credentials) }

    public init(credentials: XtreamCredentials, http: HTTPClient, headers: HTTPHeaders = .none) {
        self.credentials = credentials
        self.http = http
        self.headers = headers
    }

    private func get<T: Decodable>(_ type: T.Type, action: String? = nil, parameters: [String: String] = [:]) async throws -> T {
        let url = urls.apiURL(action: action, parameters: parameters)
        let data = try await http.data(from: url, headers: headers.dictionary).gunzippedIfNeeded()
        guard !data.isEmpty else { throw HTTPClientError.emptyResponse }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw XtreamError.invalidResponse
        }
    }

    // MARK: Account

    public func authenticate() async throws -> XtreamAccountInfo {
        let response = try await get(XtreamAuthResponse.self)
        guard let user = response.userInfo else { throw XtreamError.invalidResponse }
        guard user.auth else { throw XtreamError.authenticationFailed(user.message) }
        let status = user.status ?? "Active"
        if status.lowercased() != "active" {
            throw XtreamError.accountNotActive(status)
        }
        return XtreamAccountInfo(
            username: user.username ?? credentials.username,
            status: status,
            isTrial: user.isTrial,
            expiresAt: user.expDate,
            createdAt: user.createdAt,
            activeConnections: user.activeConnections,
            maxConnections: user.maxConnections,
            allowedOutputFormats: user.allowedOutputFormats,
            message: user.message?.nilIfBlank,
            serverTimeZone: response.serverInfo?.timezone,
            serverURL: response.serverInfo?.url
        )
    }

    // MARK: Catalogue

    func categories(action: String, type: ContentType) async throws -> [MediaCategory] {
        let list = try await get(LossyArray<XtreamCategoryDTO>.self, action: action).elements
        return list.map { MediaCategory(id: "\(type.rawValue)-\($0.id)", name: $0.name, type: type) }
    }

    public func liveCategories() async throws -> [MediaCategory] {
        try await categories(action: "get_live_categories", type: .live)
    }

    public func movieCategories() async throws -> [MediaCategory] {
        try await categories(action: "get_vod_categories", type: .movie)
    }

    public func seriesCategories() async throws -> [MediaCategory] {
        try await categories(action: "get_series_categories", type: .series)
    }

    public func liveStreams(format: XtreamOutputFormat) async throws -> [Channel] {
        let list = try await get(LossyArray<XtreamLiveStreamDTO>.self, action: "get_live_streams").elements
        return list.map { dto in
            var catchup: CatchupInfo?
            if dto.tvArchive {
                catchup = CatchupInfo(mode: .xtream, days: max(dto.tvArchiveDuration ?? 1, 1))
            }
            return Channel(
                id: "x\(dto.streamID)",
                name: dto.name,
                number: dto.number,
                logoURL: URL.lenient(dto.icon),
                categoryID: "live-\(dto.categoryID ?? "0")",
                streamURL: urls.liveURL(streamID: dto.streamID, format: format),
                epgID: dto.epgChannelID,
                catchup: catchup,
                xtreamStreamID: dto.streamID
            )
        }
    }

    public func movies() async throws -> [Movie] {
        let list = try await get(LossyArray<XtreamVODStreamDTO>.self, action: "get_vod_streams").elements
        return list.map { dto in
            let (name, yearInTitle) = TitleParser.splitYear(dto.name)
            return Movie(
                id: "x\(dto.streamID)",
                name: name,
                posterURL: URL.lenient(dto.icon),
                categoryID: "movie-\(dto.categoryID ?? "0")",
                streamURL: urls.movieURL(streamID: dto.streamID, containerExtension: dto.containerExtension),
                rating: dto.rating ?? dto.rating5.map { $0 * 2 },
                year: dto.year ?? yearInTitle,
                addedAt: dto.added,
                plot: dto.plot,
                xtreamStreamID: dto.streamID,
                containerExtension: dto.containerExtension
            )
        }
    }

    public func series() async throws -> [Series] {
        let list = try await get(LossyArray<XtreamSeriesDTO>.self, action: "get_series").elements
        return list.map { dto in
            let (name, yearInTitle) = TitleParser.splitYear(dto.name)
            return Series(
                id: "x\(dto.seriesID)",
                name: name,
                coverURL: URL.lenient(dto.cover),
                categoryID: "series-\(dto.categoryID ?? "0")",
                plot: dto.plot,
                rating: dto.rating,
                year: dto.year ?? yearInTitle,
                genre: dto.genre,
                cast: dto.cast,
                director: dto.director,
                addedAt: dto.lastModified,
                xtreamSeriesID: dto.seriesID
            )
        }
    }

    /// Downloads the whole catalogue (account + 6 lists in parallel).
    public func fetchContent(format preferredFormat: XtreamOutputFormat) async throws -> PlaylistContent {
        let account = try await authenticate()
        // Respect the panel's allowed formats: fall back to TS when HLS is not offered.
        let allowed = account.allowedOutputFormats.map { $0.lowercased() }
        let format: XtreamOutputFormat
        if !allowed.isEmpty && !allowed.contains(preferredFormat.rawValue) {
            format = allowed.contains("m3u8") ? .m3u8 : .ts
        } else {
            format = preferredFormat
        }

        // The lists load in parallel; `section` decides what a failure means.
        async let liveCategoryList = Self.attempt { try await liveCategories() }
        async let movieCategoryList = Self.attempt { try await movieCategories() }
        async let seriesCategoryList = Self.attempt { try await seriesCategories() }
        async let channelList = Self.attempt { try await liveStreams(format: format) }
        async let movieList = Self.attempt { try await movies() }
        async let seriesList = Self.attempt { try await series() }

        let resolvedLiveCats = try await Self.section(await liveCategoryList) { try await liveCategories() }
        let resolvedMovieCats = try await Self.section(await movieCategoryList) { try await movieCategories() }
        let resolvedSeriesCats = try await Self.section(await seriesCategoryList) { try await seriesCategories() }
        let resolvedChannels = try await Self.section(await channelList) { try await liveStreams(format: format) }
        let resolvedMovies = try await Self.section(await movieList) { try await movies() }
        let resolvedSeries = try await Self.section(await seriesList) { try await series() }

        return PlaylistContent(
            liveCategories: Self.categoriesWithOrphans(resolvedLiveCats, itemCategoryIDs: resolvedChannels.map(\.categoryID), type: .live),
            channels: resolvedChannels,
            movieCategories: Self.categoriesWithOrphans(resolvedMovieCats, itemCategoryIDs: resolvedMovies.map(\.categoryID), type: .movie),
            movies: resolvedMovies,
            seriesCategories: Self.categoriesWithOrphans(resolvedSeriesCats, itemCategoryIDs: resolvedSeries.map(\.categoryID), type: .series),
            series: resolvedSeries,
            providerEPGURLs: [urls.xmltvURL],
            account: account
        )
    }

    private static func attempt<T>(_ load: () async throws -> [T]) async -> Result<[T], Error> {
        do {
            return .success(try await load())
        } catch {
            return .failure(error)
        }
    }

    /// One list of the catalogue. A section the panel does not offer (some disable VOD or series: 404, an
    /// empty or non-list answer) is empty. Any other failure (network, server error, refusal) is retried once
    /// on its own, as busy panels refuse parallel requests, then thrown: a refresh that quietly lost a section
    /// would replace the saved catalogue with a partial one.
    private static func section<T>(_ first: Result<[T], Error>, retry: () async throws -> [T]) async throws -> [T] {
        switch first {
        case .success(let items):
            return items
        case .failure(let error):
            if isMissingSection(error) { return [] }
            try Task.checkCancellation()
            do {
                return try await retry()
            } catch let error where isMissingSection(error) {
                return []
            }
        }
    }

    static func isMissingSection(_ error: Error) -> Bool {
        switch error {
        case HTTPClientError.badStatus(404), HTTPClientError.emptyResponse, XtreamError.invalidResponse: return true
        default: return false
        }
    }

    /// Items sometimes reference categories the panel does not list: add an "Other" bucket for them.
    static func categoriesWithOrphans(_ categories: [MediaCategory], itemCategoryIDs: [String], type: ContentType) -> [MediaCategory] {
        let known = Set(categories.map(\.id))
        var result = categories
        var added = Set<String>()
        for id in itemCategoryIDs where !known.contains(id) && added.insert(id).inserted {
            result.append(MediaCategory(id: id, name: added.count == 1 ? "Other" : "Other \(added.count)", type: type))
        }
        return result
    }

    // MARK: Details

    public func movieDetails(streamID: Int) async throws -> MediaDetails {
        let response = try await get(XtreamVODInfoResponse.self, action: "get_vod_info", parameters: ["vod_id": String(streamID)])
        let info = response.info
        return MediaDetails(
            title: info?.name ?? response.movieData?.name ?? "",
            originalTitle: info?.originalName,
            plot: info?.plot,
            cast: info?.cast,
            director: info?.director,
            genre: info?.genre,
            releaseDate: info?.releaseDate,
            durationSeconds: info?.durationSecs,
            rating: info?.rating,
            posterURL: URL.lenient(info?.movieImage ?? info?.cover),
            backdropURL: Self.backdropURL(in: info?.backdrops ?? []),
            trailerYouTubeID: info?.youtubeTrailer,
            tmdbID: info?.tmdbID
        )
    }

    /// The first backdrop that can be an image. Panels often list TMDb links missing their file
    /// (`https://image.tmdb.org/t/p/w1280`, `…/w1280null`): the page would show nothing behind its title.
    static func backdropURL(in backdrops: [String]) -> URL? {
        backdrops.lazy.compactMap(URL.lenient).first { url in
            url.host?.hasSuffix("tmdb.org") != true || url.lastPathComponent.contains(".")
        }
    }

    public func seriesDetails(seriesID: Int) async throws -> SeriesDetails {
        let response = try await get(XtreamSeriesInfoResponse.self, action: "get_series_info", parameters: ["series_id": String(seriesID)])
        let info = response.info
        let details = MediaDetails(
            title: info?.name ?? "",
            plot: info?.plot,
            cast: info?.cast,
            director: info?.director,
            genre: info?.genre,
            releaseDate: info?.releaseDate,
            rating: info?.rating,
            posterURL: URL.lenient(info?.cover ?? info?.movieImage),
            backdropURL: Self.backdropURL(in: info?.backdrops ?? []),
            trailerYouTubeID: info?.youtubeTrailer,
            tmdbID: info?.tmdbID
        )
        let seasonMeta = Dictionary(response.seasons.map { ($0.seasonNumber, $0) }, uniquingKeysWith: { first, _ in first })
        let seasons: [Season] = response.episodes.keys.sorted().map { number in
            let episodes = (response.episodes[number] ?? []).enumerated().map { index, dto in
                Episode(
                    id: dto.id,
                    title: dto.title.map { Self.cleanEpisodeTitle($0) } ?? "Episode \(dto.episodeNumber ?? index + 1)",
                    season: number,
                    number: dto.episodeNumber ?? index + 1,
                    streamURL: urls.episodeURL(episodeID: dto.id, containerExtension: dto.containerExtension),
                    posterURL: URL.lenient(dto.info?.movieImage ?? dto.info?.cover),
                    plot: dto.info?.plot,
                    durationSeconds: dto.info?.durationSecs,
                    releaseDate: dto.info?.releaseDate,
                    rating: dto.info?.rating
                )
            }.sorted { $0.number < $1.number }
            let meta = seasonMeta[number]
            return Season(
                number: number,
                name: meta?.name ?? "Season \(number)",
                coverURL: URL.lenient(meta?.cover),
                overview: meta?.overview,
                episodes: episodes
            )
        }
        return SeriesDetails(info: details, seasons: seasons)
    }

    /// "Copper Valley - S01E01 - Pilot" -> "Pilot"
    static func cleanEpisodeTitle(_ title: String) -> String {
        if let parsed = TitleParser.parseEpisode(title), !parsed.episodeTitle.hasPrefix("Episode ") {
            return parsed.episodeTitle
        }
        return title
    }

    // MARK: Guide

    /// Now / next for one channel, without downloading the full XMLTV.
    public func shortEPG(streamID: Int, epgChannelID: String, limit: Int = 4) async throws -> [EPGProgram] {
        let response = try await get(XtreamShortEPGResponse.self, action: "get_short_epg", parameters: [
            "stream_id": String(streamID),
            "limit": String(limit),
        ])
        return response.listings.map {
            EPGProgram(channelID: epgChannelID, title: $0.title, summary: $0.description, start: $0.start, end: $0.end)
        }.sorted { $0.start < $1.start }
    }

    /// Full archive listing for one channel (used for catch-up when no XMLTV is available).
    public func fullEPG(streamID: Int, epgChannelID: String) async throws -> [EPGProgram] {
        let response = try await get(XtreamShortEPGResponse.self, action: "get_simple_data_table", parameters: [
            "stream_id": String(streamID),
        ])
        return response.listings.map {
            EPGProgram(channelID: epgChannelID, title: $0.title, summary: $0.description, start: $0.start, end: $0.end)
        }.sorted { $0.start < $1.start }
    }
}

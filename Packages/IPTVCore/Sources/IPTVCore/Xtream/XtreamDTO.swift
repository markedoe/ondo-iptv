import Foundation

// Raw payloads of `player_api.php`. Every field is decoded leniently.

struct XtreamAuthResponse: Decodable {
    var userInfo: XtreamUserInfo?
    var serverInfo: XtreamServerInfo?

    enum CodingKeys: String, CodingKey {
        case userInfo = "user_info"
        case serverInfo = "server_info"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userInfo = try? container.decodeIfPresent(XtreamUserInfo.self, forKey: .userInfo)
        serverInfo = try? container.decodeIfPresent(XtreamServerInfo.self, forKey: .serverInfo)
    }
}

struct XtreamUserInfo: Decodable {
    var username: String?
    var auth: Bool
    var status: String?
    var expDate: Date?
    var createdAt: Date?
    var isTrial: Bool
    var activeConnections: Int?
    var maxConnections: Int?
    var allowedOutputFormats: [String]
    var message: String?

    enum CodingKeys: String, CodingKey {
        case username, auth, status, message
        case expDate = "exp_date"
        case createdAt = "created_at"
        case isTrial = "is_trial"
        case activeConnections = "active_cons"
        case maxConnections = "max_connections"
        case allowedOutputFormats = "allowed_output_formats"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        username = c.lenientString(.username)
        auth = c.lenientBool(.auth) ?? false
        status = c.lenientString(.status)
        expDate = c.lenientDate(.expDate)
        createdAt = c.lenientDate(.createdAt)
        isTrial = c.lenientBool(.isTrial) ?? false
        activeConnections = c.lenientInt(.activeConnections)
        maxConnections = c.lenientInt(.maxConnections)
        allowedOutputFormats = c.lenientStringArray(.allowedOutputFormats)
        message = c.lenientString(.message)
    }
}

struct XtreamServerInfo: Decodable {
    var url: String?
    var port: String?
    var httpsPort: String?
    var serverProtocol: String?
    var timezone: String?
    var timestampNow: Int?

    enum CodingKeys: String, CodingKey {
        case url, port, timezone
        case httpsPort = "https_port"
        case serverProtocol = "server_protocol"
        case timestampNow = "timestamp_now"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = c.lenientString(.url)
        port = c.lenientString(.port)
        httpsPort = c.lenientString(.httpsPort)
        serverProtocol = c.lenientString(.serverProtocol)
        timezone = c.lenientString(.timezone)
        timestampNow = c.lenientInt(.timestampNow)
    }
}

struct XtreamCategoryDTO: Decodable {
    var id: String
    var name: String
    var parentID: Int?

    enum CodingKeys: String, CodingKey {
        case id = "category_id"
        case name = "category_name"
        case parentID = "parent_id"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientString(.id) else {
            throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "missing category_id")
        }
        self.id = id
        name = c.lenientString(.name) ?? "Uncategorized"
        parentID = c.lenientInt(.parentID)
    }
}

struct XtreamLiveStreamDTO: Decodable {
    var streamID: Int
    var number: Int?
    var name: String
    var icon: String?
    var epgChannelID: String?
    var categoryID: String?
    var tvArchive: Bool
    var tvArchiveDuration: Int?
    var directSource: String?
    var added: Date?

    enum CodingKeys: String, CodingKey {
        case num, name, added
        case streamID = "stream_id"
        case icon = "stream_icon"
        case epgChannelID = "epg_channel_id"
        case categoryID = "category_id"
        case categoryIDs = "category_ids"
        case tvArchive = "tv_archive"
        case tvArchiveDuration = "tv_archive_duration"
        case directSource = "direct_source"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let streamID = c.lenientInt(.streamID) else {
            throw DecodingError.dataCorruptedError(forKey: .streamID, in: c, debugDescription: "missing stream_id")
        }
        self.streamID = streamID
        number = c.lenientInt(.num)
        name = c.lenientString(.name)?.trimmingCharacters(in: .whitespaces) ?? "Channel \(streamID)"
        icon = c.lenientString(.icon)
        epgChannelID = c.lenientString(.epgChannelID)?.nilIfBlank
        categoryID = c.lenientString(.categoryID) ?? (try? c.decodeIfPresent([Int].self, forKey: .categoryIDs))?.first.map(String.init)
        tvArchive = c.lenientBool(.tvArchive) ?? false
        tvArchiveDuration = c.lenientInt(.tvArchiveDuration)
        directSource = c.lenientString(.directSource)?.nilIfBlank
        added = c.lenientDate(.added)
    }
}

struct XtreamVODStreamDTO: Decodable {
    var streamID: Int
    var name: String
    var icon: String?
    var categoryID: String?
    var rating: Double?
    var rating5: Double?
    var added: Date?
    var containerExtension: String?
    var directSource: String?
    var year: Int?
    var plot: String?

    enum CodingKeys: String, CodingKey {
        case name, rating, added, year, plot
        case streamID = "stream_id"
        case icon = "stream_icon"
        case categoryID = "category_id"
        case rating5 = "rating_5based"
        case containerExtension = "container_extension"
        case directSource = "direct_source"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let streamID = c.lenientInt(.streamID) else {
            throw DecodingError.dataCorruptedError(forKey: .streamID, in: c, debugDescription: "missing stream_id")
        }
        self.streamID = streamID
        name = c.lenientString(.name)?.trimmingCharacters(in: .whitespaces) ?? "Movie \(streamID)"
        icon = c.lenientString(.icon)
        categoryID = c.lenientString(.categoryID)
        rating = c.lenientDouble(.rating)
        rating5 = c.lenientDouble(.rating5)
        added = c.lenientDate(.added)
        containerExtension = c.lenientString(.containerExtension)?.nilIfBlank
        directSource = c.lenientString(.directSource)?.nilIfBlank
        year = c.lenientInt(.year)
        plot = c.lenientString(.plot)?.nilIfBlank
    }
}

struct XtreamSeriesDTO: Decodable {
    var seriesID: Int
    var name: String
    var cover: String?
    var plot: String?
    var genre: String?
    var releaseDate: String?
    var rating: Double?
    var categoryID: String?
    var lastModified: Date?
    var year: Int?

    enum CodingKeys: String, CodingKey {
        case name, cover, plot, genre, rating, year
        case seriesID = "series_id"
        case releaseDate
        case releaseDateSnake = "release_date"
        case categoryID = "category_id"
        case lastModified = "last_modified"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let seriesID = c.lenientInt(.seriesID) else {
            throw DecodingError.dataCorruptedError(forKey: .seriesID, in: c, debugDescription: "missing series_id")
        }
        self.seriesID = seriesID
        name = c.lenientString(.name)?.trimmingCharacters(in: .whitespaces) ?? "Series \(seriesID)"
        cover = c.lenientString(.cover)
        plot = c.lenientString(.plot)?.nilIfBlank
        genre = c.lenientString(.genre)?.nilIfBlank
        releaseDate = (c.lenientString(.releaseDate) ?? c.lenientString(.releaseDateSnake))?.nilIfBlank
        rating = c.lenientDouble(.rating)
        categoryID = c.lenientString(.categoryID)
        lastModified = c.lenientDate(.lastModified)
        year = c.lenientInt(.year) ?? releaseDate.flatMap { Int($0.prefix(4)) }
    }
}

/// `info` object shared by `get_vod_info` and `get_series_info`.
struct XtreamInfoDTO: Decodable {
    var name: String?
    var originalName: String?
    var plot: String?
    var cast: String?
    var director: String?
    var genre: String?
    var releaseDate: String?
    var durationSecs: Int?
    var rating: Double?
    var movieImage: String?
    var cover: String?
    var backdrops: [String]
    var youtubeTrailer: String?
    var tmdbID: String?

    enum CodingKeys: String, CodingKey {
        case name, plot, description, cast, actors, director, genre, rating, cover
        case originalName = "o_name"
        case releaseDate = "releasedate"
        case releaseDateCamel = "releaseDate"
        case releaseDateSnake = "release_date"
        case durationSecs = "duration_secs"
        case movieImage = "movie_image"
        case coverBig = "cover_big"
        case backdropPath = "backdrop_path"
        case youtubeTrailer = "youtube_trailer"
        case tmdbID = "tmdb_id"
        case tmdb
    }

    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: CodingKeys.self) else {
            // `info: []` when empty.
            backdrops = []
            return
        }
        name = c.lenientString(.name)?.nilIfBlank
        originalName = c.lenientString(.originalName)?.nilIfBlank
        plot = (c.lenientString(.plot) ?? c.lenientString(.description))?.nilIfBlank
        cast = (c.lenientString(.cast) ?? c.lenientString(.actors))?.nilIfBlank
        director = c.lenientString(.director)?.nilIfBlank
        genre = c.lenientString(.genre)?.nilIfBlank
        releaseDate = (c.lenientString(.releaseDate) ?? c.lenientString(.releaseDateCamel) ?? c.lenientString(.releaseDateSnake))?.nilIfBlank
        durationSecs = c.lenientInt(.durationSecs)
        rating = c.lenientDouble(.rating)
        movieImage = c.lenientString(.movieImage)?.nilIfBlank
        cover = (c.lenientString(.cover) ?? c.lenientString(.coverBig))?.nilIfBlank
        backdrops = c.lenientStringArray(.backdropPath).filter { !$0.isEmpty }
        youtubeTrailer = c.lenientString(.youtubeTrailer)?.nilIfBlank
        tmdbID = (c.lenientString(.tmdbID) ?? c.lenientString(.tmdb))?.nilIfBlank
    }
}

struct XtreamVODInfoResponse: Decodable {
    var info: XtreamInfoDTO?
    var movieData: XtreamVODStreamDTO?

    enum CodingKeys: String, CodingKey {
        case info
        case movieData = "movie_data"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        info = try? c.decodeIfPresent(XtreamInfoDTO.self, forKey: .info)
        movieData = try? c.decodeIfPresent(XtreamVODStreamDTO.self, forKey: .movieData)
    }
}

struct XtreamSeasonDTO: Decodable {
    var seasonNumber: Int
    var name: String?
    var overview: String?
    var cover: String?

    enum CodingKeys: String, CodingKey {
        case name, overview, cover
        case seasonNumber = "season_number"
        case coverBig = "cover_big"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let number = c.lenientInt(.seasonNumber) else {
            throw DecodingError.dataCorruptedError(forKey: .seasonNumber, in: c, debugDescription: "missing season_number")
        }
        seasonNumber = number
        name = c.lenientString(.name)?.nilIfBlank
        overview = c.lenientString(.overview)?.nilIfBlank
        cover = (c.lenientString(.coverBig) ?? c.lenientString(.cover))?.nilIfBlank
    }
}

struct XtreamEpisodeDTO: Decodable {
    var id: String
    var episodeNumber: Int?
    var title: String?
    var containerExtension: String?
    var season: Int?
    var info: XtreamInfoDTO?
    var directSource: String?

    enum CodingKeys: String, CodingKey {
        case id, title, season, info
        case episodeNumber = "episode_num"
        case containerExtension = "container_extension"
        case directSource = "direct_source"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientString(.id) else {
            throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "missing episode id")
        }
        self.id = id
        episodeNumber = c.lenientInt(.episodeNumber)
        title = c.lenientString(.title)?.nilIfBlank
        containerExtension = c.lenientString(.containerExtension)?.nilIfBlank
        season = c.lenientInt(.season)
        info = try? c.decodeIfPresent(XtreamInfoDTO.self, forKey: .info)
        directSource = c.lenientString(.directSource)?.nilIfBlank
    }
}

struct XtreamSeriesInfoResponse: Decodable {
    var seasons: [XtreamSeasonDTO]
    var info: XtreamInfoDTO?
    /// Season number -> episodes.
    var episodes: [Int: [XtreamEpisodeDTO]]

    enum CodingKeys: String, CodingKey {
        case seasons, info, episodes
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        seasons = (try? c.decodeIfPresent(LossyArray<XtreamSeasonDTO>.self, forKey: .seasons))?.elements ?? []
        info = try? c.decodeIfPresent(XtreamInfoDTO.self, forKey: .info)

        var result: [Int: [XtreamEpisodeDTO]] = [:]
        if let keyed = try? c.nestedContainer(keyedBy: AnyCodingKey.self, forKey: .episodes) {
            // {"1": [...], "2": [...]}
            for key in keyed.allKeys {
                let list = (try? keyed.decode(LossyArray<XtreamEpisodeDTO>.self, forKey: key))?.elements ?? []
                let season = Int(key.stringValue) ?? list.first?.season ?? 1
                result[season, default: []].append(contentsOf: list)
            }
        } else if let nested = try? c.decodeIfPresent([LossyArray<XtreamEpisodeDTO>].self, forKey: .episodes) {
            // [[...], [...]] — one array per season.
            for list in nested {
                for episode in list.elements {
                    result[episode.season ?? 1, default: []].append(episode)
                }
            }
        } else if let flat = try? c.decodeIfPresent(LossyArray<XtreamEpisodeDTO>.self, forKey: .episodes) {
            for episode in flat.elements {
                result[episode.season ?? 1, default: []].append(episode)
            }
        }
        episodes = result
    }
}

struct XtreamShortEPGResponse: Decodable {
    var listings: [XtreamEPGListingDTO]

    enum CodingKeys: String, CodingKey {
        case listings = "epg_listings"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        listings = (try? c.decodeIfPresent(LossyArray<XtreamEPGListingDTO>.self, forKey: .listings))?.elements ?? []
        // Panels send every title and description in base64, a few send them all as plain text. Deciding for the
        // whole answer keeps a plain "Film" or "Show", which is valid base64 too, from turning into garbage.
        let texts = listings.flatMap { [$0.title, $0.description ?? ""] }.filter { !$0.isEmpty }
        guard texts.allSatisfy({ XtreamEPGListingDTO.base64Text($0) != nil }) else { return }
        for index in listings.indices {
            listings[index].title = XtreamEPGListingDTO.base64Text(listings[index].title) ?? listings[index].title
            listings[index].description = listings[index].description.flatMap(XtreamEPGListingDTO.base64Text)?.nilIfBlank
        }
    }
}

struct XtreamEPGListingDTO: Decodable {
    var title: String
    var description: String?
    var start: Date
    var end: Date
    var channelID: String?
    var hasArchive: Bool

    enum CodingKeys: String, CodingKey {
        case title, description, start, end
        case startTimestamp = "start_timestamp"
        case stopTimestamp = "stop_timestamp"
        case channelID = "channel_id"
        case hasArchive = "has_archive"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let start = c.lenientDate(.startTimestamp), let end = c.lenientDate(.stopTimestamp) else {
            throw DecodingError.dataCorruptedError(forKey: .startTimestamp, in: c, debugDescription: "missing timestamps")
        }
        self.start = start
        self.end = end
        // As sent: `XtreamShortEPGResponse` decodes them once it knows whether the panel uses base64.
        title = c.lenientString(.title) ?? ""
        description = c.lenientString(.description)?.nilIfBlank
        channelID = c.lenientString(.channelID)
        hasArchive = c.lenientBool(.hasArchive) ?? false
    }

    /// The text `value` holds in base64, or nil when it is not base64 of readable UTF-8 text. Strict: a plain
    /// title has spaces or punctuation that base64 does not, and garbage decoded from one often has control
    /// characters.
    static func base64Text(_ value: String) -> String? {
        guard let data = Data(base64Encoded: value.trimmingCharacters(in: .whitespacesAndNewlines).filter { !$0.isNewline }),
              let text = String(data: data, encoding: .utf8), !text.isEmpty,
              !text.unicodeScalars.contains(where: { ($0.value < 0x20 && !"\t\n\r".unicodeScalars.contains($0)) || (0x7F...0x9F).contains($0.value) })
        else { return nil }
        return text
    }
}

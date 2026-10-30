#if DEBUG
import Foundation
import IPTVCore

/// Deterministic catalogue for UI tests and App Store screenshots (`-uitest-fixture` launch argument):
/// fictional movies and series with generated posters, backdrops and full details, plus a few live
/// channels with a guide generated around the current time. Streams are Apple's public HLS samples.
enum UITestFixture {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("-uitest-fixture")
    /// `-uitest-showcase`: a lived-in library (continue watching, My List, history, a reminder).
    static let isShowcase = ProcessInfo.processInfo.arguments.contains("-uitest-showcase")
    /// `-uitest-multiview`: opens the multiview with four channels (iPad / Mac).
    static let opensMultiview = ProcessInfo.processInfo.arguments.contains("-uitest-multiview")
    /// `-uitest-languages`: a few movie categories tagged by language (|EN|, |FR|, |DE|, |PT|), so Home suggests
    /// keeping only the viewer's.
    static let hasLanguages = ProcessInfo.processInfo.arguments.contains("-uitest-languages")

    private static let hls = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
    private static let hlsAdvanced = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

    static var artworkFolder: URL {
        AppPaths.importedPlaylists.appendingPathComponent("fixture-artwork", isDirectory: true)
    }

    // MARK: - Movies and series

    struct FixtureMovie {
        let title: String
        let year: Int
        let category: String
        let genre: String
        let rating: Double
        let minutes: Int
        let director: String
        let cast: String
        let plot: String
        let hue: Double
        var font = "AvenirNext-Heavy"

        var slug: String { UITestFixture.slug(title) }
    }

    struct FixtureSeries {
        let title: String
        let year: Int
        let category: String
        let genre: String
        let rating: Double
        let cast: String
        let plot: String
        let hue: Double
        let seasons: [[String]]
        var font = "AvenirNext-Heavy"

        var slug: String { UITestFixture.slug(title) }
    }

    /// Years are relative to today so the catalogue always has new releases (home screen, screenshots).
    static let thisYear = Calendar.current.component(.year, from: .now)

    static let movies: [FixtureMovie] = [
        FixtureMovie(title: "Northern Lights", year: thisYear, category: "Drama", genre: "Drama", rating: 7.8, minutes: 128,
                     director: "Maren Holt", cast: "Ingrid Saelen, Tomas Vik, Ada Lunde",
                     plot: "On a remote Arctic island, a lighthouse keeper finds a letter that rewrites everything she knew about her family.", hue: 0.55),
        FixtureMovie(title: "Glass City", year: thisYear, category: "Science Fiction", genre: "Science Fiction, Thriller", rating: 8.1, minutes: 134,
                     director: "Imani Cole", cast: "Rafael Ortiz, Mei Tanaka, Jonah Price",
                     plot: "In a city where every wall is a screen, an engineer discovers the one place no camera can see.", hue: 0.5, font: "Futura-CondensedExtraBold"),
        FixtureMovie(title: "Silent Harbor", year: thisYear - 1, category: "Thriller", genre: "Thriller", rating: 7.2, minutes: 112,
                     director: "Luca Ferri", cast: "Nora Kade, Elias Brandt",
                     plot: "When a cargo ship drifts into a sleeping port with no crew aboard, a harbor pilot is the only one who wants answers.", hue: 0.62, font: "Didot-Bold"),
        FixtureMovie(title: "The Last Signal", year: thisYear, category: "Science Fiction", genre: "Science Fiction", rating: 7.6, minutes: 126,
                     director: "Sora Kim", cast: "Leah Moreau, Daniel Okafor",
                     plot: "A radio astronomer receives a message from a probe that was lost forty years ago.", hue: 0.72, font: "Futura-CondensedExtraBold"),
        FixtureMovie(title: "Summer in Lisbon", year: thisYear - 1, category: "Drama", genre: "Romance, Comedy", rating: 7.0, minutes: 102,
                     director: "Ines Duarte", cast: "Clara Mendes, Hugo Lambert",
                     plot: "Two strangers share a rented flat by mistake, and a summer they will never forget.", hue: 0.08, font: "Georgia-Bold"),
        FixtureMovie(title: "Midnight Orchard", year: thisYear - 3, category: "Thriller", genre: "Mystery", rating: 6.9, minutes: 104,
                     director: "Paul Archer", cast: "Grace Whitfield, Owen Hart",
                     plot: "A family returns to its abandoned orchard, where the trees still bear fruit every night.", hue: 0.78, font: "Didot-Bold"),
        FixtureMovie(title: "The Long Road", year: thisYear - 2, category: "Drama", genre: "Adventure, Drama", rating: 7.5, minutes: 121,
                     director: "Amara Diallo", cast: "Samuel Reyes, Lina Novak",
                     plot: "A father and his daughter cross a continent in an old van to keep a promise.", hue: 0.1),
        FixtureMovie(title: "Echoes of Jade", year: thisYear - 3, category: "Science Fiction", genre: "Fantasy", rating: 7.1, minutes: 131,
                     director: "Wei Lin", cast: "Hana Mori, Kai Chen",
                     plot: "An apprentice cartographer maps a valley that changes shape every time she looks away.", hue: 0.38, font: "Georgia-Bold"),
        FixtureMovie(title: "Iron Tide", year: thisYear, category: "Thriller", genre: "Action", rating: 6.7, minutes: 115,
                     director: "Max Keller", cast: "Ruben Stone, Ayla Gray",
                     plot: "A disgraced diver has one night to stop a heist planned beneath a storm-battered oil rig.", hue: 0.6, font: "Futura-CondensedExtraBold"),
        FixtureMovie(title: "Quiet Hours", year: thisYear - 2, category: "Drama", genre: "Drama", rating: 7.3, minutes: 99,
                     director: "Elsa Brun", cast: "Margot Lys, Theo Blanc",
                     plot: "A night nurse and an insomniac radio host keep each other company until dawn.", hue: 0.68, font: "Didot-Bold"),
        FixtureMovie(title: "Red Canyon", year: thisYear - 2, category: "Thriller", genre: "Western", rating: 6.8, minutes: 118,
                     director: "Cody Rivers", cast: "Jake Turner, Rosa Delgado",
                     plot: "A sheriff and an outlaw are stranded together when a flash flood cuts the only road out of the canyon.", hue: 0.02),
        FixtureMovie(title: "Paper Wings", year: thisYear - 1, category: "Science Fiction", genre: "Animation, Family", rating: 7.4, minutes: 96,
                     director: "Noa Levi", cast: "Voices of Mia Russo, Leo Park",
                     plot: "A shy origami crane comes to life and sets off to find the girl who folded it.", hue: 0.88, font: "AvenirNext-Bold"),
    ]

    static let series: [FixtureSeries] = [
        FixtureSeries(title: "Harbor Lights", year: thisYear - 1, category: "Drama", genre: "Drama", rating: 8.2, cast: "Ada Lunde, Tomas Vik, Grace Whitfield",
                      plot: "A fishing town, a missing boat and three families who all have something to hide.", hue: 0.58,
                      seasons: [["The Storm", "Low Tide", "Old Debts", "Lanterns"], ["Return", "Undertow", "The Inquiry", "Safe Harbor"]]),
        FixtureSeries(title: "The Archive", year: thisYear, category: "Mystery", genre: "Mystery", rating: 7.9, cast: "Clara Mendes, Owen Hart",
                      plot: "A young archivist uncovers a box of letters that should not exist.", hue: 0.11,
                      seasons: [["Box 117", "Ink", "The Reader"], ["Missing Pages", "Copies", "The Original"]], font: "Didot-Bold"),
        FixtureSeries(title: "Blue Horizon", year: thisYear - 2, category: "Mystery", genre: "Science Fiction", rating: 7.7, cast: "Leah Moreau, Kai Chen",
                      plot: "The crew of a research station at the edge of the solar system loses contact with Earth.", hue: 0.63,
                      seasons: [["Signal Lost", "Drift", "The Visitor", "Pressure", "Horizon"]], font: "Futura-CondensedExtraBold"),
        FixtureSeries(title: "Kitchen Stories", year: thisYear - 1, category: "Drama", genre: "Comedy", rating: 7.4, cast: "Hugo Lambert, Rosa Delgado",
                      plot: "A tiny restaurant, a stubborn chef and a team that never stops arguing.", hue: 0.04,
                      seasons: [["Opening Night", "The Critic", "Family Meal", "Last Service"]], font: "Georgia-Bold"),
        FixtureSeries(title: "Wild North", year: thisYear - 3, category: "Documentary", genre: "Documentary", rating: 8.5, cast: "Narrated by Maren Holt",
                      plot: "A year in the wildest landscapes of the far north, from the polar night to the midnight sun.", hue: 0.47,
                      seasons: [["Winter", "Spring", "Summer", "Autumn"]]),
    ]

    static func slug(_ title: String) -> String {
        title.lowercased().map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined()
    }

    // MARK: - Live channels

    private static let channels: [(id: String, name: String, group: String, number: Int)] = [
        ("ondo.news", "Ondo News", "News", 1),
        ("ondo.world", "World 24", "News", 2),
        ("ondo.sport", "Sport Live", "Sports", 3),
        ("ondo.arena", "Arena HD", "Sports", 4),
        ("ondo.cinema", "Cinema Club", "Entertainment", 5),
        ("ondo.kids", "Kids Planet", "Kids", 6),
        ("ondo.docs", "Curio Docs", "Documentaries", 7),
        ("ondo.music", "Music Wave", "Music", 8),
    ]

    private static let programmes = [
        "Morning Briefing", "World Report", "Live Match", "Highlights", "The Big Movie",
        "Cartoon Time", "Ocean Life", "Top Charts", "Late Show", "Tech Today", "Weather", "Documentary",
    ]

    // MARK: - Files

    /// - Parameter artwork: folder holding the generated artwork (file URLs exercise the image pipeline).
    static func m3u(epgURL: URL, artwork: URL? = nil) -> String {
        func logo(_ name: String) -> String {
            artwork.map { " tvg-logo=\"\($0.appendingPathComponent(name).absoluteString)\"" } ?? ""
        }
        var lines = ["#EXTM3U url-tvg=\"\(epgURL.absoluteString)\""]
        for channel in channels {
            let catchup = channel.group == "News" ? " catchup=\"shift\" catchup-days=\"3\"" : ""
            lines.append("#EXTINF:-1 tvg-id=\"\(channel.id)\" tvg-chno=\"\(channel.number)\"\(logo("\(channel.id).png")) group-title=\"\(channel.group)\"\(catchup),\(channel.name)")
            lines.append(channel.number.isMultiple(of: 2) ? hlsAdvanced : hls)
        }
        for movie in movies {
            lines.append("#EXTINF:\(movie.minutes * 60)\(logo("poster-\(movie.slug).png")) group-title=\"Movies | \(movie.category)\",\(movie.title) (\(movie.year))")
            lines.append(hlsAdvanced)
        }
        if hasLanguages {
            for (index, group) in ["|EN| Movies", "|FR| Films", "|DE| Filme", "|PT| Filmes"].enumerated() {
                lines.append("#EXTINF:5400 tvg-type=\"movie\" group-title=\"\(group)\",Language Sample \(index + 1) (2020)")
                lines.append(hlsAdvanced)
            }
        }
        for show in series {
            for (seasonIndex, season) in show.seasons.enumerated() {
                for episode in season.indices {
                    let code = String(format: "S%02dE%02d", seasonIndex + 1, episode + 1)
                    lines.append("#EXTINF:2700\(logo("poster-\(show.slug).png")) group-title=\"Series | \(show.category)\",\(show.title) (\(show.year)) \(code)")
                    lines.append(hls)
                }
            }
        }
        return lines.joined(separator: "\n") + "\n"
    }

    /// Channel logos, posters, backdrops and episode stills.
    static func writeArtwork(to folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        func write(_ data: Data?, _ name: String) throws {
            if let data { try data.write(to: folder.appendingPathComponent(name)) }
        }
        for (index, channel) in channels.enumerated() {
            try write(FixtureArtwork.logo(title: channel.name, hue: Double(index) / Double(channels.count)), "\(channel.id).png")
        }
        for (index, movie) in movies.enumerated() {
            let style = FixtureArtwork.Style(hue: movie.hue, seed: UInt64(index + 1), font: movie.font)
            try write(FixtureArtwork.poster(title: movie.title, style: style), "poster-\(movie.slug).png")
            try write(FixtureArtwork.backdrop(style: style), "backdrop-\(movie.slug).png")
        }
        for (index, show) in series.enumerated() {
            let style = FixtureArtwork.Style(hue: show.hue, seed: UInt64(100 + index), font: show.font)
            try write(FixtureArtwork.poster(title: show.title, style: style), "poster-\(show.slug).png")
            try write(FixtureArtwork.backdrop(style: style), "backdrop-\(show.slug).png")
            for episode in 0..<show.seasons.reduce(0, { $0 + $1.count }) {
                let still = FixtureArtwork.Style(hue: show.hue + Double(episode % 5) * 0.015, seed: UInt64(1_000 + index * 50 + episode), font: show.font)
                try write(FixtureArtwork.backdrop(style: still, width: 480, height: 270), "still-\(show.slug)-\(episode).png")
            }
        }
    }

    static func xmltv(now: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmss Z"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        let calendar = Calendar.current
        let start = calendar.date(bySettingHour: calendar.component(.hour, from: now), minute: 0, second: 0, of: now)!
            .addingTimeInterval(-6 * 3600)
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n"
        for channel in channels {
            xml += "<channel id=\"\(channel.id)\"><display-name>\(channel.name)</display-name></channel>\n"
        }
        for (index, channel) in channels.enumerated() {
            var cursor = start
            var slot = index
            while cursor < now.addingTimeInterval(24 * 3600) {
                let duration = Double([30, 45, 60, 90][slot % 4]) * 60
                let title = programmes[slot % programmes.count]
                xml += "<programme start=\"\(formatter.string(from: cursor))\" stop=\"\(formatter.string(from: cursor.addingTimeInterval(duration)))\" channel=\"\(channel.id)\">"
                xml += "<title>\(title)</title><desc>\(title) on \(channel.name).</desc><category>\(channel.group)</category></programme>\n"
                cursor = cursor.addingTimeInterval(duration)
                slot += 1
            }
        }
        return xml + "</tv>\n"
    }

    // MARK: - Library

    /// Details as an Xtream provider would return them; showcase extras for screenshots.
    @MainActor
    static func decorate(_ model: AppModel) async {
        injectDetails(into: model)
        guard isShowcase || opensMultiview else { return }
        let channels = model.content.channels
        // The guide loads in the background: reminders need it.
        for _ in 0..<50 where model.epg.isEmpty {
            try? await Task.sleep(for: .milliseconds(100))
        }
        if isShowcase {
            await showcase(model, channels: channels)
        }
        if opensMultiview, AppModel.supportsMultiview {
            for channel in channels.prefix(4) { model.addToMultiview(channel) }
        }
    }

    @MainActor
    private static func injectDetails(into model: AppModel) {
        let folder = artworkFolder
        for movie in model.content.movies {
            guard let info = movies.first(where: { $0.title == movie.name }) else { continue }
            model.movieDetailsCache[movie.id] = MediaDetails(
                title: info.title, plot: info.plot, cast: info.cast, director: info.director, genre: info.genre,
                releaseDate: String(info.year), durationSeconds: info.minutes * 60, rating: info.rating,
                posterURL: movie.posterURL, backdropURL: folder.appendingPathComponent("backdrop-\(info.slug).png")
            )
        }
        for show in model.content.series {
            guard let info = series.first(where: { $0.title == show.name }) else { continue }
            var episodeIndex = 0
            let grouped = Dictionary(grouping: show.episodes, by: \.season)
            var seasons: [Season] = []
            for number in grouped.keys.sorted() {
                let titles = info.seasons.indices.contains(number - 1) ? info.seasons[number - 1] : []
                var episodes: [Episode] = []
                for episode in (grouped[number] ?? []).sorted(by: { $0.number < $1.number }) {
                    var copy = episode
                    if titles.indices.contains(episode.number - 1) { copy.title = titles[episode.number - 1] }
                    copy.plot = "\(info.plot) Season \(number), episode \(episode.number)."
                    copy.durationSeconds = 2_700
                    copy.posterURL = folder.appendingPathComponent("still-\(info.slug)-\(episodeIndex).png")
                    episodeIndex += 1
                    episodes.append(copy)
                }
                seasons.append(Season(number: number, name: String(localized: "Season \(number)"), episodes: episodes))
            }
            model.seriesDetailsCache[show.id] = SeriesDetails(
                info: MediaDetails(
                    title: info.title, plot: info.plot, cast: info.cast, genre: info.genre, releaseDate: String(info.year),
                    rating: info.rating, posterURL: show.coverURL, backdropURL: folder.appendingPathComponent("backdrop-\(info.slug).png")
                ),
                seasons: seasons
            )
        }
    }

    @MainActor
    private static func showcase(_ model: AppModel, channels: [Channel]) async {
        let playlistID = model.activePlaylist?.id ?? UUID()
        for channel in channels.prefix(4) { model.toggleFavorite(channel) }
        for channel in channels.prefix(3).reversed() {
            model.recordStart(PlaybackItem(
                reference: MediaReference(playlistID: playlistID, type: .live, itemID: channel.id),
                title: channel.name,
                streamURL: channel.streamURL,
                isLive: true
            ))
        }
        // My List.
        for title in ["The Archive", "Northern Lights", "Glass City"] {
            if let movie = model.content.movies.first(where: { $0.name == title }) { model.toggleFavorite(movie) }
            if let show = model.content.series.first(where: { $0.name == title }) { model.toggleFavorite(show) }
        }
        // Continue watching: a movie and a series episode.
        if let movie = model.content.movies.first(where: { $0.name == "Glass City" }), let item = model.downloadItem(for: movie) {
            model.recordStart(item)
            model.recordProgress(item, position: 3_100, duration: 8_040)
        }
        if let show = model.content.series.first(where: { $0.name == "Harbor Lights" }),
           let details = try? await model.details(for: show),
           let episode = details.seasons.first?.episodes.dropFirst(2).first,
           let item = model.downloadItem(for: episode, in: show) {
            model.recordStart(item)
            model.recordProgress(item, position: 1_600, duration: 2_700)
        }
        if let channel = channels.dropFirst(2).first,
           let upcoming = model.epg.upcoming(for: channel, count: 3).last {
            await model.toggleReminder(for: upcoming, on: channel)
        }
    }

    /// Writes the fixture files and returns the playlist to add.
    static func makePlaylist() throws -> Playlist {
        let epgURL = AppPaths.importedPlaylists.appendingPathComponent("fixture-guide.xml")
        try Data(xmltv().utf8).write(to: epgURL)
        try writeArtwork(to: artworkFolder)
        let fileName = "fixture-playlist.m3u"
        try Data(m3u(epgURL: epgURL, artwork: artworkFolder).utf8).write(to: AppPaths.importedPlaylist(fileName))
        return Playlist(name: "Demo", source: .local(fileName: fileName), autoRefreshHours: 0)
    }
}
#endif

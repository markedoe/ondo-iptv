import XCTest
import IPTVCore
@testable import Ondo

/// The names IPTVCore gives in English where the provider gives none ("Uncategorized", "Other 2", "Channel 12",
/// "Movie 7", "Series 3", "Episode 4") read in the app's language; their ids stay, and a name of the provider's own
/// stays as it is.
final class PlaceholderNamesAppTests: XCTestCase {
    private let content = PlaylistContent(
        liveCategories: [
            MediaCategory(id: "live-1a2b", name: "Uncategorized", type: .live),
            MediaCategory(id: "8", name: "Other", type: .live),
            MediaCategory(id: "9", name: "Other 2", type: .live),
        ],
        channels: [
            Channel(id: "x12", name: "Channel 12", categoryID: "live-1a2b", streamURL: URL(string: "http://panel.example.com/live/u/p/12.ts")!, xtreamStreamID: 12),
            Channel(id: "x14", name: "Channel 5", categoryID: "8", streamURL: URL(string: "http://panel.example.com/live/u/p/14.ts")!, xtreamStreamID: 14),
        ],
        movies: [Movie(id: "m7", name: "Movie 7", categoryID: "9", streamURL: URL(string: "http://panel.example.com/movie/u/p/7.mp4")!, xtreamStreamID: 7)],
        series: [Series(id: "s3", name: "Series 3", categoryID: "9", xtreamSeriesID: 3)]
    )
    private let episode = Episode(id: "31", title: "Episode 4", season: 1, number: 4, streamURL: URL(string: "http://panel.example.com/series/u/p/31.mp4")!)

    func testTheCoresNamesReadInEachLanguage() throws {
        let expected = [
            "fr": ["Non classé", "Autre", "Autre 2", "Chaîne 12", "Film 7", "Série 3", "Épisode 4"],
            "es": ["Sin categoría", "Otros", "Otros 2", "Canal 12", "Película 7", "Serie 3", "Episodio 4"],
            "pt": ["Sem categoria", "Outros", "Outros 2", "Canal 12", "Filme 7", "Série 3", "Episódio 4"],
            "zh-Hans": ["未分类", "其他", "其他 2", "频道 12", "电影 7", "剧集 3", "第 4 集"],
            "ar": ["غير مصنف", "أخرى", "أخرى 2", "القناة 12", "الفيلم 7", "المسلسل 3", "الحلقة 4"],
            "hi": ["अवर्गीकृत", "अन्य", "अन्य 2", "चैनल 12", "फ़िल्म 7", "सीरीज़ 3", "एपिसोड 4"],
        ]
        for (language, names) in expected {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
            let words = PlaceholderNames.app(bundle: try XCTUnwrap(Bundle(path: path)))
            let shown = words.renaming(content)
            let details = words.renaming(SeriesDetails(info: MediaDetails(title: "x"), seasons: [Season(number: 1, name: "Season 1", episodes: [episode])]))
            XCTAssertEqual(
                shown.liveCategories.map(\.name) + [shown.channels[0].name, shown.movies[0].name, shown.series[0].name, details.seasons[0].episodes[0].title],
                names, language
            )
            XCTAssertEqual(shown.channels[1].name, "Channel 5", "a name of the provider's own, \(language)")
            XCTAssertEqual(shown.liveCategories.map(\.id), content.liveCategories.map(\.id), language)
            XCTAssertEqual(shown.channels.map(\.id), content.channels.map(\.id), language)
            for category in shown.liveCategories {
                XCTAssertFalse(category.looksAdult, "\(language) \(category.name)")
            }
        }
    }
}

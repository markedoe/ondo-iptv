import Foundation
import IPTVCore

/// TMDb's score on every poster and Continue Watching card: the same rating as the title page's (`ratingFact`), from
/// TMDb's answers this session and what the device kept, in one map from title (`ShowcaseItem.id`) to the score as
/// written ("7.8", "7,8" in French). Worked out off the main thread when what it depends on changes (the catalogue, the
/// titles' details, TMDb's answers, Clear Cache), never per card or per frame, and without asking anything of the
/// network: a card does one lookup (`PosterScoreBadge`). Android does the same (`UiMappers.posterScores`).
extension AppModel {
    /// The map is worked out again in the background, a moment later so that answers arriving together make one build;
    /// `now`: without waiting (a catalogue just shown).
    func updatePosterScores(now: Bool = false) {
        let state = posterScoreState
        state.dirty = true
        if now { state.immediate = true }
        guard state.task == nil else { return }
        state.task = Task { await runPosterScoreBuilds() }
    }

    /// Waits for the builds asked so far (tests).
    func posterScoresSettled() async {
        while let task = posterScoreState.task { await task.value }
    }

    private func runPosterScoreBuilds() async {
        let state = posterScoreState
        while state.dirty {
            if !state.immediate { try? await Task.sleep(for: PosterScoreState.delay) }
            state.immediate = false
            state.dirty = false
            await buildPosterScores()
        }
        state.task = nil
    }

    private func buildPosterScores() async {
        let state = posterScoreState
        state.builds += 1
        let playlist = activePlaylist?.id
        let movies = content.movies
        let series = content.series
        let movieDetails = movieDetailsCache
        let seriesDetails = seriesDetailsCache
        // TMDb's answers this session come before what the store kept, as on the title page (`tmdbTitle(for:)`).
        let session = tmdbMetadata.titles.mapValues(PosterScoreRating.init)
        let keys = state.keys
        let stored = state.stored
        let store = metadataStore
        let generation = state.generation
        let built = await Task.detached(priority: .userInitiated) {
            let inputs = PosterScoreBuild.inputs(movies: movies, series: series, movieDetails: movieDetails, seriesDetails: seriesDetails)
            return PosterScoreBuild.make(inputs, keys: keys, stored: stored, session: session, store: store, locale: .autoupdatingCurrent,
                                         generation: generation)
        }.value
        // Clear Cache emptied the store meanwhile: what was read before it is not kept (its own build follows).
        if state.generation == built.generation {
            state.keys = built.keys
            state.stored = built.stored
        }
        // Another playlist opened meanwhile: its own build follows.
        guard activePlaylist?.id == playlist else { return }
        if posterScores != built.scores { posterScores = built.scores }
    }

    /// Clear Cache emptied the store: only this session's answers are left.
    func forgetKeptPosterScores() {
        // The keys stay: the store now has nothing under them.
        posterScoreState.stored = [:]
        posterScoreState.generation += 1
        updatePosterScores(now: true)
    }

    /// TMDb's score of a title as its page shows it, worked out at once (tests compare it with the map).
    func tmdbScore(for item: ShowcaseItem) -> String? {
        tmdbTitle(for: item)?.votes.map { Formatting.rating($0.average) }
    }
}

/// What the builds of `AppModel.posterScores` keep between them.
@MainActor
final class PosterScoreState {
    /// How long a build waits for more changes.
    static let delay: Duration = .milliseconds(200)
    var task: Task<Void, Never>?
    var dirty = false
    var immediate = false
    /// Builds made (the tests count them).
    var builds = 0
    /// Each title's keys (`MetadataKey.all`), by what they are made of (`PosterScoreInput.cacheKey`): the store was read
    /// for them when they were first made.
    var keys: [String: [String]] = [:]
    /// TMDb's rating of the rows the store kept under those keys.
    var stored: [String: PosterScoreRating] = [:]
    /// Changes when the store is emptied.
    var generation = 0
}

/// TMDb's rating in a kept row as the title page reads it (`TitleMetadata.votes`): rated, or a row without one (TMDb
/// does not know the title, or nobody voted yet).
enum PosterScoreRating: Hashable, Sendable {
    case unrated
    case rated(Double)

    init(_ title: TitleMetadata) {
        self = title.votes.map { .rated($0.average) } ?? .unrated
    }
}

/// What a title's keys are made of.
struct PosterScoreInput: Sendable {
    let id: String
    let type: ContentType
    let tmdbID: String?
    let title: String
    let year: Int?

    var cacheKey: String {
        MetadataKey.kind(type) + "|" + (tmdbID ?? "") + "|" + (year.map(String.init) ?? "") + "|" + title
    }
}

/// The work of a build, off the main thread.
enum PosterScoreBuild {
    struct Built: Sendable {
        var keys: [String: [String]]
        var stored: [String: PosterScoreRating]
        var scores: [String: String]
        /// `PosterScoreState.generation` when the build started.
        var generation: Int
    }

    /// Every title of the catalogue, with the TMDb id its details give (`AppModel.cachedDetails(for:)`).
    static func inputs(movies: [Movie], series: [Series], movieDetails: [String: MediaDetails],
                       seriesDetails: [String: SeriesDetails]) -> [PosterScoreInput] {
        var inputs: [PosterScoreInput] = []
        inputs.reserveCapacity(movies.count + series.count)
        for movie in movies {
            inputs.append(PosterScoreInput(id: ShowcaseItem.movie(movie).id, type: .movie, tmdbID: movieDetails[movie.id]?.tmdbID,
                                           title: movie.name, year: movie.year))
        }
        for show in series {
            inputs.append(PosterScoreInput(id: ShowcaseItem.series(show).id, type: .series, tmdbID: seriesDetails[show.id]?.info.tmdbID,
                                           title: show.name, year: show.year))
        }
        return inputs
    }

    /// How many keys the store is asked for at once: it is locked while it reads, and a page opening reads it too.
    static let readSlice = 400

    /// The scores of `inputs`: each title's keys in the title page's order (`AppModel.metadataKeys(for:)`), the first one
    /// with a row (this session's, else the store's) gives its rating; none when unknown or without votes. Keys not made
    /// before are read from the store, in slices.
    static func make(_ inputs: [PosterScoreInput], keys cached: [String: [String]], stored: [String: PosterScoreRating],
                     session: [String: PosterScoreRating], store: any MetadataStoring, locale: Locale, generation: Int = 0) -> Built {
        var keys: [String: [String]] = [:]
        keys.reserveCapacity(inputs.count)
        var stored = stored
        var unread: Set<String> = []
        var titles: [(id: String, keys: [String])] = []
        titles.reserveCapacity(inputs.count)
        for input in inputs {
            let cacheKey = input.cacheKey
            let titleKeys: [String]
            if let known = keys[cacheKey] ?? cached[cacheKey] {
                titleKeys = known
            } else {
                titleKeys = MetadataKey.all(input.type, tmdbID: input.tmdbID, name: input.title, year: input.year)
                unread.formUnion(titleKeys)
            }
            keys[cacheKey] = titleKeys
            titles.append((input.id, titleKeys))
        }
        let toRead = Array(unread)
        for start in stride(from: 0, to: toRead.count, by: readSlice) {
            for (key, title) in store.titles(Array(toRead[start..<min(start + readSlice, toRead.count)])) {
                stored[key] = PosterScoreRating(title)
            }
        }
        return Built(keys: keys, stored: stored, scores: scores(titles, session: session, stored: stored, locale: locale), generation: generation)
    }

    /// Each title's score, written for `locale` (`Formatting.rating`); many titles share one, written once.
    static func scores(_ titles: [(id: String, keys: [String])], session: [String: PosterScoreRating],
                       stored: [String: PosterScoreRating], locale: Locale) -> [String: String] {
        var written: [Double: String] = [:]
        var scores: [String: String] = [:]
        for title in titles {
            guard let average = rating(keys: title.keys, session: session, stored: stored) else { continue }
            if let text = written[average] {
                scores[title.id] = text
            } else {
                let text = Formatting.rating(average, locale: locale)
                written[average] = text
                scores[title.id] = text
            }
        }
        return scores
    }

    /// The rating of the first key with a row, as `AppModel.tmdbTitle(for:)` picks it: nil when that row has none.
    static func rating(keys: [String], session: [String: PosterScoreRating], stored: [String: PosterScoreRating]) -> Double? {
        for key in keys {
            guard let row = session[key] ?? stored[key] else { continue }
            if case .rated(let average) = row, average > 0 { return average }
            return nil
        }
        return nil
    }
}

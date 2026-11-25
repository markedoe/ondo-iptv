import Foundation

/// A sound or subtitle track of a stream as the track memory compares them: its language, its own label, whether it is
/// forced (foreign dialogue only) or for the deaf and hard of hearing, and its codec. Never its place in the stream:
/// two episodes list their tracks in different orders.
public struct StreamTrack: Hashable, Sendable {
    public var language: String?
    public var label: String?
    public var forced: Bool
    public var hearingImpaired: Bool
    public var codec: String?

    public init(language: String?, label: String? = nil, forced: Bool = false, hearingImpaired: Bool = false, codec: String? = nil) {
        self.language = language
        self.label = label
        self.forced = forced
        self.hearingImpaired = hearingImpaired
        self.codec = codec
    }
}

/// What a choice in the player keeps of the track chosen (docs/TRACKS.md): what `TrackMemory.match` finds it by.
public struct TrackPick: Codable, Hashable, Sendable {
    public var language: String?
    public var label: String?
    public var forced: Bool
    public var hearingImpaired: Bool
    public var codec: String?

    public init(language: String? = nil, label: String? = nil, forced: Bool = false, hearingImpaired: Bool = false, codec: String? = nil) {
        self.language = language
        self.label = label
        self.forced = forced
        self.hearingImpaired = hearingImpaired
        self.codec = codec
    }

    public init(_ track: StreamTrack) {
        let label = track.label?.trimmingCharacters(in: .whitespaces)
        self.init(language: TrackMemory.language(track.language), label: label?.isEmpty == false ? label : nil,
                  forced: track.forced, hearingImpaired: track.hearingImpaired, codec: track.codec)
    }

    enum CodingKeys: String, CodingKey { case language, label, forced, hearingImpaired, codec }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        language = try? c.decodeIfPresent(String.self, forKey: .language)
        label = try? c.decodeIfPresent(String.self, forKey: .label)
        forced = (try? c.decodeIfPresent(Bool.self, forKey: .forced)) ?? false
        hearingImpaired = (try? c.decodeIfPresent(Bool.self, forKey: .hearingImpaired)) ?? false
        codec = try? c.decodeIfPresent(String.self, forKey: .codec)
    }
}

/// The tracks the viewer chose for a film or a series (`key`: its playlist, kind and id, as `MediaReference.itemKey`; a
/// series' for all its episodes). Each part only once chosen: `audio` nil leaves the sound to the profile's default;
/// subtitles are a track (`subtitles`), off (`subtitlesOff`), or not chosen (neither).
public struct TitleTracks: Codable, Hashable, Sendable {
    public var key: String
    public var audio: TrackPick?
    public var subtitles: TrackPick?
    public var subtitlesOff: Bool
    /// When it was last chosen (epoch milliseconds).
    public var at: Int64

    public init(key: String, audio: TrackPick? = nil, subtitles: TrackPick? = nil, subtitlesOff: Bool = false, at: Int64 = 0) {
        self.key = key
        self.audio = audio
        self.subtitles = subtitles
        self.subtitlesOff = subtitlesOff
        self.at = at
    }

    enum CodingKeys: String, CodingKey { case key, audio, subtitles, subtitlesOff, at }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        audio = try? c.decodeIfPresent(TrackPick.self, forKey: .audio)
        subtitles = try? c.decodeIfPresent(TrackPick.self, forKey: .subtitles)
        subtitlesOff = (try? c.decodeIfPresent(Bool.self, forKey: .subtitlesOff)) ?? false
        at = (try? c.decodeIfPresent(Int64.self, forKey: .at)) ?? 0
    }
}

/// A profile's languages for a title nothing was chosen for yet (Settings, Player): `audio` nil is automatic (the
/// device's languages, then the stream's default track); `subtitles` nil is off.
public struct TrackDefaults: Codable, Hashable, Sendable {
    public var audio: String?
    public var subtitles: String?

    public init(audio: String? = nil, subtitles: String? = nil) {
        self.audio = audio
        self.subtitles = subtitles
    }
}

/// The tracks a stream starts with: indexes in the lists given to `TrackMemory.plan`; audio nil keeps the player's,
/// subtitles nil is off.
public struct TrackPlan: Hashable, Sendable {
    public var audio: Int?
    public var subtitles: Int?

    public init(audio: Int?, subtitles: Int?) {
        self.audio = audio
        self.subtitles = subtitles
    }
}

/// The tracks kept per film and per series, and a profile's default languages (docs/TRACKS.md): the same rules and
/// cases as the Kotlin `TrackMemory` (`fixtures/track-memory`).
public enum TrackMemory {
    /// Titles remembered at most per profile, the latest first.
    public static let maxTitles = 500

    /// The languages offered in Settings for a profile's defaults, as codes; the names come from the system.
    public static let choices = ["en", "fr", "es", "pt", "de", "it", "nl", "ar", "hi", "zh", "ja", "ko", "ru", "tr", "pl"]

    /// A language code as the memory compares them: the language alone, in two letters when it has two ("fre", "fra",
    /// "fr-CA" and "FR" are "fr"); nil for none or an undetermined one ("und", "mul", "zxx", "mis").
    public static func language(_ code: String?) -> String? {
        guard let code else { return nil }
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard let first = trimmed.split(whereSeparator: { $0 == "-" || $0 == "_" }).first else { return nil }
        let primary = first.lowercased()
        guard !primary.isEmpty, !undetermined.contains(primary) else { return nil }
        return threeLetters[primary] ?? primary
    }

    /// The track of `tracks` that `pick` stands for: one in its language (by its label when it named none), the most
    /// like it (forced, hearing impaired, label, codec); `current` when it is as like it as any but for the codec (the
    /// player's own pick in the language is not changed for a codec alone: no switch at every start). Nil when none has
    /// its language.
    public static func match(_ tracks: [StreamTrack], _ pick: TrackPick, current: Int? = nil) -> Int? {
        let language = Self.language(pick.language)
        let label = pick.label?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        func sameLabel(_ track: StreamTrack) -> Bool {
            guard let label, let own = track.label?.trimmingCharacters(in: .whitespaces) else { return false }
            return own.caseInsensitiveCompare(label) == .orderedSame
        }
        let candidates = tracks.indices.filter { index in
            if let language { return Self.language(tracks[index].language) == language }
            return sameLabel(tracks[index])
        }
        guard let first = candidates.first else { return nil }
        func score(_ index: Int) -> Int {
            let track = tracks[index]
            var score = 0
            if track.forced == pick.forced { score += 8 }
            if track.hearingImpaired == pick.hearingImpaired { score += 4 }
            if sameLabel(track) { score += 2 }
            if let codec = pick.codec, track.codec == codec { score += 1 }
            return score
        }
        let best = candidates.map(score).max() ?? 0
        if let current, candidates.contains(current), score(current) >= best - 1 { return current }
        return candidates.first { score($0) == best } ?? first
    }

    /// The tracks a stream starts with: the title's `memory` first, then the profile's `defaults`; a remembered track the
    /// stream lacks falls back to the default, and a default it lacks to the stream's own (audio) or to none (subtitles).
    /// Subtitles turned off for the title stay off.
    public static func plan(audio: [StreamTrack], subtitles: [StreamTrack], memory: TitleTracks?, defaults: TrackDefaults?,
                            currentAudio: Int? = nil, currentSubtitles: Int? = nil) -> TrackPlan {
        let sound = memory?.audio.flatMap { match(audio, $0, current: currentAudio) }
            ?? defaults?.audio.flatMap { match(audio, TrackPick(language: $0), current: currentAudio) }
        let text: Int?
        if memory?.subtitlesOff == true {
            text = nil
        } else {
            text = memory?.subtitles.flatMap { match(subtitles, $0, current: currentSubtitles) }
                ?? defaults?.subtitles.flatMap { match(subtitles, TrackPick(language: $0), current: currentSubtitles) }
        }
        return TrackPlan(audio: sound, subtitles: text)
    }

    /// Whether a stream for which the plan found no subtitle track is to show none, as the title or the profile says
    /// (subtitles turned off for the title, or the profile's Off); else the player's own pick stays (forced ones).
    public static func subtitlesOff(memory: TitleTracks?, defaults: TrackDefaults?) -> Bool {
        memory?.subtitlesOff == true || (defaults != nil && defaults?.subtitles == nil)
    }

    /// The sound languages to ask a player for before it lists the tracks, the first preferred.
    public static func audioLanguages(memory: TitleTracks?, defaults: TrackDefaults?) -> [String] {
        distinct([language(memory?.audio?.language), language(defaults?.audio)].compactMap { $0 })
    }

    /// The subtitle languages to ask a player for before it lists the tracks, the first preferred; empty: none.
    public static func subtitleLanguages(memory: TitleTracks?, defaults: TrackDefaults?) -> [String] {
        if memory?.subtitlesOff == true { return [] }
        return distinct([language(memory?.subtitles?.language), language(defaults?.subtitles)].compactMap { $0 })
    }

    /// What is remembered for a title known by `keys` (a film's versions: the one playing first), the latest choice.
    public static func remembered(_ memories: [TitleTracks], keys: [String]) -> TitleTracks? {
        guard !keys.isEmpty else { return nil }
        return memories.first { keys.contains($0.key) }
    }

    /// `memories` with a choice made while `keys.first` plays: `audio` a sound track chosen, `subtitles` a subtitle track
    /// chosen, `subtitlesOff` subtitles turned off. What the choice does not say stays as remembered for the title
    /// (another version's included); the title comes first, and only the latest `maxTitles` are kept.
    public static func remember(_ memories: [TitleTracks], keys: [String], audio: TrackPick? = nil, subtitles: TrackPick? = nil,
                                subtitlesOff: Bool = false, at: Int64) -> [TitleTracks] {
        guard let key = keys.first, audio != nil || subtitles != nil || subtitlesOff else { return memories }
        var chosen = remembered(memories, keys: keys) ?? TitleTracks(key: key)
        chosen.key = key
        if let audio { chosen.audio = audio }
        if subtitlesOff {
            chosen.subtitles = nil
            chosen.subtitlesOff = true
        } else if let subtitles {
            chosen.subtitles = subtitles
            chosen.subtitlesOff = false
        }
        chosen.at = at
        return Array(([chosen] + memories.filter { $0.key != key }).prefix(maxTitles))
    }

    /// The language VLC names in a track's description ("Track 1 - [French]", "English - [English]"), as a code; nil when
    /// it names none this device knows.
    public static func vlcLanguage(_ name: String) -> String? {
        guard let open = name.lastIndex(of: "["), let close = name.lastIndex(of: "]"), open < close else { return nil }
        let word = name[name.index(after: open)..<close].trimmingCharacters(in: .whitespaces)
        guard !word.isEmpty else { return nil }
        if word.count <= 3, let code = language(word), code.count == 2 { return code }
        return languageNames[word.lowercased()]
    }

    /// Reads the language names VLC's track names are compared with (`vlcLanguage`), once: call it off the main actor
    /// before VLC lists its tracks, so that the first stream does not wait for it.
    public static func prepareVLCNames() {
        _ = languageNames.count
    }

    /// Each two-letter language's name in English and in the device's language, lower case, to its code: built once, at
    /// its first use (a static constant), never per track.
    static let languageNames: [String: String] = {
        var names: [String: String] = [:]
        for locale in [Locale.current, Locale(identifier: "en")] {
            for code in Locale.LanguageCode.isoLanguageCodes.map(\.identifier) where code.count == 2 {
                if let shown = locale.localizedString(forLanguageCode: code) { names[shown.lowercased()] = code }
            }
        }
        return names
    }()

    private static func distinct(_ codes: [String]) -> [String] {
        var seen = Set<String>()
        return codes.filter { seen.insert($0).inserted }
    }

    private static let undetermined: Set<String> = ["und", "mul", "zxx", "mis", "qaa"]

    /// ISO 639-2 codes (bibliographic and terminology) of languages that have two letters, and older two-letter codes.
    private static let threeLetters: [String: String] = [
        "eng": "en", "fre": "fr", "fra": "fr", "spa": "es", "por": "pt", "ger": "de", "deu": "de",
        "ita": "it", "dut": "nl", "nld": "nl", "ara": "ar", "hin": "hi", "chi": "zh", "zho": "zh",
        "jpn": "ja", "kor": "ko", "rus": "ru", "tur": "tr", "pol": "pl", "swe": "sv", "dan": "da",
        "nor": "no", "nob": "no", "nno": "no", "nb": "no", "nn": "no", "fin": "fi", "gre": "el",
        "ell": "el", "heb": "he", "iw": "he", "hun": "hu", "cze": "cs", "ces": "cs", "rum": "ro",
        "ron": "ro", "ukr": "uk", "vie": "vi", "tha": "th", "ind": "id", "in": "id", "per": "fa",
        "fas": "fa", "urd": "ur", "ben": "bn", "tam": "ta", "tel": "te", "may": "ms", "msa": "ms",
        "bul": "bg", "hrv": "hr", "srp": "sr", "slo": "sk", "slk": "sk", "slv": "sl", "cat": "ca",
        "alb": "sq", "sqi": "sq", "mac": "mk", "mkd": "mk", "lit": "lt", "lav": "lv", "est": "et",
        "ice": "is", "isl": "is", "kur": "ku", "pan": "pa", "mar": "mr", "guj": "gu", "kan": "kn",
        "mal": "ml", "tgl": "tl", "fil": "tl",
    ]
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

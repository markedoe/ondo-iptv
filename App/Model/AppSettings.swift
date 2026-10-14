import SwiftUI
import IPTVCore

enum AppearancePreference: String, Codable, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }
}

enum VideoAspect: String, Codable, CaseIterable, Identifiable {
    case fit, fill, stretch

    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .fit: return "Fit"
        case .fill: return "Fill (crop)"
        case .stretch: return "Stretch"
        }
    }

    var systemImage: String {
        switch self {
        case .fit: return "rectangle.arrowtriangle.2.inward"
        case .fill: return "rectangle.arrowtriangle.2.outward"
        case .stretch: return "arrow.left.and.right.square"
        }
    }

    var next: VideoAspect {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

enum PosterSort: String, Codable, CaseIterable, Identifiable {
    case playlist, name, recent, rating

    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .playlist: return "Playlist order"
        case .name: return "Name"
        case .recent: return "Recently added"
        case .rating: return "Rating"
        }
    }
}

/// User preferences, persisted as JSON.
struct AppSettings: Codable, Equatable {
    var activePlaylistID: UUID?
    var appearance: AppearancePreference = .dark
    var showChannelNumbers = true
    var autoplayLastChannel = false
    var resumePlayback = true
    var autoRetryStreams = true
    var defaultAspect: VideoAspect = .fit
    var parentalControlEnabled = false
    var lockAdultCategoriesAutomatically = true
    var epgDaysAhead = 3
    var posterSort: PosterSort = .playlist
    var preferredAudioLanguage: String?
    var preferredSubtitleLanguage: String?
    var subtitlesEnabled = false
    var playerEngine: PlayerEngine = .automatic
    var activeProfileID: UUID?
    var landscapeForLive = false
    var landscapeForVOD = true
    var hasCompletedOnboarding = false
    /// Minutes before the start of a programme a reminder is delivered.
    var reminderLeadMinutes = 2
    /// Addic7ed needs no account: searched unless switched off.
    var addic7edSubtitles = true
    /// Languages of the last subtitle search (ISO 639-1), preferred first.
    var subtitleSearchLanguages: [String] = []

    init() {}

    // Tolerant decoding: new settings added in updates must not reset the others.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        activePlaylistID = try c.decodeIfPresent(UUID.self, forKey: .activePlaylistID)
        appearance = (try? c.decodeIfPresent(AppearancePreference.self, forKey: .appearance)) ?? d.appearance
        showChannelNumbers = (try? c.decodeIfPresent(Bool.self, forKey: .showChannelNumbers)) ?? d.showChannelNumbers
        autoplayLastChannel = (try? c.decodeIfPresent(Bool.self, forKey: .autoplayLastChannel)) ?? d.autoplayLastChannel
        resumePlayback = (try? c.decodeIfPresent(Bool.self, forKey: .resumePlayback)) ?? d.resumePlayback
        autoRetryStreams = (try? c.decodeIfPresent(Bool.self, forKey: .autoRetryStreams)) ?? d.autoRetryStreams
        defaultAspect = (try? c.decodeIfPresent(VideoAspect.self, forKey: .defaultAspect)) ?? d.defaultAspect
        parentalControlEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .parentalControlEnabled)) ?? d.parentalControlEnabled
        lockAdultCategoriesAutomatically = (try? c.decodeIfPresent(Bool.self, forKey: .lockAdultCategoriesAutomatically)) ?? d.lockAdultCategoriesAutomatically
        epgDaysAhead = (try? c.decodeIfPresent(Int.self, forKey: .epgDaysAhead)) ?? d.epgDaysAhead
        posterSort = (try? c.decodeIfPresent(PosterSort.self, forKey: .posterSort)) ?? d.posterSort
        preferredAudioLanguage = try? c.decodeIfPresent(String.self, forKey: .preferredAudioLanguage)
        preferredSubtitleLanguage = try? c.decodeIfPresent(String.self, forKey: .preferredSubtitleLanguage)
        subtitlesEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .subtitlesEnabled)) ?? d.subtitlesEnabled
        playerEngine = (try? c.decodeIfPresent(PlayerEngine.self, forKey: .playerEngine)) ?? d.playerEngine
        activeProfileID = try? c.decodeIfPresent(UUID.self, forKey: .activeProfileID)
        landscapeForLive = (try? c.decodeIfPresent(Bool.self, forKey: .landscapeForLive)) ?? d.landscapeForLive
        landscapeForVOD = (try? c.decodeIfPresent(Bool.self, forKey: .landscapeForVOD)) ?? d.landscapeForVOD
        hasCompletedOnboarding = (try? c.decodeIfPresent(Bool.self, forKey: .hasCompletedOnboarding)) ?? d.hasCompletedOnboarding
        reminderLeadMinutes = (try? c.decodeIfPresent(Int.self, forKey: .reminderLeadMinutes)) ?? d.reminderLeadMinutes
        addic7edSubtitles = (try? c.decodeIfPresent(Bool.self, forKey: .addic7edSubtitles)) ?? d.addic7edSubtitles
        subtitleSearchLanguages = (try? c.decodeIfPresent([String].self, forKey: .subtitleSearchLanguages)) ?? d.subtitleSearchLanguages
    }
}

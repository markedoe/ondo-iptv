import Foundation
import IPTVCore

/// The sound and subtitles kept per film and per series, and each profile's languages (docs/TRACKS.md): in the open
/// profile's library, on this device; the player asks for them at each stream and tells each choice of the viewer's.
extension AppModel {
    /// The keys `item`'s tracks are kept under: its film's or its series' first (where a choice is kept), then its film's
    /// other versions, whose choices it takes. Empty for a channel or a replay.
    func trackKeys(for item: PlaybackItem) -> [String] {
        let reference = item.reference
        guard !item.isLive, !item.isCatchup, reference.type == .movie || reference.type == .series else { return [] }
        var keys = [reference.itemKey]
        if reference.type == .movie, let group = films.group(of: reference.itemID) {
            for version in films.versions(group: group) {
                let key = MediaReference(playlistID: reference.playlistID, type: .movie, itemID: version.id).itemKey
                if !keys.contains(key) { keys.append(key) }
            }
        }
        return keys
    }

    /// What `item` starts with: the tracks chosen for its title, and the open profile's languages.
    func titleTracks(for item: PlaybackItem) -> (memory: TitleTracks?, defaults: TrackDefaults?) {
        (TrackMemory.remembered(library.titleTracks, keys: trackKeys(for: item)), profileTrackDefaults)
    }

    /// The open profile's languages: those it set; for the main profile, before it set any, those the player learned on
    /// this device before each profile had its own (`AppSettings.preferredAudioLanguage`…, docs/TRACKS.md "Migration").
    var profileTrackDefaults: TrackDefaults? {
        if let defaults = library.trackDefaults { return defaults }
        guard activeProfile.isMain else { return nil }
        let subtitles = settings.subtitlesEnabled ? settings.preferredSubtitleLanguage : nil
        guard settings.preferredAudioLanguage != nil || subtitles != nil else { return nil }
        return TrackDefaults(audio: settings.preferredAudioLanguage, subtitles: subtitles)
    }

    /// The open profile's languages as Settings shows them.
    var trackDefaults: TrackDefaults { profileTrackDefaults ?? TrackDefaults() }

    func setTrackDefaults(_ defaults: TrackDefaults) {
        guard library.trackDefaults != defaults else { return }
        updateLibrary { $0.trackDefaults = defaults }
    }

    /// A choice the viewer made in the player while `item` plays: a sound track, a subtitle track, or subtitles off.
    func rememberTracks(for item: PlaybackItem, audio: TrackPick? = nil, subtitles: TrackPick? = nil, subtitlesOff: Bool = false) {
        let keys = trackKeys(for: item)
        guard !keys.isEmpty, audio != nil || subtitles != nil || subtitlesOff else { return }
        let at = Int64(Date().timeIntervalSince1970 * 1000)
        updateLibrary {
            $0.titleTracks = TrackMemory.remember($0.titleTracks, keys: keys, audio: audio, subtitles: subtitles, subtitlesOff: subtitlesOff, at: at)
        }
    }
}

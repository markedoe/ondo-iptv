import Foundation
import IPTVCore

/// Channel management: hide (per profile), rename and renumber (per playlist).
extension AppModel {
    /// Channels of the active playlist hidden by the current profile.
    var hiddenChannelIDs: Set<String> {
        guard let playlist = activePlaylist else { return [] }
        return library.hiddenChannelIDs(playlistID: playlist.id)
    }

    func isHidden(_ channel: Channel) -> Bool {
        hiddenChannelIDs.contains(channel.id)
    }

    func setHidden(_ hidden: Bool, channel: Channel) {
        guard let playlist = activePlaylist else { return }
        updateLibrary { $0.setHidden(hidden, playlistID: playlist.id, channelID: channel.id) }
    }

    /// Hidden channels, in playlist order (Settings > Hidden Channels).
    func hiddenChannels() -> [Channel] {
        let hidden = hiddenChannelIDs
        return content.channels.filter { hidden.contains($0.id) }
    }

    func channelEdit(for channel: Channel) -> ChannelEdit {
        activePlaylist?.channelEdits[channel.id] ?? ChannelEdit()
    }

    /// Name given by the provider, before any rename (IPTVCore's "Channel 12" for none, in the app's language).
    func originalName(of channel: Channel) -> String {
        rawContent.channels.first { $0.id == channel.id }.map { PlaceholderNames.app().renaming($0).name } ?? channel.name
    }

    /// Number given by the provider, before any change.
    func originalNumber(of channel: Channel) -> Int? {
        rawContent.channels.first { $0.id == channel.id }?.number
    }

    /// Saves a new name / number; empty values restore the provider's ones.
    func setEdit(_ edit: ChannelEdit, for channel: Channel) async {
        guard var playlist = activePlaylist else { return }
        var cleaned = edit
        let trimmed = edit.name?.trimmingCharacters(in: .whitespacesAndNewlines)
        cleaned.name = (trimmed?.isEmpty ?? true) || trimmed == originalName(of: channel) ? nil : trimmed
        if cleaned.number == originalNumber(of: channel) { cleaned.number = nil }
        playlist.channelEdits[channel.id] = cleaned.isEmpty ? nil : cleaned
        await updatePlaylist(playlist)
    }
}

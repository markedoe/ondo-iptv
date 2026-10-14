import Foundation
import IPTVCore

/// Programme reminders: stored in the profile's library, delivered as local notifications.
extension AppModel {
    func reminderID(for program: EPGProgram, on channel: Channel) -> String? {
        activePlaylist.map { ProgramReminder.id(playlistID: $0.id, channelID: channel.id, start: program.start) }
    }

    func hasReminder(for program: EPGProgram, on channel: Channel) -> Bool {
        reminderID(for: program, on: channel).map(library.hasReminder(id:)) ?? false
    }

    /// Reminders of locked or hidden categories are left out, like their channels.
    var upcomingReminders: [ProgramReminder] {
        let excluded = excludedCategoryIDs
        return library.upcomingReminders().filter { reminder in
            guard reminder.playlistID == activePlaylist?.id, let channel = index.channelsByID[reminder.channelID] else { return true }
            return !excluded.contains(channel.categoryID)
        }
    }

    /// Adds or removes the reminder of a future programme. Permission is requested with the first one.
    func toggleReminder(for program: EPGProgram, on channel: Channel, now: Date = Date()) async {
        guard let playlist = activePlaylist, program.start > now else { return }
        let reminder = ProgramReminder(
            playlistID: playlist.id,
            channelID: channel.id,
            channelName: channel.name,
            title: program.title,
            start: program.start,
            end: program.end
        )
        var isSet = false
        updateLibrary { isSet = $0.toggleReminder(reminder, now: now) }
        if isSet, !(await reminderScheduler.requestAuthorization()) {
            bannerMessage = String(localized: "Notifications are off for Ondo: allow them in Settings to be reminded.")
        }
        await syncReminders(now: now)
    }

    func removeReminder(_ reminder: ProgramReminder) {
        updateLibrary { $0.removeReminder(id: reminder.id) }
        scheduleReminderSync()
    }

    func setReminderLeadMinutes(_ minutes: Int) {
        updateSettings { $0.reminderLeadMinutes = max(minutes, 0) }
        scheduleReminderSync()
    }

    /// Makes the pending notifications match the reminders of the active profile. Syncs run one after the
    /// other and each reads the reminders once the previous one is done: a sync started earlier (a lead
    /// time change) finishing after a later one (a removal) would bring the removed reminder back.
    func syncReminders(now: Date = Date()) async {
        let previous = reminderSync
        let sync = Task {
            await previous?.value
            await self.reminderScheduler.reschedule(
                self.library.upcomingReminders(at: now),
                leadTime: TimeInterval(self.settings.reminderLeadMinutes * 60),
                now: now
            )
        }
        reminderSync = sync
        await sync.value
    }

    func scheduleReminderSync() {
        Task { await syncReminders() }
    }

    /// Opens the channel of a tapped reminder (switching playlist if needed).
    func openReminder(_ target: ReminderNotificationRouter.Target) async {
        if activePlaylist?.id != target.playlistID, let playlist = playlists.first(where: { $0.id == target.playlistID }) {
            await activate(playlist)
        }
        guard let channel = index.channelsByID[target.channelID] else {
            bannerMessage = String(localized: "This channel is no longer in your playlist.")
            return
        }
        play(channel)
    }
}

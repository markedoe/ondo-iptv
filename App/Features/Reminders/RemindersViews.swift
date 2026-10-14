import SwiftUI
import IPTVCore

/// Bell button setting or removing the reminder of a future programme.
struct ReminderButton: View {
    @Environment(AppModel.self) private var model
    let program: EPGProgram
    let channel: Channel

    var body: some View {
        let isSet = model.hasReminder(for: program, on: channel)
        Button {
            Task { await model.toggleReminder(for: program, on: channel) }
        } label: {
            Label(isSet ? "Reminder Set" : "Remind Me", systemImage: isSet ? "bell.fill" : "bell")
        }
        .accessibilityIdentifier("reminderButton")
    }
}

/// Upcoming reminders of the active profile, with the delivery lead time.
struct RemindersView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let reminders = model.upcomingReminders
        List {
            Section {
                Picker(selection: Binding(
                    get: { model.settings.reminderLeadMinutes },
                    set: { model.setReminderLeadMinutes($0) }
                )) {
                    Text("At the start").tag(0)
                    ForEach([1, 2, 5, 10, 15], id: \.self) { minutes in
                        Text("\(minutes) min before").tag(minutes)
                    }
                } label: {
                    Label("Notify", systemImage: "bell.badge")
                }
            } footer: {
                Text("Reminders are kept with My List, in your profile.")
            }

            Section {
                ForEach(reminders) { reminder in
                    ReminderRow(reminder: reminder)
                }
                .onDelete { offsets in
                    for offset in offsets { model.removeReminder(reminders[offset]) }
                }
            }
        }
        .overlay {
            if reminders.isEmpty {
                ContentUnavailableView(
                    "No reminders",
                    systemImage: "bell.slash",
                    description: Text("Open a programme in the TV guide and choose Remind Me.")
                )
            }
        }
        .navigationTitle("Reminders")
    }
}

struct ReminderRow: View {
    @Environment(AppModel.self) private var model
    let reminder: ProgramReminder

    var body: some View {
        Button {
            if let channel = model.index.channelsByID[reminder.channelID], model.activePlaylist?.id == reminder.playlistID {
                model.play(channel)
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(reminder.title).font(.body.weight(.medium)).lineLimit(1)
                    Text(reminder.channelName).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 3) {
                    Text(Formatting.dayAndTime(reminder.start)).font(.subheadline.monospacedDigit())
                    if reminder.start <= Date() {
                        Text("On now").font(.caption2.bold()).foregroundStyle(.red)
                    }
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) {
                model.removeReminder(reminder)
            } label: {
                Label("Remove Reminder", systemImage: "bell.slash")
            }
        }
    }
}

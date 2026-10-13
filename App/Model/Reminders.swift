import Foundation
import IPTVCore
import Observation
import UserNotifications

/// Delivers programme reminders as local notifications.
protocol ReminderScheduling: AnyObject {
    /// Asks for permission the first time; returns whether notifications can be shown.
    func requestAuthorization() async -> Bool
    /// Makes the pending notifications match `reminders`, each delivered `leadTime` seconds before its start.
    func reschedule(_ reminders: [ProgramReminder], leadTime: TimeInterval, now: Date) async
}

final class UserNotificationReminderScheduler: ReminderScheduling {
    private static let identifierPrefix = "reminder."
    /// iOS keeps at most 64 pending local notifications per app.
    private static let maxPending = 60

    private var center: UNUserNotificationCenter { .current() }

    func requestAuthorization() async -> Bool {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional:
            return true
        case .denied:
            return false
        default:
            return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        }
    }

    func reschedule(_ reminders: [ProgramReminder], leadTime: TimeInterval, now: Date) async {
        let pending = Set(await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(Self.identifierPrefix) })
        let wanted = reminders
            .filter { $0.start.addingTimeInterval(-leadTime) > now }
            .sorted { $0.start < $1.start }
            .prefix(Self.maxPending)
        let wantedIDs = Set(wanted.map { Self.identifier(for: $0, leadTime: leadTime) })
        center.removePendingNotificationRequests(withIdentifiers: Array(pending.subtracting(wantedIDs)))

        for reminder in wanted {
            let identifier = Self.identifier(for: reminder, leadTime: leadTime)
            guard !pending.contains(identifier) else { continue }
            let content = UNMutableNotificationContent()
            content.title = reminder.title
            content.body = String(localized: "Starts at \(Formatting.time(reminder.start)) on \(reminder.channelName)")
            content.sound = .default
            content.threadIdentifier = "reminders"
            content.userInfo = ReminderNotificationRouter.userInfo(for: reminder)
            let delay = max(reminder.start.addingTimeInterval(-leadTime).timeIntervalSince(now), 1)
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
        }
    }

    /// Includes the lead time so changing it replaces the notifications.
    private static func identifier(for reminder: ProgramReminder, leadTime: TimeInterval) -> String {
        "\(identifierPrefix)\(reminder.id)|\(Int(leadTime))"
    }
}

/// Used by tests and UI tests: no system permission prompt, no real notification.
final class InMemoryReminderScheduler: ReminderScheduling {
    var isAuthorized = true
    /// Reminder id -> delivery date.
    private(set) var scheduled: [String: Date] = [:]

    func requestAuthorization() async -> Bool { isAuthorized }

    func reschedule(_ reminders: [ProgramReminder], leadTime: TimeInterval, now: Date) async {
        scheduled = Dictionary(
            reminders
                .map { ($0.id, $0.start.addingTimeInterval(-leadTime)) }
                .filter { $0.1 > now },
            uniquingKeysWith: { first, _ in first }
        )
    }
}

/// Receives taps on reminder notifications and hands the channel to the UI.
@MainActor
@Observable
final class ReminderNotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    struct Target: Equatable, Sendable {
        let playlistID: UUID
        let channelID: String
    }

    static let shared = ReminderNotificationRouter()

    /// Channel to open, consumed by the UI.
    var pendingTarget: Target?

    /// Must run at launch so taps that start the app are delivered.
    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    nonisolated static func userInfo(for reminder: ProgramReminder) -> [String: String] {
        ["playlistID": reminder.playlistID.uuidString, "channelID": reminder.channelID]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        if let playlistID = (info["playlistID"] as? String).flatMap(UUID.init(uuidString:)),
           let channelID = info["channelID"] as? String {
            let target = Target(playlistID: playlistID, channelID: channelID)
            Task { @MainActor in ReminderNotificationRouter.shared.pendingTarget = target }
        }
        completionHandler()
    }
}

import Foundation

enum Formatting {
    /// 3725 -> "1:02:05", 125 -> "2:05"
    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "--:--" }
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// 5400 -> "1 hr, 30 min" (the system's localized short units)
    static func duration(_ seconds: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute]
        formatter.unitsStyle = .short
        return formatter.string(from: TimeInterval(seconds)) ?? "\(seconds / 60) min"
    }

    static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }

    static func timeRange(_ start: Date, _ end: Date) -> String {
        "\(time(start)) – \(time(end))"
    }

    static func dayAndTime(_ date: Date) -> String {
        if Calendar.current.isDateInToday(date) { return time(date) }
        return date.formatted(.dateTime.weekday(.abbreviated).day().hour().minute())
    }

    static func relative(_ date: Date) -> String {
        date.formatted(.relative(presentation: .named))
    }

    /// "7.8" / "7,8": the user's decimal separator, like the rest of the app.
    static func rating(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(1)))
    }

    static func bytes(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: count, countStyle: .file)
    }
}

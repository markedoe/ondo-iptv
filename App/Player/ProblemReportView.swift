import SwiftUI
import IPTVCore

/// More, Report a Problem: the report of this playback, shown in full before it leaves the device, to share (mail,
/// messages, files) or to paste into a GitHub issue.
struct ProblemReportView: View {
    let report: String
    @Environment(\.dismiss) private var dismiss

    /// A new issue with the start of the report (an address has a length limit): the rest is pasted from Copy.
    private var issueURL: URL? {
        var body = report
        if body.count > 2_500 { body = String(body.prefix(2_500)) + "\n…" }
        var components = URLComponents(string: "https://github.com/markedoe/ondo-iptv/issues/new")
        components?.queryItems = [
            URLQueryItem(name: "title", value: "Playback problem"),
            URLQueryItem(name: "body", value: "<!-- Describe what you saw. -->\n\n```\n\(body)\n```"),
        ]
        return components?.url
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("""
                        This report helps find the cause of a playback problem: the start and seek times, the freezes and \
                        the player’s statistics for this playback, with the app, the system and the player settings. It \
                        holds no stream address, login or title. GitHub issues are public.
                        """)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Text(verbatim: report)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding()
            }
            .navigationTitle("Problem Report")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button {
                        Pasteboard.copy(report)
                    } label: {
                        Label("Copy Report", systemImage: "doc.on.doc")
                    }
                    ShareLink(item: report, subject: Text("Ondo playback report"))
                    if let issueURL {
                        Link(destination: issueURL) {
                            Label("Open an Issue on GitHub", systemImage: "exclamationmark.bubble")
                        }
                    }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 520)
        #endif
    }
}

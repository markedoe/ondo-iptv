import SwiftUI
import IPTVCore

/// The user's own keys and accounts for the subtitle services searched from the player.
struct SubtitleServicesView: View {
    @Environment(AppModel.self) private var model
    @State private var values: [SubtitleCredential: String] = [:]

    var body: some View {
        Form {
            Section {
                credentialField("API key", .openSubtitlesKey)
                credentialField("Username (optional)", .openSubtitlesUsername)
                credentialField("Password (optional)", .openSubtitlesPassword, secure: true)
            } header: {
                Text(verbatim: "OpenSubtitles")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("The largest collection, all languages. Get a key by creating an API consumer in your account. Without signing in, 5 downloads a day; your account raises the limit.")
                    Link(destination: URL(string: "https://www.opensubtitles.com/consumers")!) { Text(verbatim: "opensubtitles.com/consumers") }
                }
            }

            Section {
                credentialField("API key", .subdlKey)
            } header: {
                Text(verbatim: "SubDL")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Large collection of films and series. The key is in your SubDL account, under API.")
                    Link(destination: URL(string: "https://subdl.com")!) { Text(verbatim: "subdl.com") }
                }
            }

            Section {
                credentialField("API key", .subSourceKey)
            } header: {
                Text(verbatim: "SubSource")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Films and series, many languages. Generate the key in your SubSource profile.")
                    Link(destination: URL(string: "https://subsource.net/api-docs")!) { Text(verbatim: "subsource.net/api-docs") }
                }
            }

            Section {
                Toggle("Search Addic7ed", isOn: Binding(
                    get: { model.settings.addic7edSubtitles },
                    set: { model.setAddic7edSubtitles($0) }
                ))
            } header: {
                Text(verbatim: "Addic7ed")
            } footer: {
                Text("Series only, no account needed (through the public Gestdown service).")
            }

            Section {
                Text("Searching sends the title you are watching to the services above, with your own key or account. Nothing is sent until you search.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Subtitles")
        .onAppear {
            for field in SubtitleCredential.allCases { values[field] = model.subtitleCredential(field) ?? "" }
        }
        .onDisappear(perform: saveAll)
    }

    private func binding(_ field: SubtitleCredential) -> Binding<String> {
        Binding(get: { values[field] ?? "" }, set: { values[field] = $0 })
    }

    /// Saved when the field is submitted and when the screen closes, not at each keystroke.
    @ViewBuilder
    private func credentialField(_ title: LocalizedStringKey, _ field: SubtitleCredential, secure: Bool = false) -> some View {
        Group {
            if secure {
                SecureField(title, text: binding(field))
            } else {
                TextField(title, text: binding(field))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
            }
        }
        .onSubmit { model.setSubtitleCredential(values[field], for: field) }
    }

    private func saveAll() {
        for field in SubtitleCredential.allCases {
            model.setSubtitleCredential(values[field], for: field)
        }
    }
}

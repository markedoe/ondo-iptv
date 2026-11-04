import SwiftUI
import IPTVCore

/// The user's own keys and accounts for the subtitle services searched from the player. Each service checks its key
/// (and OpenSubtitles its account) when it is saved: only an accepted one is kept.
struct SubtitleServicesView: View {
    @Environment(AppModel.self) private var model
    @State private var values: [SubtitleCredential: String] = [:]
    /// The fields as last shown from the keychain: a service the user did not edit follows what sync brings.
    @State private var shown: [SubtitleCredential: String] = [:]
    @State private var checks: [SubtitleService: SubtitleKeyCheck] = [:]

    var body: some View {
        Form {
            Section {
                credentialField("API key", .openSubtitlesKey)
                credentialField("Username (optional)", .openSubtitlesUsername)
                credentialField("Password (optional)", .openSubtitlesPassword, secure: true)
                saveRow(.openSubtitles)
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
                saveRow(.subdl)
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
                saveRow(.subSource)
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
                    get: { model.settings.addic7edOptIn },
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
            for field in SubtitleCredential.allCases { show(field) }
        }
        .onChange(of: model.subtitleCredentialsRevision) { _, _ in
            // Sync saved or removed a key: shown again, except in a service being typed or checked here.
            for service in [SubtitleService.openSubtitles, .subdl, .subSource] where !edited(service) && checks[service] != .checking {
                SubtitleCredential.fields(of: service).forEach(show)
            }
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
        .onSubmit { check(Self.service(of: field)) }
    }

    private static func service(of field: SubtitleCredential) -> SubtitleService {
        switch field {
        case .openSubtitlesKey, .openSubtitlesUsername, .openSubtitlesPassword: return .openSubtitles
        case .subdlKey: return .subdl
        case .subSourceKey: return .subSource
        }
    }

    /// The button that checks and saves a service's fields, and what the service answered.
    @ViewBuilder
    private func saveRow(_ service: SubtitleService) -> some View {
        Button("Check and Save") { check(service) }
            .disabled(checks[service] == .checking)
        let name = service.displayName
        switch checks[service] {
        case .checking?:
            HStack {
                ProgressView().controlSize(.small)
                Text("Checking…")
            }
            .font(.caption)
        case .accepted?:
            Label("Accepted by \(name).", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.green)
        case .refused?:
            Group {
                if service == .openSubtitles && !(values[.openSubtitlesUsername] ?? "").isEmpty {
                    Label("\(name) refused the API key or the username and password, so nothing was saved.", systemImage: "xmark.octagon")
                } else {
                    Label("\(name) refused the API key, so nothing was saved. Copy it again from your account.", systemImage: "xmark.octagon")
                }
            }
            .font(.caption)
            .foregroundStyle(.red)
        case .unreachable?:
            Label("\(name) could not be reached, so nothing was saved. Check the connection and try again.", systemImage: "wifi.exclamationmark")
                .font(.caption)
                .foregroundStyle(.orange)
        case nil:
            EmptyView()
        }
    }

    /// The fields of `service` to check and save: those the user changed as typed, the others as saved now (what sync
    /// brought meanwhile included, never the value the screen showed before).
    private func typed(_ service: SubtitleService) -> [SubtitleCredential: String] {
        Self.fieldsToSave(SubtitleCredential.fields(of: service), typed: values, shown: shown, saved: model.subtitleCredential)
    }

    static func fieldsToSave(_ fields: [SubtitleCredential], typed: [SubtitleCredential: String], shown: [SubtitleCredential: String],
                             saved: (SubtitleCredential) -> String?) -> [SubtitleCredential: String] {
        Dictionary(uniqueKeysWithValues: fields.map { field in
            let value = typed[field] ?? ""
            return (field, value != (shown[field] ?? "") ? value : (saved(field) ?? ""))
        })
    }

    private func show(_ field: SubtitleCredential) {
        let saved = model.subtitleCredential(field) ?? ""
        values[field] = saved
        shown[field] = saved
    }

    /// Typed here since the fields were shown (what sync saved meanwhile is no edit of the user's).
    private func edited(_ service: SubtitleService) -> Bool {
        SubtitleCredential.fields(of: service).contains { (values[$0] ?? "") != (shown[$0] ?? "") }
    }

    private func changed(_ service: SubtitleService) -> Bool {
        edited(service) && SubtitleCredential.fields(of: service).contains { (values[$0] ?? "") != (model.subtitleCredential($0) ?? "") }
    }

    private func check(_ service: SubtitleService) {
        guard checks[service] != .checking else { return }
        let fields = typed(service)
        checks[service] = .checking
        Task {
            let answer = await model.checkAndSaveSubtitleCredentials(service, values: fields)
            checks[service] = answer
            // Saved: the fields show what is saved, no edit left.
            if answer == .accepted { SubtitleCredential.fields(of: service).forEach(show) }
        }
    }

    /// Leaving the screen saves what changed, once the service has accepted it.
    private func saveAll() {
        for service in [SubtitleService.openSubtitles, .subdl, .subSource] where changed(service) && checks[service] != .checking {
            let fields = typed(service)
            Task { _ = await model.checkAndSaveSubtitleCredentials(service, values: fields) }
        }
    }
}

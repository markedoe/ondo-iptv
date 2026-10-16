import SwiftUI
import IPTVCore
import UniformTypeIdentifiers

/// Product switches of the App Store version: the code stays, ready to be turned back on.
enum FeatureFlags {
    /// Real-Debrid accounts as a source. Off: Ondo presents itself as an IPTV player only.
    static let realDebrid = false

    #if TESTFLIGHT_DOWNLOADS || DEBUG
    /// Downloading movies and episodes to watch offline: in the TestFlight edition only. The App Store edition, the one
    /// sent to App Review, is built without the code at all (Apple's rules 5.2.3 on saving media from other sources and
    /// 2.3.1 on hidden or dormant features): the release workflow builds one edition or the other (`edition`). In
    /// development and tests it is on, except for the App Store screenshots, which show the App Store edition.
    static let downloads: Bool = {
        #if TESTFLIGHT_DOWNLOADS
        return true
        #else
        return !ProcessInfo.processInfo.arguments.contains("-uitest-showcase")
        #endif
    }()
    #endif
}

enum PlaylistKind: String, CaseIterable, Identifiable {
    case xtream, m3uURL, file, realDebrid

    var id: String { rawValue }

    /// Kinds offered when adding a playlist.
    static var available: [PlaylistKind] {
        allCases.filter { $0 != .realDebrid || FeatureFlags.realDebrid }
    }

    var label: LocalizedStringKey {
        switch self {
        case .xtream: return "Xtream Codes"
        case .m3uURL: return "M3U URL"
        case .file: return "File"
        case .realDebrid: return "Real-Debrid"
        }
    }
}

/// Add (or edit) a playlist: Xtream login, M3U link or local file, plus advanced options.
struct AddPlaylistView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    /// Playlist being edited, `nil` when adding.
    var editing: Playlist?

    @State private var kind: PlaylistKind = .xtream
    @State private var name = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var m3uURL = ""
    @State private var importedFileName: String?
    @State private var importedFileLabel: String?
    @State private var epgURLs = ""
    @State private var useProviderEPG = true
    @State private var epgShift: Double = 0
    @State private var userAgent = ""
    @State private var referrer = ""
    @State private var outputFormat: XtreamOutputFormat = .m3u8
    @State private var forceM3U = false
    @State private var autoRefreshHours = 24
    @State private var showingFileImporter = false
    @State private var isWorking = false
    @State private var errorMessage: String?
    @State private var errorAttribution: ErrorAttribution?
    @State private var detectedXtream: XtreamCredentials?
    @State private var realDebridToken = ""

    var body: some View {
        Form {
            if editing == nil {
                Section {
                    Picker("Type", selection: $kind) {
                        ForEach(PlaylistKind.available) { kind in
                            Text(kind.label).tag(kind)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }

            Section {
                TextField("Name", text: $name, prompt: Text("My provider"))
                    .accessibilityIdentifier("playlistName")
                switch kind {
                case .xtream:
                    TextField("Server", text: $server, prompt: Text("http://example.com:8080"))
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        #endif
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                case .m3uURL:
                    TextField("Playlist URL", text: $m3uURL, prompt: Text("http://…/playlist.m3u"), axis: .vertical)
                        .lineLimit(1...4)
                        .accessibilityIdentifier("playlistURL")
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        #endif
                        .onChange(of: m3uURL) { _, value in
                            detectedXtream = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap(XtreamCredentials.fromM3ULink)
                        }
                    if let detected = detectedXtream {
                        Button {
                            server = detected.server.absoluteString
                            username = detected.username
                            password = detected.password
                            kind = .xtream
                        } label: {
                            Label("This is an Xtream link: use the Xtream API (faster, with movie and series details)", systemImage: "wand.and.stars")
                                .font(.callout)
                        }
                    }
                case .file:
                    Button {
                        showingFileImporter = true
                    } label: {
                        Label(importedFileLabel ?? String(localized: "Choose a file…"), systemImage: "doc")
                    }
                case .realDebrid:
                    SecureField("API token", text: $realDebridToken)
                        .autocorrectionDisabled()
                }
            } header: {
                Text("Playlist")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if kind == .xtream {
                        Text("Use the server address, username and password sent by your provider.")
                    } else if kind == .realDebrid {
                        Text("Your private API token is on real-debrid.com/apitoken. The video files of your downloads become movies and series.")
                    }
                    Legal.disclaimer
                }
            }

            Section {
                TextField("Additional EPG URLs (XMLTV)", text: $epgURLs, prompt: Text("http://…/guide.xml.gz"), axis: .vertical)
                    .lineLimit(1...4)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                Toggle("Use the provider's guide", isOn: $useProviderEPG)
                Stepper(value: $epgShift, in: -12...12, step: 0.5) {
                    LabeledContent("Guide time shift", value: epgShift == 0 ? "0 h" : String(format: "%+.1f h", epgShift))
                }
            } header: {
                Text("TV Guide")
            } footer: {
                Text("One URL per line. Gzip compressed guides (.xml.gz) are supported.")
            }

            Section("Advanced") {
                if kind == .xtream {
                    Picker("Live stream format", selection: $outputFormat) {
                        Text("HLS (m3u8) – recommended").tag(XtreamOutputFormat.m3u8)
                        Text("MPEG-TS (ts)").tag(XtreamOutputFormat.ts)
                    }
                    Toggle("Force M3U (use the M3U export instead of the API)", isOn: $forceM3U)
                }
                Picker("Auto refresh", selection: $autoRefreshHours) {
                    Text("Manual").tag(0)
                    Text("Every 6 hours").tag(6)
                    Text("Every 12 hours").tag(12)
                    Text("Every day").tag(24)
                    Text("Every week").tag(168)
                }
                TextField("User-Agent", text: $userAgent, prompt: Text("Default"))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                TextField("Referer", text: $referrer, prompt: Text("None"))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
            }

            if let errorMessage {
                Section {
                    Label {
                        VStack(alignment: .leading, spacing: 4) {
                            if let errorAttribution { Text(errorAttribution.label).font(.headline) }
                            Text(errorMessage)
                        }
                    } icon: {
                        Image(systemName: errorAttribution?.systemImage ?? "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(editing == nil ? "Add Playlist" : "Edit Playlist")
        .inlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                if isWorking {
                    ProgressView()
                } else {
                    Button(editing == nil ? String(localized: "Add") : String(localized: "Save")) {
                        Task { await submit() }
                    }
                    .disabled(!isValid)
                    .accessibilityIdentifier("confirmAddPlaylist")
                }
            }
        }
        .disabled(isWorking)
        .fileImporter(isPresented: $showingFileImporter, allowedContentTypes: Self.playlistTypes) { result in
            switch result {
            case .success(let url):
                do {
                    importedFileName = try model.importPlaylistFile(at: url)
                    importedFileLabel = url.lastPathComponent
                    if name.isEmpty { name = url.deletingPathExtension().lastPathComponent }
                } catch {
                    errorMessage = error.localizedDescription
                    errorAttribution = .app
                }
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
        .onAppear(perform: loadEditing)
    }

    static let playlistTypes: [UTType] = {
        var types: [UTType] = [.m3uPlaylist, .xml, .json, .plainText, .data]
        if let m3u8 = UTType(filenameExtension: "m3u8") { types.insert(m3u8, at: 0) }
        if let xspf = UTType(filenameExtension: "xspf") { types.append(xspf) }
        return types
    }()

    private var isValid: Bool {
        switch kind {
        case .xtream:
            return XtreamCredentials.normalizeServer(server) != nil && !username.isEmpty && !password.isEmpty
        case .m3uURL:
            return URL(string: m3uURL.trimmingCharacters(in: .whitespacesAndNewlines))?.scheme != nil
        case .file:
            return importedFileName != nil
        case .realDebrid:
            return realDebridToken.trimmingCharacters(in: .whitespaces).count >= 20
        }
    }

    private func loadEditing() {
        guard let playlist = editing else { return }
        name = playlist.name
        switch playlist.source {
        case .xtream(let credentials):
            kind = .xtream
            server = credentials.server.absoluteString
            username = credentials.username
            password = credentials.password
        case .remote(let url):
            kind = .m3uURL
            m3uURL = url.absoluteString
        case .local(let fileName):
            kind = .file
            importedFileName = fileName
            importedFileLabel = fileName.split(separator: "-", maxSplits: 5).last.map(String.init) ?? fileName
        case .realDebrid(let token):
            kind = .realDebrid
            realDebridToken = token
        }
        epgURLs = playlist.epgURLs.map(\.absoluteString).joined(separator: "\n")
        useProviderEPG = playlist.useProviderEPG
        epgShift = playlist.epgTimeShiftHours
        userAgent = playlist.headers.userAgent ?? ""
        referrer = playlist.headers.referrer ?? ""
        outputFormat = playlist.xtreamOutput
        forceM3U = playlist.xtreamForceM3U
        autoRefreshHours = playlist.autoRefreshHours
    }

    private func buildPlaylist() -> Playlist? {
        let source: PlaylistSource
        switch kind {
        case .xtream:
            guard let serverURL = XtreamCredentials.normalizeServer(server) else { return nil }
            source = .xtream(XtreamCredentials(
                server: serverURL,
                username: username.trimmingCharacters(in: .whitespaces),
                password: password
            ))
        case .m3uURL:
            guard let url = URL(string: m3uURL.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
            source = .remote(url)
        case .file:
            guard let importedFileName else { return nil }
            source = .local(fileName: importedFileName)
        case .realDebrid:
            source = .realDebrid(token: realDebridToken.trimmingCharacters(in: .whitespaces))
        }
        let defaultName: String
        switch source {
        case .xtream(let credentials): defaultName = credentials.server.host ?? "Xtream"
        case .remote(let url): defaultName = url.host ?? "M3U"
        case .local: defaultName = importedFileLabel ?? "Playlist"
        case .realDebrid: defaultName = "Real-Debrid"
        }
        var playlist = editing ?? Playlist(name: "", source: source)
        playlist.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? defaultName : name.trimmingCharacters(in: .whitespaces)
        playlist.source = source
        playlist.epgURLs = epgURLs
            .split(whereSeparator: { $0.isNewline || $0 == "," || $0 == " " })
            .compactMap { URL(string: String($0)) }
            .filter { $0.scheme != nil }
        playlist.useProviderEPG = useProviderEPG
        playlist.epgTimeShiftHours = epgShift
        playlist.headers = HTTPHeaders(
            userAgent: userAgent.trimmingCharacters(in: .whitespaces).isEmpty ? nil : userAgent.trimmingCharacters(in: .whitespaces),
            referrer: referrer.trimmingCharacters(in: .whitespaces).isEmpty ? nil : referrer.trimmingCharacters(in: .whitespaces)
        )
        playlist.xtreamOutput = outputFormat
        playlist.xtreamForceM3U = forceM3U
        playlist.autoRefreshHours = autoRefreshHours
        return playlist
    }

    private func submit() async {
        guard let playlist = buildPlaylist() else { return }
        errorMessage = nil
        errorAttribution = nil
        isWorking = true
        defer { isWorking = false }
        if editing != nil {
            await model.updatePlaylist(playlist)
            dismiss()
            return
        }
        do {
            try await model.addPlaylist(playlist)
            dismiss()
        } catch {
            errorAttribution = ErrorAttribution.classify(error)
            errorMessage = error.localizedDescription
        }
    }
}

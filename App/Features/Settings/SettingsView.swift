import SwiftUI
import IPTVCore
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showingExporter = false
    @State private var showingImporter = false
    @State private var backupDocument: BackupDocument?
    @State private var message: String?
    @State private var cacheSize: Int64 = 0
    @State private var confirmClearHistory = false
    @State private var tmdbKey = ""
    @State private var tmdbCheck: TMDbCheck?
    @Environment(\.openURL) private var openURL
    @State private var awaitingUnlock: BackupAction?
    @State private var unlockedAction: BackupAction?

    private func binding<T>(_ keyPath: WritableKeyPath<AppSettings, T>) -> Binding<T> {
        Binding(
            get: { model.settings[keyPath: keyPath] },
            set: { value in model.updateSettings { $0[keyPath: keyPath] = value } }
        )
    }

    /// The viewer's own TMDb token, named as on TMDb's API page, checked with TMDb before it is saved.
    private var tmdbSection: some View {
        Section {
            SecureField("API Read Access Token", text: $tmdbKey)
                .onSubmit(checkTMDbKey)
            HStack {
                Button("Check and Save", action: checkTMDbKey)
                    .disabled(tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || tmdbCheck == .checking)
                if tmdbCheck == .checking {
                    ProgressView().controlSize(.small)
                }
                if model.hasTMDbKey {
                    Spacer()
                    Button("Remove", role: .destructive) {
                        tmdbKey = ""
                        tmdbCheck = nil
                        model.setTMDbCredential(nil)
                    }
                }
            }
            tmdbStatus
            Link(destination: URL(string: "https://www.themoviedb.org/settings/api")!) {
                Text("Open TMDb's API Page")
            }
        } header: {
            Text(verbatim: "TMDb")
        } footer: {
            Text("""
            On themoviedb.org: your account → Settings → API. Copy the API Read Access Token (the long one); the API \
            Key works too. This product uses the TMDB API but is not endorsed or certified by TMDB.
            """)
        }
    }

    @ViewBuilder
    private var tmdbStatus: some View {
        switch tmdbCheck {
        case .refused:
            Label("TMDb refused it. Copy the whole API Read Access Token again (the API Key works too).", systemImage: "xmark.octagon")
                .font(.caption)
                .foregroundStyle(.red)
        case .unreachable:
            Label("TMDb could not be reached, so nothing was saved. Check the connection and try again.", systemImage: "wifi.exclamationmark")
                .font(.caption)
                .foregroundStyle(.orange)
        case .checking, .accepted, nil:
            if model.hasTMDbKey {
                Label("Accepted by TMDb: trending titles, missing background images and filmographies come from TMDb.", systemImage: "checkmark.circle")
                    .font(.caption)
                    .foregroundStyle(.green)
            }
        }
    }

    /// The language Ondo shows itself in, in that language ("Français").
    /// Automatic, then the buffer lengths in seconds (`PlaybackBuffer`).
    private var bufferChoices: some View {
        ForEach(PlaybackBuffer.choices, id: \.self) { seconds in
            if seconds == 0 {
                Text("Automatic").tag(seconds)
            } else {
                Text(verbatim: Duration.seconds(seconds).formatted(.units(allowed: [.seconds], width: .abbreviated))).tag(seconds)
            }
        }
    }

    private static var appLanguageName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale.current.localizedString(forIdentifier: code)?.capitalized(with: Locale.current) ?? code
    }

    /// The system page where Ondo's language is chosen: Ondo's page in Settings (iPhone, iPad), Language & Region on
    /// the Mac.
    private func openAppLanguageSettings() {
        #if os(macOS)
        let address = "x-apple.systempreferences:com.apple.Localization-Settings.extension"
        #else
        let address = UIApplication.openSettingsURLString
        #endif
        if let url = URL(string: address) { openURL(url) }
    }

    private func checkTMDbKey() {
        let value = tmdbKey
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, tmdbCheck != .checking else { return }
        tmdbCheck = .checking
        Task {
            let check = await model.checkAndSaveTMDbCredential(value)
            tmdbCheck = check
            if check == .accepted { tmdbKey = "" }
        }
    }

    /// The app's version and its build number, as App Store Connect and TestFlight list them ("1.1.0 (24)"); the
    /// TestFlight edition says it is a beta.
    private var versionText: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let version = (info?["CFBundleVersion"] as? String).map { "\(short) (\($0))" } ?? short
        #if TESTFLIGHT_DOWNLOADS
        return BetaBadge.version(version)
        #else
        return version
        #endif
    }

    var body: some View {
        Form {
            Section {
                NavigationLink {
                    // Re-adding a playlist would bring it back without its locks: behind the PIN like backups.
                    ParentalGate { PlaylistsSettingsView() }
                } label: {
                    LabeledContent {
                        Text("\(model.playlists.count)")
                    } label: {
                        Label("Manage Playlists", systemImage: "list.bullet.rectangle")
                    }
                }
                if model.isXtream {
                    NavigationLink {
                        AccountInfoView()
                    } label: {
                        Label("Account Information", systemImage: "person.text.rectangle")
                    }
                }
                NavigationLink {
                    CategoryManagerView()
                } label: {
                    Label("Languages and Categories", systemImage: "line.3.horizontal.decrease.circle")
                }
                NavigationLink {
                    HiddenChannelsView()
                } label: {
                    LabeledContent {
                        Text("\(model.hiddenChannelIDs.count)")
                    } label: {
                        Label("Hidden Channels", systemImage: "eye.slash.circle")
                    }
                }
                Toggle(isOn: binding(\.hideWatchedMovies)) {
                    Label("Hide watched movies", systemImage: "eye.slash")
                }
            } header: {
                Text("Playlists")
            } footer: {
                Text("Movies you have finished no longer appear in Movies, on Home or in More Like This. Search, My List and collections still show them.")
            }

            Section {
                if PlayerEngine.isVLCAvailable {
                    Picker(selection: binding(\.playerEngine)) {
                        ForEach(PlayerEngine.allCases) { engine in
                            Text(engine.label).tag(engine)
                        }
                    } label: {
                        Label("Player Engine", systemImage: "cpu")
                    }
                }
                Picker(selection: binding(\.liveBuffer)) {
                    bufferChoices
                } label: {
                    Label("Buffer for live TV", systemImage: "gauge.with.dots.needle.33percent")
                }
                Picker(selection: binding(\.vodBuffer)) {
                    bufferChoices
                } label: {
                    Label("Buffer for movies & series", systemImage: "gauge.with.dots.needle.33percent")
                }
                Picker(selection: binding(\.defaultAspect)) {
                    ForEach(VideoAspect.allCases) { aspect in
                        Text(aspect.label).tag(aspect)
                    }
                } label: {
                    Label("Aspect Ratio", systemImage: "aspectratio")
                }
                Toggle(isOn: binding(\.resumePlayback)) {
                    Label("Resume movies and episodes", systemImage: "play.circle")
                }
                Toggle(isOn: binding(\.autoRetryStreams)) {
                    Label("Reconnect dropped streams", systemImage: "arrow.triangle.2.circlepath")
                }
                Toggle(isOn: binding(\.autoplayLastChannel)) {
                    Label("Play last channel at launch", systemImage: "tv")
                }
                Toggle(isOn: binding(\.showChannelNumbers)) {
                    Label("Show channel numbers", systemImage: "number")
                }
                #if os(iOS)
                Toggle(isOn: binding(\.landscapeForLive)) {
                    Label("Landscape for live TV", systemImage: "rotate.right")
                }
                Toggle(isOn: binding(\.landscapeForVOD)) {
                    Label("Landscape for movies & series", systemImage: "rotate.right")
                }
                #endif
            } header: {
                Text("Player")
            } footer: {
                Text("A longer buffer covers longer interruptions from the provider; the picture then takes longer to appear and to come back after a skip. Automatic adapts to the provider.")
            }

            Section {
                NavigationLink {
                    RemindersView()
                } label: {
                    LabeledContent {
                        Text("\(model.upcomingReminders.count)")
                    } label: {
                        Label("Reminders", systemImage: "bell")
                    }
                }
                Stepper(value: binding(\.epgDaysAhead), in: 1...7) {
                    Label("Days of guide: \(model.settings.epgDaysAhead)", systemImage: "calendar")
                }
                Button {
                    model.loadEPG(force: true)
                } label: {
                    HStack {
                        Label("Refresh TV Guide", systemImage: "arrow.clockwise")
                        Spacer()
                        if model.epgPhase.isLoading { ProgressView().controlSize(.small) }
                    }
                }
                if case .failed(let error, _) = model.epgPhase {
                    Text(error).font(.caption).foregroundStyle(.red)
                } else if !model.epg.isEmpty {
                    LabeledContent("Programmes", value: model.epg.data.programCount.formatted())
                }
            } header: {
                Text("TV Guide")
            } footer: {
                Text("Extra XMLTV sources and time shift are set per playlist.")
            }

            Section("Profiles") {
                NavigationLink {
                    ParentalGate { ProfilesSettingsView() }
                } label: {
                    LabeledContent {
                        Text(model.activeProfile.name)
                    } label: {
                        Label("Profiles", systemImage: "person.2")
                    }
                }
            }

            Section("Parental Control") {
                NavigationLink {
                    ParentalGate { ParentalControlView() }
                } label: {
                    LabeledContent {
                        Text(model.settings.parentalControlEnabled ? "On" : "Off")
                    } label: {
                        Label("Parental Control", systemImage: "lock.shield")
                    }
                }
            }

            Section {
                NavigationLink {
                    SubtitleServicesView()
                } label: {
                    LabeledContent {
                        Text(model.subtitleServices.isEmpty ? String(localized: "Off") : "\(model.subtitleServices.count)")
                    } label: {
                        Label("Online Subtitles", systemImage: "captions.bubble")
                    }
                }
            } header: {
                Text("Subtitles")
            } footer: {
                Text("Your own keys for OpenSubtitles, SubDL and SubSource, to find subtitles from the player.")
            }

            tmdbSection

            Section {
                Toggle("Update Awards Automatically", isOn: binding(\.updateAwards))
            } header: {
                Text("Awards")
            } footer: {
                Text("Once a week, Ondo downloads the latest awards and festival selections (made from Wikidata) from GitHub, where the project is hosted. Nothing about you is sent.")
            }

            Section {
                Picker(selection: binding(\.appearance)) {
                    ForEach(AppearancePreference.allCases) { appearance in
                        Text(appearance.label).tag(appearance)
                    }
                } label: {
                    Label("Theme", systemImage: "circle.lefthalf.filled")
                }
                LabeledContent {
                    Button(Self.appLanguageName, action: openAppLanguageSettings)
                } label: {
                    Label("App Language", systemImage: "globe")
                }
            } header: {
                Text("Appearance")
            } footer: {
                #if os(macOS)
                Text("Ondo's language is chosen in System Settings → General → Language & Region → Applications.")
                #else
                Text("Ondo's language is chosen in the Settings app, on Ondo's page.")
                #endif
            }

            Section {
                // Sync brings playlists and their logins in and sends them out, like backups: behind the PIN too.
                Toggle(isOn: Binding(
                    get: { model.settings.syncOnLocalNetwork },
                    set: { on in guarded(on ? .syncOn : .syncOff) }
                )) {
                    Label("Sync on this network", systemImage: "arrow.triangle.2.circlepath.circle")
                }
                .disabled(backupsBlocked)
                if model.settings.syncOnLocalNetwork {
                    LocalSyncStatus()
                }
            } footer: {
                // One sentence group, the same as Android's: the translation checks read single-line literals.
                // swiftlint:disable:next line_length
                Text("While Ondo is open, devices on this network with this setting on keep the same playlists, My List, collections and progress. iPhone, iPad and Mac sync with the network’s other Ondo devices, not with each other. Nothing leaves the local network, but playlist logins travel unencrypted on it: turn this on at home only.")
            }

            Section {
                Button {
                    guarded(.export)
                } label: {
                    Label("Export Backup…", systemImage: "square.and.arrow.up")
                }
                .disabled(backupsBlocked)
                Button {
                    guarded(.restore)
                } label: {
                    Label("Restore Backup…", systemImage: "square.and.arrow.down")
                }
                .disabled(backupsBlocked)
                Button {
                    Task {
                        await model.clearCaches()
                        cacheSize = await model.cacheSize()
                        message = String(localized: "Cache cleared.")
                    }
                } label: {
                    LabeledContent {
                        Text(Formatting.bytes(cacheSize))
                    } label: {
                        Label("Clear Cache", systemImage: "trash")
                    }
                }
                Button(role: .destructive) {
                    confirmClearHistory = true
                } label: {
                    Label("Clear Watch History", systemImage: "clock.badge.xmark")
                }
                if let message {
                    Text(message).font(.caption).foregroundStyle(.secondary)
                }
            } header: {
                Text("Data")
            } footer: {
                Text("Backups contain your playlists (including logins), My List, collections and history. Keep them private.")
            }

            Section("About") {
                NavigationLink {
                    HelpView()
                } label: {
                    Label("Help", systemImage: "questionmark.circle")
                }
                .accessibilityIdentifier("helpLink")
                LabeledContent("Version", value: versionText)
                Legal.disclaimer
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
            #endif
        }
        .task { cacheSize = await model.cacheSize() }
        .fileExporter(isPresented: $showingExporter, document: backupDocument, contentType: .json, defaultFilename: "Ondo-Backup") { result in
            if case .failure(let error) = result { message = error.localizedDescription }
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            Task {
                do {
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    try await model.importBackup(Data(contentsOf: url))
                    message = String(localized: "Backup restored.")
                } catch {
                    message = error.localizedDescription
                }
            }
        }
        .confirmationDialog("Clear the watch history?", isPresented: $confirmClearHistory, titleVisibility: .visible) {
            Button("Clear History", role: .destructive) { model.clearHistory() }
        }
        .sheet(item: $awaitingUnlock, onDismiss: {
            if let action = unlockedAction {
                unlockedAction = nil
                perform(action)
            }
        }) { action in
            PINPromptView { unlockedAction = action }
        }
    }

    /// A backup holds the provider logins, and restoring one changes the playlists: under parental control,
    /// both need the PIN. So does turning sync on or off, which sends and brings the same.
    private enum BackupAction: String, Identifiable {
        case export, restore, syncOn, syncOff
        var id: String { rawValue }
    }

    /// No PIN can unlock (a kids profile on a device without one): backups wait for a parent's profile.
    private var backupsBlocked: Bool {
        model.settingsNeedParentalUnlock && !model.hasParentalPIN
    }

    private func guarded(_ action: BackupAction) {
        if model.settingsNeedParentalUnlock {
            awaitingUnlock = action
        } else {
            perform(action)
        }
    }

    private func perform(_ action: BackupAction) {
        switch action {
        case .export:
            do {
                backupDocument = BackupDocument(data: try model.exportBackup())
                showingExporter = true
            } catch {
                message = error.localizedDescription
            }
        case .restore:
            showingImporter = true
        case .syncOn, .syncOff:
            model.setSyncOnLocalNetwork(action == .syncOn)
        }
    }
}

/// What sync on the local network found, and how it went with each device; or why it does not run.
private struct LocalSyncStatus: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sync = model.localSync
        if !model.syncsThisProfile {
            Label("Only the main profile syncs.", systemImage: "person.crop.circle.badge.exclamationmark")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if sync.problem == .localNetworkDenied {
            Label("Ondo may not use the local network. Allow it in the system settings, in Privacy & Security, Local Network.", systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(.orange)
        } else if sync.devices.isEmpty {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Looking for other devices with Ondo open…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else {
            ForEach(sync.devices) { device in
                LabeledContent {
                    status(of: device)
                } label: {
                    Label {
                        Text(verbatim: device.name)
                    } icon: {
                        Image(systemName: "tv")
                    }
                }
            }
        }
    }

    private func status(of device: LocalSync.Device) -> Text {
        if device.failures > 0 { return Text("Not reached, trying again") }
        if let syncedAt = device.syncedAt { return Text("Synced at \(syncedAt.formatted(date: .omitted, time: .shortened))") }
        return Text("Found")
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

// MARK: - Playlists

struct PlaylistsSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: Playlist?
    @State private var showingAdd = false
    @State private var pendingDelete: Playlist?

    var body: some View {
        List {
            ForEach(model.playlists) { playlist in
                HStack(spacing: 12) {
                    Image(systemName: playlist.id == model.activePlaylist?.id ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(playlist.id == model.activePlaylist?.id ? Color.accentColor : .secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(playlist.name).font(.body.weight(.medium))
                        Text(playlist.source.kindLabel + (playlist.lastRefreshedAt.map { " · " + Formatting.relative($0) } ?? ""))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button {
                        editing = playlist
                    } label: {
                        Image(systemName: "pencil")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Edit")
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    Task { await model.activate(playlist) }
                }
                .contextMenu {
                    Button("Edit") { editing = playlist }
                    Button("Refresh") {
                        Task {
                            await model.activate(playlist)
                            await model.refreshActivePlaylist()
                        }
                    }
                    Button("Delete", role: .destructive) { pendingDelete = playlist }
                }
            }
            .onMove { model.movePlaylists(fromOffsets: $0, toOffset: $1) }
            .onDelete { offsets in
                if let first = offsets.first { pendingDelete = model.playlists[first] }
            }
        }
        .navigationTitle("Playlists")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showingAdd = true
                } label: {
                    Label("Add Playlist", systemImage: "plus")
                }
            }
        }
        .sheet(item: $editing) { playlist in
            NavigationStack { AddPlaylistView(editing: playlist) }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 600)
                #endif
        }
        .sheet(isPresented: $showingAdd) {
            NavigationStack { AddPlaylistView() }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 600)
                #endif
        }
        .confirmationDialog(
            "Delete this playlist?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { playlist in
            Button("Delete \(playlist.name)", role: .destructive) {
                Task { await model.deletePlaylist(playlist) }
            }
        } message: { _ in
            Text("Its items in My List, collections and history will be removed too.")
        }
    }
}

struct AccountInfoView: View {
    @Environment(AppModel.self) private var model
    @State private var account: XtreamAccountInfo?
    @State private var error: String?
    @State private var isChecking = false

    var body: some View {
        Form {
            if let account = account ?? model.content.account {
                Section("Account") {
                    LabeledContent("Username", value: account.username)
                    LabeledContent("Status") {
                        Label(account.status, systemImage: account.isActive ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(account.isActive ? .green : .orange)
                    }
                    if let expiry = account.expiresAt {
                        LabeledContent("Expires", value: expiry.formatted(date: .long, time: .omitted))
                    } else {
                        LabeledContent("Expires", value: String(localized: "Never"))
                    }
                    if account.isTrial { LabeledContent("Trial", value: String(localized: "Yes")) }
                    if let created = account.createdAt {
                        LabeledContent("Created", value: created.formatted(date: .long, time: .omitted))
                    }
                }
                Section("Connections") {
                    LabeledContent("Active", value: account.activeConnections.map(String.init) ?? "–")
                    LabeledContent("Maximum", value: account.maxConnections.map(String.init) ?? "–")
                    LabeledContent("Formats", value: account.allowedOutputFormats.joined(separator: ", "))
                    if let timezone = account.serverTimeZone {
                        LabeledContent("Server time zone", value: timezone)
                    }
                }
                if let message = account.message {
                    Section("Message from the provider") { Text(message) }
                }
            }
            if let error {
                Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
            }
            Section {
                Button {
                    Task { await check() }
                } label: {
                    HStack {
                        Label("Test Connection", systemImage: "antenna.radiowaves.left.and.right")
                        Spacer()
                        if isChecking { ProgressView().controlSize(.small) }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Account")
        .task { await check() }
    }

    private func check() async {
        guard let credentials = model.activePlaylist?.source.xtreamCredentials else { return }
        isChecking = true
        defer { isChecking = false }
        do {
            account = try await XtreamClient(credentials: credentials, http: model.http, headers: model.activePlaylist?.headers ?? .none).authenticate()
            error = nil
        } catch let failure {
            error = failure.localizedDescription
        }
    }
}

// MARK: - Categories & parental control

struct ParentalControlView: View {
    @Environment(AppModel.self) private var model
    @State private var settingPIN = false
    @State private var type: ContentType = .live

    var body: some View {
        Form {
            Section {
                Toggle("Parental control", isOn: Binding(
                    get: { model.settings.parentalControlEnabled && model.hasParentalPIN },
                    set: { enabled in
                        if enabled {
                            settingPIN = true
                        } else {
                            model.setParentalPIN(nil)
                        }
                    }
                ))
                if model.settings.parentalControlEnabled && model.hasParentalPIN {
                    Button("Change PIN") { settingPIN = true }
                    if model.isParentalUnlocked {
                        Button("Lock Now") { model.lockParentalControl() }
                    }
                }
                Toggle("Lock adult categories automatically", isOn: Binding(
                    get: { model.settings.lockAdultCategoriesAutomatically },
                    set: { value in model.updateSettings { $0.lockAdultCategoriesAutomatically = value } }
                ))
            } footer: {
                Text("Locked categories are hidden from home, search and My List, and ask for the PIN when opened.")
            }

            if model.settings.parentalControlEnabled && model.hasParentalPIN {
                Section("Locked Categories") {
                    Picker("Type", selection: $type) {
                        Text("Live TV").tag(ContentType.live)
                        Text("Movies").tag(ContentType.movie)
                        Text("Series").tag(ContentType.series)
                    }
                    .pickerStyle(.segmented)
                    ForEach(model.content.categories(for: type)) { category in
                        Toggle(category.name, isOn: Binding(
                            get: { model.isLockConfigured(category) },
                            set: { model.setLocked($0, category: category) }
                        ))
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Parental Control")
        .sheet(isPresented: $settingPIN) {
            SetPINView()
        }
    }
}

struct SetPINView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var confirmation = ""

    private var isValid: Bool {
        pin.count >= 4 && pin.count <= 8 && pin.allSatisfy(\.isNumber) && pin == confirmation
    }

    var body: some View {
        NavigationStack {
            Form {
                SecureField("New PIN (4 to 8 digits)", text: $pin)
                SecureField("Confirm PIN", text: $confirmation)
                if !confirmation.isEmpty && pin != confirmation {
                    Text("The PINs do not match.").foregroundStyle(.red)
                }
            }
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .formStyle(.grouped)
            .navigationTitle("Set PIN")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.setParentalPIN(pin)
                        dismiss()
                    }
                    .disabled(!isValid)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 220)
        #endif
    }
}

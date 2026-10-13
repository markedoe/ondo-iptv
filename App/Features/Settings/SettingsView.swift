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

    private func binding<T>(_ keyPath: WritableKeyPath<AppSettings, T>) -> Binding<T> {
        Binding(
            get: { model.settings[keyPath: keyPath] },
            set: { value in model.updateSettings { $0[keyPath: keyPath] = value } }
        )
    }

    var body: some View {
        Form {
            Section("Playlists") {
                NavigationLink {
                    PlaylistsSettingsView()
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
                    Label("Hidden Categories", systemImage: "eye.slash")
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
            }

            Section("Player") {
                if PlayerEngine.isVLCAvailable {
                    Picker(selection: binding(\.playerEngine)) {
                        ForEach(PlayerEngine.allCases) { engine in
                            Text(engine.label).tag(engine)
                        }
                    } label: {
                        Label("Player Engine", systemImage: "cpu")
                    }
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
                    ProfilesSettingsView()
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
                    ParentalControlView()
                } label: {
                    LabeledContent {
                        Text(model.settings.parentalControlEnabled ? "On" : "Off")
                    } label: {
                        Label("Parental Control", systemImage: "lock.shield")
                    }
                }
            }

            Section {
                SecureField("TMDb API key or read access token", text: $tmdbKey)
                    .onSubmit { model.setTMDbCredential(tmdbKey) }
                HStack {
                    Button("Save Key") { model.setTMDbCredential(tmdbKey) }
                        .disabled(tmdbKey.isEmpty)
                    if model.hasTMDbKey {
                        Spacer()
                        Button("Remove", role: .destructive) {
                            tmdbKey = ""
                            model.setTMDbCredential(nil)
                        }
                    }
                }
                if model.hasTMDbKey {
                    Label("Trending titles are shown on the home screen.", systemImage: "checkmark.circle")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            } header: {
                Text("Trending (TMDb)")
            } footer: {
                Text("Optional. Create a key on themoviedb.org. This product uses the TMDB API but is not endorsed or certified by TMDB.")
            }

            Section("Appearance") {
                Picker(selection: binding(\.appearance)) {
                    ForEach(AppearancePreference.allCases) { appearance in
                        Text(appearance.label).tag(appearance)
                    }
                } label: {
                    Label("Theme", systemImage: "circle.lefthalf.filled")
                }
            }

            Section {
                Button {
                    do {
                        backupDocument = BackupDocument(data: try model.exportBackup())
                        showingExporter = true
                    } catch {
                        message = error.localizedDescription
                    }
                } label: {
                    Label("Export Backup…", systemImage: "square.and.arrow.up")
                }
                Button {
                    showingImporter = true
                } label: {
                    Label("Restore Backup…", systemImage: "square.and.arrow.down")
                }
                Button {
                    Task {
                        await model.clearCaches()
                        cacheSize = model.cacheSize()
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
                Text("Backups contain your playlists (including logins), favorites and history. Keep them private.")
            }

            Section("About") {
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
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
        .onAppear { cacheSize = model.cacheSize() }
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
            Text("Its favorites and history will be removed too.")
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

struct CategoryManagerView: View {
    @Environment(AppModel.self) private var model
    @State private var type: ContentType = .live

    var body: some View {
        List {
            Picker("Type", selection: $type) {
                Text("Live TV").tag(ContentType.live)
                Text("Movies").tag(ContentType.movie)
                Text("Series").tag(ContentType.series)
            }
            .pickerStyle(.segmented)
            ForEach(model.content.categories(for: type)) { category in
                Toggle(isOn: Binding(
                    get: { !model.isHidden(category) },
                    set: { model.setHidden(!$0, category: category) }
                )) {
                    HStack {
                        Text(category.name)
                        Spacer()
                        Text("\(model.count(in: category))").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Visible Categories")
    }
}

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
                Text("Locked categories are hidden from home, search and favorites, and ask for the PIN when opened.")
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

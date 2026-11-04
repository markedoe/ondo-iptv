import SwiftUI
import IPTVCore

enum ProfileStyle {
    static let colors: [Color] = [.indigo, .pink, .orange, .teal, .green, .purple, .blue, .red]
    /// Every avatar offered: the initial, the icons, then the emoji (the same as Android's, docs/PROFILES.md).
    static let choices = [Profile.initialSymbol] + Profile.symbols + Profile.emoji

    static func color(_ index: Int) -> Color { colors[abs(index) % colors.count] }
}

struct ProfileAvatar: View {
    let profile: Profile
    var size: CGFloat = 64

    var body: some View {
        ZStack {
            Circle()
                .fill(ProfileStyle.color(profile.colorIndex).gradient)
            switch profile.avatar {
            case .symbol(let name):
                Image(systemName: name)
                    .font(.system(size: size * 0.45, weight: .semibold))
                    .foregroundStyle(.white)
            case .emoji(let emoji):
                Text(verbatim: emoji)
                    .font(.system(size: size * 0.5))
            case .initial:
                Text(verbatim: profile.initial)
                    .font(.system(size: size * 0.45, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .overlay(alignment: .bottomTrailing) {
            if profile.isProtected {
                Image(systemName: "lock.fill")
                    .font(.system(size: size * 0.18))
                    .padding(size * 0.06)
                    .background(.regularMaterial, in: Circle())
                    .offset(x: size * 0.08, y: size * 0.08)
            }
        }
    }
}

/// "Who's watching?" screen at launch; with `switching`, the switcher the toolbar's avatar opens.
struct ProfilePickerView: View {
    @Environment(AppModel.self) private var model
    var switching = false
    var onSwitched: () -> Void = {}
    @State private var pinFor: Profile?
    @State private var pin = ""
    @State private var wrongPIN = false
    @State private var alwaysOpen = false

    private func open(_ profile: Profile) {
        if alwaysOpen, !switching { model.setLaunchProfile(profile) }
        model.switchProfile(to: profile)
        onSwitched()
    }

    var body: some View {
        VStack(spacing: 36) {
            if !switching {
                Text("Who's watching?")
                    .font(.largeTitle.bold())
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120, maximum: 160), spacing: 24)], spacing: 24) {
                ForEach(model.profiles) { profile in
                    Button {
                        if switching, profile.id == model.activeProfile.id {
                            onSwitched()
                        } else if profile.isProtected {
                            pin = ""
                            wrongPIN = false
                            pinFor = profile
                        } else {
                            open(profile)
                        }
                    } label: {
                        VStack(spacing: 10) {
                            ProfileAvatar(profile: profile, size: 96)
                            Text(profile.name).font(.headline)
                            if switching, profile.id == model.activeProfile.id {
                                Text("Current").font(.caption).foregroundStyle(.secondary)
                            } else if profile.isKids {
                                Text("Kids").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: 640)
            if switching {
                Toggle("Always open this profile", isOn: Binding(
                    get: { model.settings.launchProfileID == model.activeProfile.id },
                    set: { model.setLaunchProfile($0 ? model.activeProfile : nil) }
                ))
                .frame(maxWidth: 360)
            } else {
                Toggle("Always open this profile", isOn: $alwaysOpen)
                    .frame(maxWidth: 360)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .alert("Profile PIN", isPresented: Binding(get: { pinFor != nil }, set: { if !$0 { pinFor = nil } })) {
            SecureField("PIN", text: $pin)
            Button("Open") {
                guard let profile = pinFor else { return }
                pinFor = nil
                if model.verifyPIN(pin, for: profile) {
                    open(profile)
                } else {
                    // The alert closes with its button: ask again, saying the PIN was wrong.
                    pin = ""
                    wrongPIN = true
                    Task {
                        try? await Task.sleep(for: .milliseconds(350))
                        pinFor = profile
                    }
                }
            }
            Button("Cancel", role: .cancel) { pinFor = nil }
        } message: {
            if model.pinRetryDate() != nil {
                Text("Too many wrong PINs. Wait a moment and try again.")
            } else {
                Text(wrongPIN ? "Wrong PIN" : "Enter the PIN of this profile.")
            }
        }
    }
}

/// The profile in use, a round avatar at the top right next to Settings: it opens the switcher (switch, add,
/// rename, delete, change the avatar; docs/PROFILES.md).
struct ProfileMenu: View {
    @Environment(AppModel.self) private var model
    @State private var showingSwitcher = false

    var body: some View {
        Button {
            model.flush()
            showingSwitcher = true
        } label: {
            ProfileAvatar(profile: model.activeProfile, size: 26)
        }
        .buttonStyle(.plain)
        .help("Switch profile")
        .accessibilityLabel("Switch profile")
        .accessibilityValue(Text(verbatim: model.activeProfile.name))
        .sheet(isPresented: $showingSwitcher) {
            NavigationStack { ProfileSwitcherView { showingSwitcher = false } }
        }
    }
}

/// The switcher: the profiles to switch to, "Always open this profile", and Edit (add, rename, delete, avatar).
struct ProfileSwitcherView: View {
    @Environment(AppModel.self) private var model
    let onDone: () -> Void

    var body: some View {
        ProfilePickerView(switching: true, onSwitched: onDone)
            .navigationTitle("Profiles")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done", action: onDone) }
                // What could weaken a Kids profile waits for a parent (`settingsNeedParentalUnlock`).
                if !model.settingsNeedParentalUnlock {
                    ToolbarItem(placement: .primaryAction) {
                        NavigationLink { ProfilesSettingsView() } label: { Text("Edit") }
                    }
                }
            }
            #if os(macOS)
            .frame(minWidth: 520, minHeight: 420)
            #endif
    }
}

struct ProfilesSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: Profile?
    @State private var adding = false

    var body: some View {
        List {
            ForEach(model.profiles) { profile in
                Button {
                    editing = profile
                } label: {
                    HStack(spacing: 14) {
                        ProfileAvatar(profile: profile, size: 40)
                        VStack(alignment: .leading) {
                            Text(profile.name).font(.body.weight(.medium))
                            HStack(spacing: 6) {
                                if profile.id == model.activeProfile.id { Text("Current") }
                                if profile.isKids { Text("Kids") }
                                if profile.isProtected { Text("PIN") }
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .onDelete { offsets in
                // Never the main profile (`canDelete`).
                for profile in offsets.map({ model.profiles[$0] }) { model.deleteProfile(profile) }
            }
        }
        .navigationTitle("Profiles")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    adding = true
                } label: {
                    Label("Add Profile", systemImage: "plus")
                }
            }
        }
        .sheet(item: $editing) { profile in
            NavigationStack { ProfileEditView(profile: profile) }
        }
        .sheet(isPresented: $adding) {
            NavigationStack { ProfileEditView(profile: nil) }
        }
    }
}

struct ProfileEditView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let profile: Profile?

    @State private var name = ""
    @State private var colorIndex = 0
    @State private var symbol = Profile.initialSymbol
    @State private var isKids = false
    @State private var pin = ""
    @State private var removePIN = false

    var body: some View {
        Form {
            Section {
                HStack {
                    Spacer()
                    ProfileAvatar(profile: Profile(name: name, colorIndex: colorIndex, symbol: symbol), size: 80)
                    Spacer()
                }
                TextField("Name", text: $name)
            }
            Section("Color") {
                HStack {
                    ForEach(ProfileStyle.colors.indices, id: \.self) { index in
                        Circle()
                            .fill(ProfileStyle.colors[index])
                            .frame(width: 28, height: 28)
                            .overlay { if index == colorIndex { Image(systemName: "checkmark").font(.caption.bold()).foregroundStyle(.white) } }
                            .onTapGesture { colorIndex = index }
                    }
                }
            }
            Section("Icon") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(ProfileStyle.choices, id: \.self) { candidate in
                            ProfileAvatar(profile: Profile(name: name, colorIndex: colorIndex, symbol: candidate), size: 32)
                                .padding(2)
                                .overlay { Circle().stroke(candidate == symbol ? OndoColor.selected : .clear, lineWidth: 2) }
                                .onTapGesture { symbol = candidate }
                                .accessibilityAddTraits(candidate == symbol ? [.isButton, .isSelected] : .isButton)
                                .accessibilityLabel(candidate == Profile.initialSymbol ? Text("Initial") : Text(verbatim: candidate))
                        }
                    }
                }
            }
            Section {
                Toggle("Kids profile", isOn: $isKids)
            } footer: {
                Text("Kids profiles never show locked or adult categories.")
            }
            Section {
                SecureField(profile?.isProtected == true ? "New PIN (leave empty to keep)" : "PIN (optional)", text: $pin)
                if profile?.isProtected == true {
                    Toggle("Remove PIN", isOn: $removePIN)
                }
            } header: {
                Text("Profile PIN")
            } footer: {
                Text("Asked when switching to this profile.")
            }
            if let profile, model.canDelete(profile) {
                Section {
                    Button("Delete Profile", role: .destructive) {
                        model.deleteProfile(profile)
                        dismiss()
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(profile == nil ? "New Profile" : "Edit Profile")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || (!pin.isEmpty && pin.count < 4))
            }
        }
        .onAppear {
            guard let profile else { return }
            name = profile.name
            colorIndex = profile.colorIndex
            symbol = profile.symbol
            isKids = profile.isKids
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 520)
        #endif
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if var existing = profile {
            existing.name = trimmed
            existing.colorIndex = colorIndex
            existing.symbol = symbol
            existing.isKids = isKids
            if removePIN {
                existing.pinHash = nil
            } else if !pin.isEmpty {
                existing.pinHash = Keychain.hash(pin: pin)
            }
            model.updateProfile(existing)
        } else {
            model.addProfile(name: trimmed, colorIndex: colorIndex, symbol: symbol, isKids: isKids, pin: pin.isEmpty ? nil : pin)
        }
        dismiss()
    }
}

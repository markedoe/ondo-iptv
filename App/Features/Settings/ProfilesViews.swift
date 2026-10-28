import SwiftUI
import IPTVCore

enum ProfileStyle {
    static let colors: [Color] = [.indigo, .pink, .orange, .teal, .green, .purple, .blue, .red]
    static let symbols = ["person.fill", "face.smiling", "star.fill", "heart.fill", "gamecontroller.fill", "film.fill", "pawprint.fill", "leaf.fill"]

    static func color(_ index: Int) -> Color { colors[abs(index) % colors.count] }
}

struct ProfileAvatar: View {
    let profile: Profile
    var size: CGFloat = 64

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22)
                .fill(ProfileStyle.color(profile.colorIndex).gradient)
            Image(systemName: profile.symbol)
                .font(.system(size: size * 0.45, weight: .semibold))
                .foregroundStyle(.white)
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

/// "Who's watching?" screen.
struct ProfilePickerView: View {
    @Environment(AppModel.self) private var model
    @State private var pinFor: Profile?
    @State private var pin = ""
    @State private var wrongPIN = false

    var body: some View {
        VStack(spacing: 36) {
            Text("Who's watching?")
                .font(.largeTitle.bold())
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120, maximum: 160), spacing: 24)], spacing: 24) {
                ForEach(model.profiles) { profile in
                    Button {
                        if profile.isProtected {
                            pin = ""
                            wrongPIN = false
                            pinFor = profile
                        } else {
                            model.switchProfile(to: profile)
                        }
                    } label: {
                        VStack(spacing: 10) {
                            ProfileAvatar(profile: profile, size: 96)
                            Text(profile.name).font(.headline)
                            if profile.isKids {
                                Text("Kids").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: 640)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .alert("Profile PIN", isPresented: Binding(get: { pinFor != nil }, set: { if !$0 { pinFor = nil } })) {
            SecureField("PIN", text: $pin)
            Button("Open") {
                guard let profile = pinFor else { return }
                pinFor = nil
                if model.verifyPIN(pin, for: profile) {
                    model.switchProfile(to: profile)
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

/// Profile switcher used in toolbars.
struct ProfileMenu: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.profiles.count > 1 {
            Button {
                model.flush()
                model.needsProfileSelection = true
            } label: {
                ProfileAvatar(profile: model.activeProfile, size: 26)
            }
            .buttonStyle(.plain)
            .help("Switch profile")
            .accessibilityLabel("Switch profile")
            .accessibilityValue(Text(verbatim: model.activeProfile.name))
        }
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
                guard model.profiles.count > 1 else { return }
                for offset in offsets { model.deleteProfile(model.profiles[offset]) }
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
    @State private var symbol = "person.fill"
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
                HStack {
                    ForEach(ProfileStyle.symbols, id: \.self) { candidate in
                        Image(systemName: candidate)
                            .frame(width: 30, height: 30)
                            .foregroundStyle(candidate == symbol ? OndoColor.selected : .secondary)
                            .onTapGesture { symbol = candidate }
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
            if let profile, model.profiles.count > 1 {
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

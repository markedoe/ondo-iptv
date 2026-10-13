import SwiftUI
import IPTVCore

/// Renames / renumbers a channel. Clearing a field restores the provider's value.
struct ChannelEditView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let channel: Channel

    @State private var name = ""
    @State private var number = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(model.originalName(of: channel), text: $name)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("channelNameField")
                } header: {
                    Text("Name")
                } footer: {
                    Text("Provider name: \(model.originalName(of: channel))")
                }
                Section {
                    TextField(model.originalNumber(of: channel).map(String.init) ?? String(localized: "None"), text: $number)
                        #if os(iOS)
                        .keyboardType(.numberPad)
                        #endif
                } header: {
                    Text("Number")
                } footer: {
                    Text("Used to show the channel and to switch to it by typing its number.")
                }
                Section {
                    Toggle("Hidden", isOn: Binding(
                        get: { model.isHidden(channel) },
                        set: { model.setHidden($0, channel: channel) }
                    ))
                } footer: {
                    Text("Hidden channels only appear in Settings > Hidden Channels, for this profile.")
                }
                if !model.channelEdit(for: channel).isEmpty {
                    Section {
                        Button("Restore Provider Name and Number", role: .destructive) {
                            Task {
                                await model.setEdit(ChannelEdit(), for: channel)
                                dismiss()
                            }
                        }
                    }
                }
            }
            .navigationTitle("Edit Channel")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        Task {
                            await model.setEdit(ChannelEdit(name: name, number: Int(number)), for: channel)
                            dismiss()
                        }
                    }
                    .disabled(!number.isEmpty && Int(number) == nil)
                }
            }
            .onAppear {
                let edit = model.channelEdit(for: channel)
                name = edit.name ?? ""
                number = (edit.number ?? model.originalNumber(of: channel)).map(String.init) ?? ""
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 420)
        #endif
    }
}

/// Channels hidden by the current profile, with a way to bring them back.
struct HiddenChannelsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let channels = model.hiddenChannels()
        List {
            ForEach(channels) { channel in
                HStack(spacing: 12) {
                    RemoteImage(url: model.logoURL(for: channel), placeholderText: channel.name, maxPixelSize: 120)
                        .frame(width: 44, height: 30)
                    Text(channel.name)
                    Spacer()
                    Button("Show") { model.setHidden(false, channel: channel) }
                        .buttonStyle(.bordered)
                }
            }
        }
        .overlay {
            if channels.isEmpty {
                ContentUnavailableView(
                    "No hidden channels",
                    systemImage: "eye",
                    description: Text("Long-press a channel and choose Hide Channel to remove it from lists, search and the guide.")
                )
            }
        }
        .navigationTitle("Hidden Channels")
    }
}

import SwiftUI
import IPTVCore
import UniformTypeIdentifiers

/// First launch: explains that the app is a player and offers to add a playlist.
struct WelcomeView: View {
    @Environment(AppModel.self) private var model
    @State private var showingAdd = false
    @State private var showingImport = false
    @State private var isLoadingDemo = false
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                OndoLogo(size: 104)
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 6)
                    .padding(.top, 40)

                VStack(spacing: 10) {
                    Text("Welcome to Ondo")
                        .font(.largeTitle.bold())
                        .multilineTextAlignment(.center)
                    Text("The movies, series and channels of your IPTV provider, in a clean library.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                VStack(alignment: .leading, spacing: 14) {
                    FeatureRow(icon: "film.stack", title: "Movies & series", text: "Posters, details, seasons, continue watching and next episode.")
                    FeatureRow(icon: "list.bullet.rectangle", title: "M3U & Xtream Codes", text: "Add the playlists given by your provider.")
                    FeatureRow(icon: "tv", title: "Live TV & guide", text: "Channels, TV guide and replay when your provider offers it.")
                    #if os(macOS)
                    FeatureRow(icon: "airplayvideo", title: "AirPlay", text: "Watch on your TV or projector, or in Picture in Picture.")
                    #else
                    FeatureRow(icon: "airplayvideo", title: "AirPlay & Chromecast", text: "Watch on your TV or projector, or in Picture in Picture.")
                    #endif
                }
                .frame(maxWidth: 480)

                VStack(spacing: 12) {
                    Button {
                        showingAdd = true
                    } label: {
                        Label("Add a Playlist", systemImage: "plus")
                            .frame(maxWidth: 320)
                    }
                    .buttonStyle(.ondoProminent)
                    .controlSize(.large)
                    .accessibilityIdentifier("addPlaylistButton")

                    Button {
                        Task { await addDemo() }
                    } label: {
                        if isLoadingDemo {
                            ProgressView().frame(maxWidth: 320)
                        } else {
                            Text("Try the demo").frame(maxWidth: 320)
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .disabled(isLoadingDemo)
                    .accessibilityIdentifier("demoButton")

                    Button("Restore a backup…") { showingImport = true }
                        .buttonStyle(.borderless)
                }

                if let errorMessage {
                    Text(errorMessage)
                        .font(.callout)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                }

                Legal.disclaimer
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 480)
                    .padding(.bottom, 30)
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity)
        }
        .sheet(isPresented: $showingAdd) {
            NavigationStack { AddPlaylistView() }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 560)
                #endif
        }
        .fileImporter(isPresented: $showingImport, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            Task {
                do {
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    try await model.importBackup(Data(contentsOf: url))
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func addDemo() async {
        isLoadingDemo = true
        defer { isLoadingDemo = false }
        do {
            try await model.addPlaylist(Playlist(name: String(localized: "Demo"), source: .remote(AppPaths.demoPlaylistURL)))
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct FeatureRow: View {
    let icon: String
    let title: LocalizedStringKey
    let text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(OndoColor.selected)
                .frame(width: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

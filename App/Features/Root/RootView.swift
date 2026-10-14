import SwiftUI
import IPTVCore

enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case home, movies, series, live, guide, favorites, downloads, search

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .home: return "Home"
        case .live: return "Live TV"
        case .guide: return "TV Guide"
        case .movies: return "Movies"
        case .series: return "Series"
        case .favorites: return "Favorites"
        case .downloads: return "Downloads"
        case .search: return "Search"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .live: return "tv"
        case .guide: return "calendar.day.timeline.left"
        case .movies: return "film"
        case .series: return "play.rectangle.on.rectangle"
        case .favorites: return "star"
        case .downloads: return "arrow.down.circle"
        case .search: return "magnifyingglass"
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if !model.isReady {
                ProgressView()
            } else if model.playlists.isEmpty {
                WelcomeView()
            } else if model.needsProfileSelection {
                ProfilePickerView()
            } else {
                MainView()
            }
        }
        .task { await model.bootstrap() }
    }
}

/// Shows the loading / error state of the active playlist above any section.
struct PlaylistStateGate<Content: View>: View {
    @Environment(AppModel.self) private var model
    @ViewBuilder var content: () -> Content

    var body: some View {
        switch model.phase {
        case .loading(let message) where model.content.isEmpty:
            VStack(spacing: 16) {
                ProgressView()
                Text(message).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message, let attribution) where model.content.isEmpty:
            ContentUnavailableView {
                Label(attribution.label, systemImage: attribution.systemImage)
            } description: {
                Text(message)
            } actions: {
                Button("Try Again") {
                    Task { await model.refreshActivePlaylist() }
                }
                .buttonStyle(.borderedProminent)
            }
        default:
            content()
        }
    }
}

struct MainView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        content
            .overlay(alignment: .top) { BannerView() }
            .onChange(of: ReminderNotificationRouter.shared.pendingTarget, initial: true) { _, target in
                guard let target else { return }
                ReminderNotificationRouter.shared.pendingTarget = nil
                Task { await self.model.openReminder(target) }
            }
            #if os(iOS)
            .fullScreenCover(item: $model.playbackRequest) { session in
                PlayerView(session: session) { model.playerDismissed(session) }
                    .environment(model)
                    .preferredColorScheme(.dark)
            }
            .fullScreenCover(isPresented: $model.isMultiviewPresented) {
                MultiviewView { self.model.closeMultiview() }
                    .environment(model)
                    .preferredColorScheme(.dark)
            }
            #else
            .modifier(PlayerWindowOpener())
            #endif
    }

    @ViewBuilder
    private var content: some View {
        #if os(macOS)
        SidebarRootView()
        #else
        if Platform.isPad {
            SidebarRootView()
        } else {
            TabRootView()
        }
        #endif
    }
}

#if os(macOS)
/// Opens a player window for every new playback request.
private struct PlayerWindowOpener: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    func body(content: Content) -> some View {
        content
            .onChange(of: model.playbackRequest?.id) { _, id in
                guard let id else { return }
                openWindow(id: "player", value: id)
                model.playbackRequest = nil
            }
            .onChange(of: model.isMultiviewPresented) { _, requested in
                guard requested else { return }
                openWindow(id: "multiview")
                model.isMultiviewPresented = false
            }
    }
}

/// Content of the macOS multiview window; closing the window stops its players.
struct MultiviewWindow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        MultiviewView { dismiss() }
            .frame(minWidth: 640, minHeight: 360)
            .onDisappear { model.closeMultiview() }
    }
}
#endif

/// iPhone: five tabs, movies and series first (Guide is in Live TV, Settings on Home).
struct TabRootView: View {
    @State private var selection: AppSection = .home

    var body: some View {
        TabView(selection: $selection) {
            ForEach([AppSection.home, .movies, .series, .live, .search]) { section in
                RoutedNavigationStack {
                    SectionView(section: section)
                }
                .tabItem { Label(section.title, systemImage: section.systemImage) }
                .tag(section)
            }
        }
    }
}

/// Mac and iPad: sidebar with sections and playlists.
struct SidebarRootView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: AppSection? = Self.initialSection
    @State private var showingSettings = false

    private static var initialSection: AppSection {
        #if DEBUG
        ScreenshotRoute.section ?? .home
        #else
        .home
        #endif
    }

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section {
                    ForEach(AppSection.allCases) { section in
                        Label(section.title, systemImage: section.systemImage)
                            .tag(section)
                    }
                }
                Section("Playlists") {
                    ForEach(model.playlists) { playlist in
                        Button {
                            Task { await model.activate(playlist) }
                        } label: {
                            HStack {
                                Label(playlist.name, systemImage: playlist.id == model.activePlaylist?.id ? "checkmark.circle.fill" : "list.bullet.rectangle")
                                Spacer()
                                if playlist.id == model.activePlaylist?.id && model.isRefreshing {
                                    ProgressView().controlSize(.small)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Ondo")
            #if os(macOS)
            .navigationSplitViewColumnWidth(min: 190, ideal: 220)
            #else
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showingSettings = true } label: { Label("Settings", systemImage: "gearshape") }
                }
            }
            .sheet(isPresented: $showingSettings) {
                NavigationStack { SettingsView() }
            }
            #endif
        } detail: {
            RoutedNavigationStack {
                SectionView(section: selection ?? .home)
                    #if DEBUG
                    .modifier(ScreenshotRouteOpener())
                    #endif
            }
            .id(selection)
        }
    }
}

struct SectionView: View {
    let section: AppSection

    var body: some View {
        PlaylistStateGate {
            Self.content(for: section)
        }
        .navigationDestination(for: AppSection.self) { section in
            Self.content(for: section)
        }
        .navigationDestination(for: CustomListRoute.self) { route in
            CustomListDetailView(listID: route.id)
        }
        .navigationDestination(for: FavoritesRoute.self) { route in
            FavoritesView(initialType: route.type)
        }
        .navigationDestination(for: Movie.self) { movie in
            MovieDetailView(movie: movie)
        }
        .navigationDestination(for: Series.self) { series in
            SeriesDetailView(series: series)
        }
        .navigationDestination(for: Channel.self) { channel in
            ChannelGuideView(channel: channel)
        }
    }
}

extension SectionView {
    @ViewBuilder
    static func content(for section: AppSection) -> some View {
        switch section {
        case .home: HomeView()
        case .live: LiveView()
        case .guide: GuideView()
        case .movies: MoviesView()
        case .series: SeriesListView()
        case .favorites: FavoritesView()
        case .downloads: DownloadsView()
        case .search: SearchView()
        }
    }
}

struct BannerView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let message = model.bannerMessage {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
                Text(message).font(.callout)
                Spacer(minLength: 0)
                Button {
                    model.bannerMessage = nil
                } label: {
                    Image(systemName: "xmark").font(.callout.weight(.semibold))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }
            .padding(12)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
            .padding(.top, 8)
            .transition(.move(edge: .top).combined(with: .opacity))
            .task(id: message) {
                AccessibilityNotification.Announcement(message).post()  // VoiceOver reads it, it fades out
                try? await Task.sleep(for: .seconds(6))
                model.bannerMessage = nil
            }
        }
    }
}

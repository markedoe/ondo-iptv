import SwiftUI
import IPTVCore

/// Home: a carousel of featured movies and series, then shelves (continue watching, My List,
/// new movies, new series), with live TV last.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @State private var showingSettings = false
    @State private var showingAddPlaylist = false
    @State private var heroUnderBar = true

    private var compact: Bool {
        #if os(iOS)
        sizeClass == .compact
        #else
        false
        #endif
    }

    var body: some View {
        let hero = model.heroItems()
        // The reader extends under the bars so it reports their height; the carousel is drawn beneath them.
        GeometryReader { proxy in
            ScrollView {
                content(hero: hero, width: proxy.size.width, topInset: hero.isEmpty ? 0 : topInset(proxy))
            }
            #if os(iOS)
            .ignoresSafeArea(edges: hero.isEmpty ? [] : .top)
            #endif
        }
        #if os(iOS)
        .ignoresSafeArea(edges: hero.isEmpty ? [] : .top)
        #endif
        .background(Color.appBackground)
        .refreshable { await model.refreshActivePlaylist() }
        .task(id: "\(model.content.fetchedAt.timeIntervalSince1970)-\(model.hasTMDbKey)") {
            await model.loadTrending()
        }
        .task(id: prefetchKey(hero)) {
            await prefetchDetails(hero)
        }
        .navigationTitle("Home")
        .inlineNavigationTitle()
        #if os(iOS)
        .toolbarColorScheme(!hero.isEmpty && heroUnderBar ? .dark : nil, for: .navigationBar)
        #endif
        .toolbar { toolbar }
        .sheet(isPresented: $showingSettings) {
            NavigationStack { SettingsView() }
        }
        .sheet(isPresented: $showingAddPlaylist) {
            NavigationStack { AddPlaylistView() }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 560)
                #endif
        }
    }

    /// Height of the bars above the carousel. Under the iOS 26 navigation bar the reader only reports the
    /// status bar, so the bar itself is added on iPhone (on iPad the carousel text sits at the bottom).
    private func topInset(_ proxy: GeometryProxy) -> CGFloat {
        #if os(iOS)
        let inset = proxy.safeAreaInsets.top
        return compact && inset < 80 ? inset + 44 : inset
        #else
        0
        #endif
    }

    // MARK: - Content

    @ViewBuilder
    private func content(hero: [ShowcaseItem], width: CGFloat, topInset: CGFloat) -> some View {
        let metrics = ShelfMetrics(compact: compact)
        let resume = model.continueWatching()
        let myList = model.myList()
        let newMovies = model.newMovies()
        let newSeries = model.newSeries()
        let hasVOD = !model.content.movies.isEmpty || !model.content.series.isEmpty

        VStack(alignment: .leading, spacing: compact ? 26 : 34) {
            if !hero.isEmpty {
                HeroCarousel(items: hero, compact: compact, topInset: topInset, width: width)
                    .onGeometryChange(for: Bool.self) { geometry in
                        geometry.frame(in: .global).maxY > topInset + 24
                    } action: { visible in
                        heroUnderBar = visible
                    }
            }

            accountNotice
                .padding(.horizontal, metrics.gutter)

            if !resume.isEmpty {
                Shelf(title: "Continue Watching", metrics: metrics) {
                    ForEach(resume) { entry in
                        ContinueCard(entry: entry, width: metrics.wideCardWidth)
                    }
                }
            }

            if !myList.isEmpty {
                Shelf(title: "My List", route: .favorites(FavoritesRoute(type: myList[0].contentType)), metrics: metrics) {
                    ForEach(myList) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }

            if !hasVOD { liveShelves(metrics: metrics, emphasised: true) }

            if !newMovies.isEmpty {
                Shelf(title: "New Movies", metrics: metrics) {
                    ForEach(newMovies) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }

            if !newSeries.isEmpty {
                Shelf(title: "New Series", metrics: metrics) {
                    ForEach(newSeries) { item in
                        ShowcasePoster(item: item, width: metrics.posterWidth)
                    }
                }
            }

            if !model.trending.isEmpty {
                Shelf(title: "Trending", metrics: metrics) {
                    ForEach(model.trending) { match in
                        switch match {
                        case .movie(let movie, _): ShowcasePoster(item: .movie(movie), width: metrics.posterWidth)
                        case .series(let series, _): ShowcasePoster(item: .series(series), width: metrics.posterWidth)
                        }
                    }
                }
            }

            customLists(metrics: metrics)

            if hasVOD { liveShelves(metrics: metrics, emphasised: false) }

            reminders
                .padding(.horizontal, metrics.gutter)

            if compact { libraryLinks.padding(.horizontal, metrics.gutter) }
        }
        .padding(.top, hero.isEmpty ? 12 : 0)
        .padding(.bottom, 32)
    }

    /// Live TV comes last on a VOD playlist; on a live-only playlist it leads, with a way into the channels.
    @ViewBuilder
    private func liveShelves(metrics: ShelfMetrics, emphasised: Bool) -> some View {
        let favorites = model.favoriteChannels()
        let recent = model.recentChannels()
        if !favorites.isEmpty {
            channelShelf(title: "Channels in My List", channels: favorites, metrics: metrics)
        }
        if !recent.isEmpty && (emphasised || favorites.isEmpty) {
            channelShelf(title: "Recently Watched", channels: recent, metrics: metrics)
        }
        if emphasised && favorites.isEmpty && recent.isEmpty && !model.content.channels.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("Your channels").font(.title3.bold())
                Text("Long-press a channel and choose “Add to My List”; it will appear here.")
                    .foregroundStyle(.secondary)
                HStack(spacing: 12) {
                    NavigationLink(value: AppSection.live) {
                        Label("Live TV", systemImage: AppSection.live.systemImage)
                    }
                    .buttonStyle(.borderedProminent)
                    NavigationLink(value: AppSection.guide) {
                        Label("TV Guide", systemImage: AppSection.guide.systemImage)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(.horizontal, metrics.gutter)
        }
    }

    private func channelShelf(title: LocalizedStringKey, channels: [Channel], metrics: ShelfMetrics) -> some View {
        Shelf(title: title, seeAll: .live, metrics: metrics) {
            ForEach(channels) { channel in
                Button { model.play(channel, in: channels) } label: { ChannelTile(channel: channel) }
                    .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func customLists(metrics: ShelfMetrics) -> some View {
        let lists = model.library.customLists.filter { !$0.items.isEmpty }
        if !lists.isEmpty {
            Shelf(title: "Collections", route: .favorites(FavoritesRoute(type: nil)), metrics: metrics) {
                ForEach(lists) { list in
                    NavigationLink(value: CustomListRoute(id: list.id)) {
                        VStack(alignment: .leading, spacing: 4) {
                            Image(systemName: "rectangle.stack")
                                .font(.title2)
                                .foregroundStyle(Color.accentColor)
                            Spacer(minLength: 8)
                            Text(list.name).font(.headline).lineLimit(2)
                            Text("\(list.items.count) items").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(14)
                        .frame(width: 150, height: 120, alignment: .leading)
                        .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private var reminders: some View {
        let reminders = Array(model.upcomingReminders.prefix(5))
        if !reminders.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Reminders").font(.title3.bold())
                VStack(spacing: 0) {
                    ForEach(reminders) { reminder in
                        ReminderRow(reminder: reminder)
                            .padding(.vertical, 8)
                        if reminder.id != reminders.last?.id { Divider() }
                    }
                }
                .padding(.horizontal, 12)
                .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }

    /// iPhone has no sidebar: downloads stay one tap away (My List has its own tab).
    private var libraryLinks: some View {
        VStack(spacing: 0) {
            libraryLink(.downloads, title: "Downloads")
        }
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func libraryLink(_ section: AppSection, title: LocalizedStringKey) -> some View {
        NavigationLink(value: section) {
            HStack(spacing: 12) {
                Image(systemName: section.systemImage)
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 20)
                Text(title)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Shown only when the provider account is expired, inactive or about to expire.
    @ViewBuilder
    private var accountNotice: some View {
        if let account = model.content.account,
           !account.isActive || (account.expiresAt.map { $0.timeIntervalSinceNow < 7 * 86_400 } ?? false) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text(account.isActive ? String(localized: "Subscription expires soon") : String(localized: "Subscription inactive"))
                        .font(.subheadline.weight(.semibold))
                    if let expiry = account.expiresAt {
                        Text("Expires \(expiry.formatted(date: .abbreviated, time: .omitted))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .padding(12)
            .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    // MARK: - Details prefetch

    private func prefetchKey(_ hero: [ShowcaseItem]) -> String {
        hero.map(\.id).joined(separator: ",") + "|" + model.continueWatching().prefix(6).map(\.id).joined(separator: ",")
    }

    /// Loads plots, genres and backdrops for the carousel and the continue watching cards.
    private func prefetchDetails(_ hero: [ShowcaseItem]) async {
        let resume = model.continueWatching().prefix(6).compactMap { model.showcaseItem(for: $0) }
        for item in hero + resume where model.cachedDetails(for: item) == nil {
            guard !Task.isCancelled else { return }
            _ = await model.loadDetails(for: item)
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        #if os(iOS)
        if compact {
            ToolbarItem(placement: .principal) { playlistMenu(compactLabel: true) }
        } else {
            ToolbarItem(placement: .primaryAction) { playlistMenu(compactLabel: false) }
        }
        #else
        ToolbarItem(placement: .primaryAction) { playlistMenu(compactLabel: false) }
        #endif
        ToolbarItem(placement: .primaryAction) { ProfileMenu() }
        #if os(iOS)
        if !Platform.isPad {
            ToolbarItem(placement: .primaryAction) {
                Button { showingSettings = true } label: { Label("Settings", systemImage: "gearshape") }
            }
        }
        #endif
    }

    private func playlistMenu(compactLabel: Bool) -> some View {
        Menu {
            ForEach(model.playlists) { playlist in
                Button {
                    Task { await model.activate(playlist) }
                } label: {
                    if playlist.id == model.activePlaylist?.id {
                        Label(playlist.name, systemImage: "checkmark")
                    } else {
                        Text(playlist.name)
                    }
                }
            }
            Divider()
            Button {
                showingAddPlaylist = true
            } label: {
                Label("Add Playlist…", systemImage: "plus")
            }
            Button {
                Task { await model.refreshActivePlaylist() }
            } label: {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
        } label: {
            if compactLabel {
                HStack(spacing: 5) {
                    if model.isRefreshing { ProgressView().controlSize(.small) }
                    Text(model.activePlaylist?.name ?? String(localized: "Playlists"))
                        .font(.headline)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                }
                .foregroundStyle(.primary)
            } else {
                HStack(spacing: 4) {
                    if model.isRefreshing { ProgressView().controlSize(.small) }
                    Label(model.activePlaylist?.name ?? String(localized: "Playlists"), systemImage: "list.bullet.rectangle")
                }
            }
        }
        .accessibilityIdentifier("playlistMenu")
    }
}

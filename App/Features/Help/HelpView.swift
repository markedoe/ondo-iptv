import SwiftUI

/// One page of the help: what a feature does and where it is.
struct HelpTopic: Identifiable {
    let id: String
    let title: LocalizedStringResource
    let systemImage: String
    let body: LocalizedStringResource
}

extension HelpTopic {
    /// The topics of the features this edition has (no downloads in the App Store edition).
    static var shown: [HelpTopic] {
        all.filter { $0.id != "downloads" || FeatureFlags.downloads }
    }

    static let all: [HelpTopic] = [
        HelpTopic(
            id: "playlists", title: "Add a Playlist", systemImage: "list.bullet.rectangle",
            body: """
            Ondo is a player: it shows the movies, series and channels of the subscription your provider gives \
            you, and provides no content of its own.

            To add your subscription, open Settings, Manage Playlists, and add an M3U link or file, or an Xtream \
            Codes login (server address, user name and password). You can add several playlists and switch between \
            them from the menu at the top of Home, or from the sidebar on iPad and Mac.

            If the provider's TV guide is missing, add an XMLTV address to the playlist (Additional EPG URLs).
            """
        ),
        HelpTopic(
            id: "search", title: "Find Something to Watch", systemImage: "magnifyingglass",
            body: """
            Movies, Series and Live TV show your provider's categories. Search looks through channels, programmes, \
            movies and series: on iPhone it is the magnifying glass at the top of each tab, on iPad and Mac it is \
            in the sidebar.

            In Search, the filter button keeps the movies and series released in the years you choose. The grids \
            sort by playlist order, name, date added, release date or rating.

            On a movie or series page, tap the name of a director or an actor to see their other movies and series \
            in your playlist. With your TMDb API Read Access Token (Settings), all of them are found; without it, \
            movies only among the pages you have already opened. Genres, the year and awards open the titles of your \
            playlist that share them the same way. Search finds actors and directors by name too.
            """
        ),
        HelpTopic(
            id: "categories", title: "Hide Languages and Categories", systemImage: "line.3.horizontal.decrease.circle",
            body: """
            Many providers start category names with a code such as |PT| or FR:. The filter button on Movies, \
            Series and Live TV (also in Settings, Languages and Categories) hides a whole language or country at \
            once, in every playlist; the categories the provider adds later follow the same rule.

            Styles work the same way with the words of category names: hide Manga & Anime, Children or \
            Documentaries, or a word of your own such as Bollywood. Only category names count, not the genre of \
            each movie.

            You can also hide categories one by one there, or from a category's menu (long-press, or Control-click \
            on a Mac). The number of hidden categories is shown at the end of the list, so you can bring them back \
            at any time.
            """
        ),
        HelpTopic(
            id: "my-list", title: "My List and Collections", systemImage: "list.star",
            body: """
            Add to My List keeps a movie, a series or a channel at hand. On iPhone, My List is a tab with Movies, \
            Series, Channels and Collections; on iPad and Mac, it is in the sidebar.

            Collections are your own groups, such as Weekend or Kids: use Collection on a movie or series page, or \
            Add to Collection in a menu.

            Each profile has its own My List, collections and history.
            """
        ),
        HelpTopic(
            id: "guide", title: "TV Guide, Replay and Reminders", systemImage: "calendar.day.timeline.left",
            body: """
            The guide comes from your provider or from the XMLTV addresses added to the playlist. If a channel \
            shows no programmes, open its menu and choose Match TV Guide.

            When your provider offers replay, you can restart the programme on now or watch a past one, from the \
            guide or from the player.

            Open an upcoming programme and set a reminder: a notification arrives a few minutes before it starts.
            """
        ),
        HelpTopic(
            id: "playback", title: "When a Stream Does Not Play", systemImage: "exclamationmark.triangle",
            body: """
            Ondo says whether the provider, the network or the format is at fault.

            If the provider refuses the stream, the subscription may have expired or too many devices may be \
            watching at once: most providers allow one or two streams at a time.

            If the server cannot be reached, check your connection and try again.

            If the format is not supported, choose the other engine in Settings, Player Engine: the VLC-based \
            engine plays MKV, AVI and TS. Reconnect dropped streams (Settings) restarts a live channel that cuts \
            out.

            If the picture freezes now and then, More, Stream Statistics shows what arrives, what the video needs \
            and whether the freezes come from the network or the provider, or from the player. With the VLC-based \
            engine, a live channel keeps 3 seconds in reserve and a film 1 second, so that they start and seek fast; \
            when the provider stops sending for longer, the next ones keep 6 and 5. Settings, Buffer for live TV and \
            Buffer for movies & series, set a fixed length instead.

            More, Report a Problem… shows a report of the playback (start and seek times, freezes, statistics; no \
            address, login or title) to send by mail or message, or in a GitHub issue.
            """
        ),
        HelpTopic(
            id: "downloads", title: "Downloads", systemImage: "arrow.down.circle",
            body: """
            Download a movie or an episode from its page to watch it offline, when your provider allows it. Your \
            downloads are in Downloads.

            If a download stays stuck, Ondo tells you why: refused by the provider, connection limit reached, or \
            nothing received. Many providers count a download as one of your connections.
            """
        ),
        HelpTopic(
            id: "subtitles", title: "Subtitles and Audio", systemImage: "captions.bubble",
            body: """
            Choose the audio and subtitle tracks from the player. You can also open an SRT or VTT file, or search \
            subtitles online: Addic7ed works without an account for series, and OpenSubtitles, SubDL and SubSource \
            work with your own key (Settings, Online Subtitles).
            """
        ),
        HelpTopic(
            id: "tv", title: "On Your TV", systemImage: "airplayvideo",
            body: """
            AirPlay sends the video to an Apple TV or an AirPlay TV, from iPhone, iPad and Mac. Chromecast works \
            from iPhone and iPad.

            On iPad and Mac, Multiview plays up to four channels side by side, with the sound of one.
            """
        ),
        HelpTopic(
            id: "profiles", title: "Profiles and Parental Control", systemImage: "person.2",
            body: """
            Profiles (Settings, Profiles) keep their own My List, collections and history, and can have a PIN. A \
            kids profile never sees adult or locked categories.

            Parental control (Settings) locks the categories you choose behind a PIN; categories whose name looks \
            adult are locked automatically. Locked categories stay out of Home, search and My List.
            """
        ),
        HelpTopic(
            id: "privacy", title: "Privacy", systemImage: "hand.raised",
            body: """
            Ondo has no account, no ads and no analytics of its own. Your playlists, history and settings stay on \
            your device; passwords and keys are kept in the system keychain.

            Ondo connects only to the servers you add and, if you enter your TMDb token, to TMDb and to the subtitle \
            services you choose. Backups contain your logins: keep them private.
            """
        ),
    ]
}

/// Help: one page per feature, and the support website.
struct HelpView: View {
    static let supportURL = URL(string: "https://markedoe.github.io/ondo-iptv/support.html")!

    var body: some View {
        List {
            Section {
                ForEach(HelpTopic.shown) { topic in
                    NavigationLink {
                        HelpTopicView(topic: topic)
                    } label: {
                        Label { Text(topic.title) } icon: { Image(systemName: topic.systemImage) }
                    }
                    .accessibilityIdentifier("help-" + topic.id)
                }
            } footer: {
                Text("Ondo is a player: it provides no content.")
            }
            Section {
                Link(destination: Self.supportURL) {
                    Label("Support Website", systemImage: "safari")
                }
            }
        }
        .navigationTitle("Help")
    }
}

struct HelpTopicView: View {
    let topic: HelpTopic

    var body: some View {
        ScrollView {
            Text(topic.body)
                .frame(maxWidth: 640, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .textSelection(.enabled)
        }
        .navigationTitle(Text(topic.title))
    }
}

#if os(macOS)
/// Help menu: "Ondo Help" opens the help window.
struct HelpCommands: Commands {
    var body: some Commands {
        CommandGroup(replacing: .help) {
            HelpMenuButton()
        }
    }
}

private struct HelpMenuButton: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Ondo Help") { openWindow(id: "help") }
            .keyboardShortcut("?", modifiers: .command)
    }
}
#endif

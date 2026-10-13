import SwiftUI
import IPTVCore

/// Movie and series pages: full-bleed artwork under the navigation bar, the poster and title over its
/// faded bottom edge, then the actions and the page content.
struct DetailScaffold<Actions: View, Content: View>: View {
    let title: String
    let posterURL: URL?
    let backdropURL: URL?
    let facts: [String]
    @ViewBuilder var actions: () -> Actions
    @ViewBuilder var content: (ShelfMetrics) -> Content
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @State private var headerUnderBar = true

    private var compact: Bool {
        #if os(iOS)
        sizeClass == .compact
        #else
        false
        #endif
    }

    var body: some View {
        let metrics = ShelfMetrics(compact: compact)
        GeometryReader { proxy in
            let topInset = topInset(proxy)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    header(width: proxy.size.width, topInset: topInset, metrics: metrics)
                    actions()
                        .padding(.horizontal, metrics.gutter)
                    content(metrics)
                }
                .padding(.bottom, 32)
            }
            #if os(iOS)
            .ignoresSafeArea(edges: .top)
            #endif
        }
        #if os(iOS)
        .ignoresSafeArea(edges: .top)
        .toolbarColorScheme(headerUnderBar ? .dark : nil, for: .navigationBar)
        .toolbar {
            // The title appears in the bar once the header has scrolled away.
            ToolbarItem(placement: .principal) {
                Text(title).font(.headline).lineLimit(1).opacity(headerUnderBar ? 0 : 1)
            }
        }
        #endif
        .background(Color.appBackground)
        .navigationTitle(title)
        .inlineNavigationTitle()
    }

    private func topInset(_ proxy: GeometryProxy) -> CGFloat {
        #if os(iOS)
        proxy.safeAreaInsets.top
        #else
        0
        #endif
    }

    // MARK: Header

    private func header(width: CGFloat, topInset: CGFloat, metrics: ShelfMetrics) -> some View {
        let artworkHeight = topInset + (compact ? 250 : min(max(width * 0.36, 300), 440))
        let posterWidth: CGFloat = compact ? 112 : 168
        let overlap: CGFloat = compact ? 92 : 150
        return VStack(alignment: .leading, spacing: 0) {
            artwork
                .frame(width: width, height: artworkHeight)
                .clipped()
                .onGeometryChange(for: Bool.self) { geometry in
                    geometry.frame(in: .global).maxY > topInset + overlap
                } action: { visible in
                    headerUnderBar = visible
                }
            HStack(alignment: .bottom, spacing: compact ? 14 : 24) {
                PosterArtwork(url: posterURL, title: title)
                    .frame(width: posterWidth)
                    .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title)
                        .font(compact ? .title2.bold() : .system(.largeTitle, weight: .bold))
                        .lineLimit(3)
                        .minimumScaleFactor(0.8)
                    if !facts.isEmpty {
                        Text(facts.joined(separator: " · "))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                .padding(.bottom, 4)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, metrics.gutter)
            .padding(.top, -overlap)
        }
    }

    private var artwork: some View {
        Color.black
            .overlay {
                if let backdropURL {
                    RemoteImage(url: backdropURL, placeholderText: "", contentMode: .fill, maxPixelSize: 1600)
                } else {
                    RemoteImage(url: posterURL, placeholderText: "", contentMode: .fill, maxPixelSize: 200)
                        .blur(radius: 40, opaque: true)
                        .scaleEffect(1.2)
                        .overlay { Color.black.opacity(0.25) }
                }
            }
            .overlay {
                LinearGradient(
                    stops: [.init(color: .black.opacity(0.55), location: 0), .init(color: .clear, location: 0.3),
                            .init(color: .clear, location: 0.45), .init(color: Color.appBackground, location: 1)],
                    startPoint: .top, endPoint: .bottom
                )
            }
            .accessibilityHidden(true)
    }
}

/// Main action of a detail page: Play / Resume, full width on iPhone.
struct PrimaryPlayButton: View {
    let title: LocalizedStringKey
    var subtitle: String?
    var progress: Double?
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: action) {
                Label(title, systemImage: "play.fill")
                    .font(.headline)
                    .frame(maxWidth: 420)
                    .padding(.vertical, 14)
                    .padding(.horizontal, 24)
                    .foregroundStyle(.white)
                    .background(Color.accentColor, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("detailPlay")
            if let progress, progress > 0 {
                HStack(spacing: 10) {
                    ProgressBar(value: progress).frame(maxWidth: 200)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

/// Round secondary action with its caption ("My List", "Trailer", "Download"…).
struct CircleAction<Icon: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder var icon: () -> Icon

    var body: some View {
        VStack(spacing: 6) {
            icon()
                .font(.title3)
                .frame(width: 50, height: 50)
                .background(Color.secondary.opacity(0.16), in: Circle())
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(minWidth: 64)
    }
}

extension CircleAction where Icon == Image {
    init(_ title: LocalizedStringKey, systemImage: String) {
        self.init(title: title) { Image(systemName: systemImage) }
    }
}

/// "My List" toggle for a movie or series.
struct MyListAction: View {
    @Environment(AppModel.self) private var model
    let item: ShowcaseItem

    var body: some View {
        let inList: Bool = {
            switch item {
            case .movie(let movie): return model.isFavorite(.movie, id: movie.id)
            case .series(let series): return model.isFavorite(.series, id: series.id)
            }
        }()
        Button {
            switch item {
            case .movie(let movie): model.toggleFavorite(movie)
            case .series(let series): model.toggleFavorite(series)
            }
        } label: {
            CircleAction("My List", systemImage: inList ? "checkmark" : "plus")
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(.plain)
        .accessibilityValue(inList ? Text("In My List") : Text(""))
    }
}

/// Download control in the round style; nothing when the stream cannot be downloaded.
struct DownloadAction: View {
    let item: PlaybackItem?

    var body: some View {
        if let item, DownloadManager.canDownload(item) {
            CircleAction(title: "Download") {
                DownloadButton(item: item, compact: true)
                    .buttonStyle(.plain)
            }
        }
    }
}

/// Trailer on YouTube, when the provider gives one.
struct TrailerAction: View {
    let youTubeID: String?

    var body: some View {
        if let youTubeID, let url = URL(string: "https://www.youtube.com/watch?v=\(youTubeID)") {
            Link(destination: url) {
                CircleAction("Trailer", systemImage: "play.rectangle")
            }
            .buttonStyle(.plain)
        }
    }
}

/// Plot limited to a few lines, with More / Less when it is long.
struct ExpandablePlot: View {
    let text: String
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text)
                .font(.callout)
                .lineLimit(expanded ? nil : 4)
                .frame(maxWidth: 720, alignment: .leading)
            if text.count > 220 {
                Button(expanded ? String(localized: "Less") : String(localized: "More")) {
                    withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
                }
                .font(.callout.weight(.semibold))
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }
        }
    }
}

import SwiftUI
import IPTVCore

/// Sizes of posters and cards, tuned per device class.
struct ShelfMetrics {
    let compact: Bool

    var posterWidth: CGFloat { compact ? 116 : 150 }
    var wideCardWidth: CGFloat { compact ? 264 : 320 }
    var gutter: CGFloat { compact ? 16 : 24 }
}

/// A titled horizontal row ("shelf") with an optional "See All" link.
struct Shelf<Content: View>: View {
    let title: LocalizedStringKey
    var seeAll: AppSection?
    let metrics: ShelfMetrics
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.title3.bold())
                Spacer()
                if let seeAll {
                    NavigationLink(value: seeAll) {
                        HStack(spacing: 3) {
                            Text("See All")
                            Image(systemName: "chevron.right").font(.caption.weight(.bold))
                        }
                        .font(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                }
            }
            .padding(.horizontal, metrics.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    content()
                }
                .padding(.horizontal, metrics.gutter)
            }
            .scrollClipDisabled()
        }
    }
}

/// Poster of a movie or series opening its detail page.
struct ShowcasePoster: View {
    @Environment(AppModel.self) private var model
    let item: ShowcaseItem
    let width: CGFloat

    var body: some View {
        switch item {
        case .movie(let movie):
            NavigationLink(value: movie) { card }
                .buttonStyle(.plain)
                .contextMenu { MovieContextMenu(movie: movie) }
        case .series(let series):
            NavigationLink(value: series) { card }
                .buttonStyle(.plain)
                .contextMenu { SeriesContextMenu(series: series) }
        }
    }

    private var card: some View {
        let progress: Double? = {
            if case .movie(let movie) = item { return model.progress(movie: movie)?.progress }
            return nil
        }()
        return PosterArtwork(url: item.posterURL, title: item.title, progress: progress)
            .frame(width: width)
            .accessibilityLabel(Text(item.title))
    }
}

/// 2:3 artwork with soft corners, a hairline border and an optional progress bar.
struct PosterArtwork: View {
    let url: URL?
    let title: String
    var progress: Double?

    var body: some View {
        Color.secondary.opacity(0.15)
            .aspectRatio(2 / 3, contentMode: .fit)
            .overlay {
                RemoteImage(url: url, placeholderText: title, contentMode: .fill, maxPixelSize: 480)
            }
            .overlay(alignment: .bottom) {
                if let progress, progress > 0 {
                    ProgressBar(value: progress).padding(8)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(.white.opacity(0.08), lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Thin rounded progress bar drawn over artwork.
struct ProgressBar: View {
    let value: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.3))
                Capsule().fill(Color.accentColor).frame(width: proxy.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 4)
    }
}

/// 16:9 "Continue watching" card: artwork, title, what is left and progress.
struct ContinueCard: View {
    @Environment(AppModel.self) private var model
    let entry: HistoryEntry
    let width: CGFloat

    var body: some View {
        let item = model.showcaseItem(for: entry)
        let artwork = item.flatMap { model.backdropURL(for: $0) } ?? entry.imageURL
        Button {
            model.resume(entry)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                Color.secondary.opacity(0.15)
                    .frame(width: width, height: width * 9 / 16)
                    .overlay {
                        RemoteImage(url: artwork, placeholderText: entry.title, contentMode: .fill, maxPixelSize: 720)
                    }
                    .overlay {
                        LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                    }
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 6) {
                                Image(systemName: "play.fill").font(.caption)
                                Text(remaining).font(.caption.weight(.semibold))
                            }
                            .foregroundStyle(.white)
                            ProgressBar(value: entry.progress)
                        }
                        .padding(10)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
                Text(entry.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                if let subtitle = entry.subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(width: width)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) {
                model.removeFromHistory(entry)
            } label: {
                Label("Remove from History", systemImage: "trash")
            }
        }
    }

    private var remaining: String {
        let seconds = max(entry.duration - entry.position, 0)
        guard seconds > 60 else { return String(localized: "Resume") }
        return String(localized: "\(Int(seconds / 60)) min left")
    }
}

struct MovieContextMenu: View {
    @Environment(AppModel.self) private var model
    let movie: Movie

    var body: some View {
        Button {
            model.play(movie)
        } label: {
            Label("Play", systemImage: "play.fill")
        }
        Button {
            model.toggleFavorite(movie)
        } label: {
            MyListLabel(inList: model.isFavorite(.movie, id: movie.id))
        }
        AddToListMenu(item: .movie(movie))
    }
}

struct SeriesContextMenu: View {
    @Environment(AppModel.self) private var model
    let series: Series

    var body: some View {
        Button {
            Task { await model.playShowcase(.series(series)) }
        } label: {
            Label("Play", systemImage: "play.fill")
        }
        Button {
            model.toggleFavorite(series)
        } label: {
            MyListLabel(inList: model.isFavorite(.series, id: series.id))
        }
        AddToListMenu(item: .series(series))
    }
}

/// "Add to My List" / "Remove from My List".
struct MyListLabel: View {
    let inList: Bool

    var body: some View {
        if inList {
            Label("Remove from My List", systemImage: "checkmark")
        } else {
            Label("Add to My List", systemImage: "plus")
        }
    }
}

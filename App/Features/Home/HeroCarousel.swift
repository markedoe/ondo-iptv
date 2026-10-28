import SwiftUI
import IPTVCore

/// Carousel of featured movies and series at the top of the home screen: the title's picture in a large rounded
/// frame inset from the edges of the page, its title, facts and buttons inside the frame at the bottom left, over a
/// dark shade, and the page dots at the bottom right.
struct HeroCarousel: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let items: [ShowcaseItem]
    let compact: Bool
    /// Width of the frame: the page's width less its margins.
    let width: CGFloat
    @State private var current: String?

    #if DEBUG
    /// Auto-advance is off in UI tests and screenshots (Debug builds) so captures are deterministic.
    private static let autoAdvances = !ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-uitest") }
    #else
    private static let autoAdvances = true
    #endif

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: compact ? 16 : 20, style: .continuous)
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 0) {
                ForEach(items) { item in
                    HeroPage(item: item, compact: compact, isCurrent: item.id == (current ?? items.first?.id))
                        .frame(width: width, height: height)
                        .clipped()
                        .id(item.id)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $current)
        .frame(width: width, height: height)
        .clipShape(shape)
        .overlay { shape.strokeBorder(.white.opacity(0.08), lineWidth: 1) }
        .overlay(alignment: .bottomTrailing) {
            if items.count > 1 {
                // In line with the buttons on a wide frame; below them on iPhone, where the row has no room for both.
                pageDots
                    .padding(.trailing, compact ? 16 : 26)
                    .padding(.bottom, compact ? 12 : 47)
            }
        }
        #if os(macOS)
        // A mouse cannot swipe: previous and next buttons on the sides.
        .overlay {
            if items.count > 1 {
                HStack {
                    pageButton("Previous", systemImage: "chevron.backward", step: -1)
                    Spacer()
                    pageButton("Next", systemImage: "chevron.forward", step: 1)
                }
                .padding(.horizontal, 12)
            }
        }
        #endif
        .environment(\.colorScheme, .dark)
        .task(id: current) {
            guard Self.autoAdvances, !reduceMotion, items.count > 1 else { return }
            try? await Task.sleep(for: .seconds(7))
            guard !Task.isCancelled else { return }
            let index = items.firstIndex { $0.id == current } ?? 0
            withAnimation(.easeInOut(duration: 0.6)) { current = items[(index + 1) % items.count].id }
        }
    }

    /// A tall frame on iPhone, a wide one on iPad and Mac.
    private var height: CGFloat {
        compact ? min(max(width * 1.2, 380), 520) : min(max(width * 0.48, 360), 540)
    }

    #if os(macOS)
    private func pageButton(_ title: LocalizedStringKey, systemImage: String, step: Int) -> some View {
        Button {
            let index = items.firstIndex { $0.id == current } ?? 0
            withAnimation(.easeInOut(duration: 0.4)) { current = items[(index + step + items.count) % items.count].id }
        } label: {
            Image(systemName: systemImage)
                .font(.title2.weight(.semibold))
                .frame(width: 36, height: 36)
                .background(.black.opacity(0.35), in: Circle())
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .help(Text(title))
    }
    #endif

    private var pageDots: some View {
        HStack(spacing: 6) {
            ForEach(items) { item in
                let selected = item.id == (current ?? items.first?.id)
                Capsule()
                    .fill(.white.opacity(selected ? 0.95 : 0.35))
                    .frame(width: selected ? 18 : 6, height: 6)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: current)
        .accessibilityHidden(true)
    }
}

/// One page of the carousel.
private struct HeroPage: View {
    @Environment(AppModel.self) private var model
    let item: ShowcaseItem
    let compact: Bool
    let isCurrent: Bool

    var body: some View {
        let details = model.cachedDetails(for: item)
        ZStack(alignment: .bottomLeading) {
            // The picture opens the title's page, as its title does (the one VoiceOver reads).
            detailLink {
                picture(backdrop: model.backdropURL(for: item))
            }
            .accessibilityHidden(true)
            content(details: details)
        }
        .foregroundStyle(.white)
        .task(id: isCurrent) {
            if isCurrent { await model.loadTMDbBackdropIfNeeded(for: item) }
        }
    }

    // MARK: Picture

    private func picture(backdrop: URL?) -> some View {
        Color.black
            .overlay {
                if compact && backdrop == nil {
                    // A tall frame without a wide picture: the poster itself, rather than its blur.
                    RemoteImage(url: item.posterURL, placeholderText: item.title, contentMode: .fill, maxPixelSize: 900)
                } else {
                    BackdropImage(backdropURL: backdrop, posterURL: item.posterURL, dimming: 0.3) { model.backdropFailed($0, for: item) }
                }
            }
            .overlay {
                // The text's side: a dark shade from the left on a wide frame, from the bottom on every frame.
                if !compact {
                    LinearGradient(
                        stops: [.init(color: .black.opacity(0.85), location: 0), .init(color: .black.opacity(0.45), location: 0.42),
                                .init(color: .clear, location: 0.72)],
                        startPoint: .leading, endPoint: .trailing
                    )
                }
            }
            .overlay {
                LinearGradient(
                    stops: [.init(color: .clear, location: compact ? 0.38 : 0.5), .init(color: .black.opacity(compact ? 0.9 : 0.6), location: 1)],
                    startPoint: .top, endPoint: .bottom
                )
            }
            .clipped()
            .contentShape(Rectangle())
    }

    // MARK: Title, facts and buttons

    private func content(details: MediaDetails?) -> some View {
        VStack(alignment: .leading, spacing: compact ? 8 : 10) {
            detailLink {
                Text(item.title)
                    .font(compact ? .title.bold() : .system(.largeTitle, weight: .bold))
                    .tracking(compact ? -0.4 : -0.6)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            FactsLine(facts: facts(details: details), badges: badges, color: .white.opacity(0.75))
                .font(.subheadline)
            if !compact, let plot = details?.plot ?? item.plot, !plot.isEmpty {
                Text(plot)
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.85))
                    .lineLimit(3)
                    .frame(maxWidth: 520, alignment: .leading)
            }
            HStack(spacing: compact ? 10 : 12) {
                playButton
                myListButton
                if !compact {
                    detailLink {
                        Label("Info", systemImage: "info.circle")
                            .modifier(HeroSecondaryButton())
                    }
                    .accessibilityIdentifier(isCurrent ? "heroInfo" : "")
                }
            }
            .padding(.top, compact ? 6 : 8)
        }
        .padding(.horizontal, compact ? 18 : 28)
        .padding(.bottom, compact ? 30 : 28)
        .frame(maxWidth: compact ? CGFloat.infinity : 640, alignment: .leading)
    }

    // MARK: Actions

    private var playButton: some View {
        Button {
            Task { await model.playShowcase(item) }
        } label: {
            Label(playTitle, systemImage: "play.fill")
                .font(.headline)
                .foregroundStyle(.black)
                .padding(.horizontal, compact ? 22 : 26)
                .padding(.vertical, 12)
                .background(.white, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(isCurrent ? "heroPlay" : "")
    }

    private var myListButton: some View {
        let inList = model.isFavorite(item.contentType, id: item.itemID)
        return Button {
            switch item {
            case .movie(let movie): model.toggleFavorite(movie)
            case .series(let series): model.toggleFavorite(series)
            }
        } label: {
            Label("My List", systemImage: inList ? "checkmark" : "plus")
                .modifier(HeroSecondaryButton())
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(.plain)
        .accessibilityValue(inList ? Text("In My List") : Text(""))
        .accessibilityIdentifier(isCurrent ? "heroMyList" : "")
    }

    private var playTitle: LocalizedStringKey {
        if case .movie(let movie) = item, let progress = model.progress(movie: movie), !progress.isFinished, progress.position > 0 {
            return "Resume"
        }
        return "Play"
    }

    @ViewBuilder
    private func detailLink<Content: View>(@ViewBuilder label: () -> Content) -> some View {
        switch item {
        case .movie(let movie): NavigationLink(value: movie, label: label).buttonStyle(.plain)
        case .series(let series): NavigationLink(value: series, label: label).buttonStyle(.plain)
        }
    }

    /// "Movie · 2024 · Drama · 1 h 52 · ★ 7.8"
    private func facts(details: MediaDetails?) -> [String] {
        var parts = [String(localized: item.kindName)]
        if let year = item.year { parts.append(String(year)) }
        if let genre = (details?.genre ?? item.genre)?.split(separator: ",").first {
            parts.append(genre.trimmingCharacters(in: .whitespaces))
        }
        if case .movie = item, let seconds = details?.durationSeconds, seconds > 0 {
            parts.append(Formatting.duration(seconds))
        }
        if let rating = details?.rating ?? item.rating, rating > 0 {
            parts.append("★ " + Formatting.rating(rating))
        }
        return parts
    }

    /// The format of a movie's stream (MKV, MP4…), as on its page.
    private var badges: [String] {
        if case .movie(let movie) = item, let format = MediaFormat.label(for: movie.streamURL) { return [format] }
        return []
    }
}

/// A secondary button of the carousel: translucent white, white text.
private struct HeroSecondaryButton: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(.headline)
            .foregroundStyle(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.white.opacity(0.2), in: Capsule())
            .contentShape(Capsule())
    }
}

private extension ShowcaseItem {
    var itemID: String {
        switch self {
        case .movie(let movie): return movie.id
        case .series(let series): return series.id
        }
    }

    var plot: String? {
        switch self {
        case .movie(let movie): return movie.plot
        case .series(let series): return series.plot
        }
    }

    var genre: String? {
        if case .series(let series) = self { return series.genre }
        return nil
    }

    var kindName: String.LocalizationValue {
        switch self {
        case .movie: return "Movie"
        case .series: return "Series"
        }
    }
}

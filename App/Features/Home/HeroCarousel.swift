import SwiftUI
import IPTVCore

/// Full-width carousel of featured movies and series at the top of the home screen.
/// iPhone: blurred artwork with the poster centred; iPad / Mac: wide backdrop with the poster and text.
struct HeroCarousel: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let items: [ShowcaseItem]
    let compact: Bool
    /// Height of the bars the hero extends under (status + navigation bar on iOS).
    let topInset: CGFloat
    let width: CGFloat
    @State private var current: String?

    /// Auto-advance is off in UI tests and screenshots so captures are deterministic.
    private static let autoAdvances = !ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-uitest") }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 0) {
                ForEach(items) { item in
                    HeroPage(item: item, compact: compact, topInset: topInset, isCurrent: item.id == (current ?? items.first?.id))
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
        .overlay(alignment: .bottom) {
            if items.count > 1 { pageDots.padding(.bottom, 10) }
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

    private var height: CGFloat {
        compact ? topInset + 520 : topInset + min(max(width * 0.42, 380), 500)
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
    let topInset: CGFloat
    let isCurrent: Bool

    var body: some View {
        let details = model.cachedDetails(for: item)
        ZStack(alignment: compact ? .bottom : .bottomLeading) {
            background(backdrop: compact ? nil : model.backdropURL(for: item))
            if compact {
                compactContent(details: details)
            } else {
                regularContent(details: details)
            }
        }
        .foregroundStyle(.white)
    }

    // MARK: Background

    private func background(backdrop: URL?) -> some View {
        Color.black
            .overlay { BackdropImage(backdropURL: backdrop, posterURL: item.posterURL, dimming: 0.3) }
            .overlay {
                // Keeps the toolbar legible and the text readable.
                LinearGradient(
                    stops: [.init(color: .black.opacity(0.55), location: 0), .init(color: .clear, location: 0.3),
                            .init(color: .clear, location: 0.5), .init(color: .black.opacity(0.85), location: 1)],
                    startPoint: .top, endPoint: .bottom
                )
            }
            .overlay {
                if !compact {
                    LinearGradient(colors: [.black.opacity(0.8), .black.opacity(0.35), .clear], startPoint: .leading, endPoint: .trailing)
                }
            }
            .clipped()
            .accessibilityHidden(true)
    }

    // MARK: iPhone

    private func compactContent(details: MediaDetails?) -> some View {
        VStack(spacing: 14) {
            detailLink {
                PosterArtwork(url: item.posterURL, title: item.title)
                    .frame(width: 196)
                    .shadow(color: .black.opacity(0.5), radius: 18, y: 10)
            }
            VStack(spacing: 4) {
                Text(item.title)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                Text(metaLine(details: details))
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.75))
                    .lineLimit(1)
            }
            .padding(.horizontal, 24)
            HStack(spacing: 12) {
                playButton
                myListButton
            }
        }
        .padding(.top, topInset + 12)
        .padding(.bottom, 34)
    }

    // MARK: iPad / Mac

    private func regularContent(details: MediaDetails?) -> some View {
        HStack(alignment: .bottom, spacing: 28) {
            detailLink {
                PosterArtwork(url: item.posterURL, title: item.title)
                    .frame(width: 190)
                    .shadow(color: .black.opacity(0.5), radius: 18, y: 10)
            }
            VStack(alignment: .leading, spacing: 10) {
                Text(item.kindLabel)
                    .font(.caption.weight(.bold))
                    .tracking(1.2)
                    .foregroundStyle(.white.opacity(0.7))
                Text(item.title)
                    .font(.system(.largeTitle, weight: .bold))
                    .lineLimit(2)
                Text(metaLine(details: details))
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.75))
                if let plot = details?.plot ?? item.plot, !plot.isEmpty {
                    Text(plot)
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(3)
                        .frame(maxWidth: 560, alignment: .leading)
                }
                HStack(spacing: 12) {
                    playButton
                    myListButton
                    detailLink {
                        Label("Info", systemImage: "info.circle")
                            .font(.headline)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 12)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                    .accessibilityIdentifier(isCurrent ? "heroInfo" : "")
                }
                .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 32)
        .padding(.top, topInset + 16)
        .padding(.bottom, 36)
    }

    // MARK: Actions

    private var playButton: some View {
        Button {
            Task { await model.playShowcase(item) }
        } label: {
            Label(playTitle, systemImage: "play.fill")
                .font(.headline)
                .foregroundStyle(.black)
                .padding(.horizontal, 26)
                .padding(.vertical, 12)
                .background(.white, in: Capsule())
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
                .font(.headline)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(.ultraThinMaterial, in: Capsule())
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

    /// "Movie · 2024 · Drama · ★ 7.8"
    private func metaLine(details: MediaDetails?) -> String {
        var parts = [String(localized: item.kindName)]
        if let year = item.year { parts.append(String(year)) }
        if let genre = (details?.genre ?? item.genre)?.split(separator: ",").first {
            parts.append(genre.trimmingCharacters(in: .whitespaces))
        }
        if let rating = details?.rating ?? item.rating, rating > 0 {
            parts.append("★ " + Formatting.rating(rating))
        }
        return parts.joined(separator: " · ")
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

    var kindLabel: String { String(localized: kindName).uppercased() }
}

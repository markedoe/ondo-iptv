import SwiftUI
import IPTVCore

/// One live channel in a list: logo, number, name, programme now with progress.
struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    var date: Date = Date()

    var body: some View {
        let program = model.epg.current(for: channel, at: date)
        HStack(spacing: 12) {
            if model.settings.showChannelNumbers, let number = channel.number {
                Text("\(number)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 26, alignment: .trailing)
            }
            RemoteImage(url: model.logoURL(for: channel), placeholderText: channel.name, maxPixelSize: 160)
                .frame(width: 56, height: 38)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(channel.name)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    if channel.hasCatchup {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.caption)
                            .foregroundStyle(Color.accentColor)
                            .help("Replay available")
                    }
                }
                if let program {
                    Text(program.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    ProgressView(value: program.progress(at: date))
                        .progressViewStyle(.linear)
                        .frame(maxWidth: 220)
                        .scaleEffect(x: 1, y: 0.6, anchor: .center)
                }
            }
            Spacer(minLength: 0)
            if model.isFavorite(.live, id: channel.id) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityLabel("In My List")
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }
}

/// Poster tile used by movie and series grids.
struct PosterCard: View {
    let title: String
    let imageURL: URL?
    var subtitle: String?
    var rating: Double?
    var progress: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            PosterArtwork(url: imageURL, title: title, progress: progress)
                .overlay(alignment: .topTrailing) {
                    if let rating, rating > 0 {
                        Label(Formatting.rating(rating), systemImage: "star.fill")
                            .font(.caption2.weight(.semibold))
                            .labelStyle(.titleAndIcon)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(.ultraThinMaterial, in: Capsule())
                            .padding(6)
                    }
                }
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .contentShape(Rectangle())
    }
}

/// Square-ish channel tile used on the home screen.
struct ChannelTile: View {
    @Environment(AppModel.self) private var model
    let channel: Channel

    var body: some View {
        VStack(spacing: 6) {
            RemoteImage(url: model.logoURL(for: channel), placeholderText: channel.name, maxPixelSize: 240)
                .padding(10)
                .frame(width: 120, height: 76)
                .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
            Text(channel.name)
                .font(.caption)
                .lineLimit(1)
                .frame(width: 120)
            if let program = model.epg.current(for: channel) {
                Text(program.title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .frame(width: 120)
            }
        }
        .contentShape(Rectangle())
    }
}

struct SectionHeader: View {
    let title: LocalizedStringKey
    var systemImage: String?

    var body: some View {
        HStack(spacing: 8) {
            if let systemImage { Image(systemName: systemImage).foregroundStyle(Color.accentColor) }
            Text(title).font(.title3.bold())
            Spacer()
        }
    }
}

/// Asks for the parental PIN before opening locked content.
struct PINPromptView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onSuccess: () -> Void

    @State private var pin = ""
    @State private var failed = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield")
                .font(.system(size: 44))
                .foregroundStyle(Color.accentColor)
            Text("Parental Control").font(.title2.bold())
            Text("Enter your PIN to open locked content.")
                .foregroundStyle(.secondary)
            SecureField("PIN", text: $pin)
                .textFieldStyle(.roundedBorder)
                .multilineTextAlignment(.center)
                .font(.title2.monospacedDigit())
                .frame(maxWidth: 200)
                .focused($focused)
                #if os(iOS)
                .keyboardType(.numberPad)
                #endif
                .onSubmit(check)
            if model.pinRetryDate() != nil {
                Text("Too many wrong PINs. Wait a moment and try again.")
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            } else if failed {
                Text("Wrong PIN").foregroundStyle(.red)
            }
            HStack {
                Button("Cancel", role: .cancel) { dismiss() }
                Button("Unlock", action: check)
                    .buttonStyle(.borderedProminent)
                    .disabled(pin.count < 4)
            }
        }
        .padding(30)
        .onAppear { focused = true }
        .presentationDetents([.medium])
    }

    private func check() {
        if model.verifyParentalPIN(pin) {
            dismiss()
            onSuccess()
        } else {
            failed = true
            pin = ""
        }
    }
}

/// Settings that could switch parental control off (the control itself, profiles) open only once it is
/// unlocked. A screen that was open stays open when it locks again, so setting a new PIN or "Lock Now" does
/// not shut the person who did it out.
struct ParentalGate<Content: View>: View {
    @Environment(AppModel.self) private var model
    @ViewBuilder var content: Content

    @State private var opened = false
    @State private var askingPIN = false

    var body: some View {
        if opened || !model.settingsNeedParentalUnlock {
            content.onAppear { opened = true }
        } else {
            ContentUnavailableView {
                Label("Locked", systemImage: "lock.shield")
            } description: {
                if model.hasParentalPIN {
                    Text("Enter the parental control PIN to change these settings.")
                } else {
                    Text("Switch to a parent's profile to change these settings.")
                }
            } actions: {
                if model.hasParentalPIN {
                    Button("Unlock") { askingPIN = true }
                        .buttonStyle(.borderedProminent)
                }
            }
            .sheet(isPresented: $askingPIN) {
                PINPromptView {}
            }
        }
    }
}

/// Lists categories and shows the selected one: pushed on iPhone, side by side on Mac / iPad.
struct CategoryBrowser<Detail: View>: View {
    @Environment(AppModel.self) private var model
    @Environment(NavigationRouter.self) private var router: NavigationRouter?
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    let type: ContentType
    var pseudoCategories: [MediaCategory] = []
    @ViewBuilder var detail: (MediaCategory) -> Detail

    @State private var selected: MediaCategory?
    @State private var pinRequest: MediaCategory?
    @State private var filter = ""

    private var sideBySide: Bool {
        #if os(macOS)
        return true
        #else
        return sizeClass == .regular
        #endif
    }

    private var categories: [MediaCategory] {
        let all = model.categories(for: type)
        guard !filter.isEmpty else { return all }
        let tokens = TextNormalizer.normalize(filter).split(separator: " ")
        return all.filter { TextNormalizer.matches(normalizedText: TextNormalizer.normalize($0.name), queryTokens: tokens) }
    }

    var body: some View {
        Group {
            if sideBySide {
                HStack(spacing: 0) {
                    categoryList
                        .frame(width: 270)
                    Divider()
                    Group {
                        if let selected {
                            detail(selected)
                                .id(selected.id)
                        } else {
                            ContentUnavailableView("Select a category", systemImage: "square.grid.2x2")
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .onAppear {
                    if selected == nil { selected = pseudoCategories.first ?? categories.first }
                }
            } else {
                categoryList
                    .navigationDestination(for: MediaCategory.self) { category in
                        detail(category)
                    }
            }
        }
        .sheet(item: $pinRequest) { category in
            PINPromptView { open(category) }
        }
    }

    private var categoryList: some View {
        let list = List {
            if !pseudoCategories.isEmpty && filter.isEmpty {
                Section {
                    ForEach(pseudoCategories) { category in
                        row(category, count: nil)
                    }
                }
            }
            Section {
                ForEach(categories) { category in
                    row(category, count: model.count(in: category))
                }
            } footer: {
                if filter.isEmpty { HiddenCategoriesNote(type: type) }
            }
        }
        #if os(macOS)
        // On the Mac `.searchable` lives in the window toolbar, where the detail (the channel list) has its
        // own search field: two of them in one toolbar make AppKit's layout loop and the app crash.
        return VStack(spacing: 0) {
            TextField("Filter categories", text: $filter)
                .textFieldStyle(.roundedBorder)
                .padding(8)
            list.listStyle(.sidebar)
        }
        #else
        return list
            .searchable(text: $filter, placement: sideBySide ? .automatic : .navigationBarDrawer(displayMode: .automatic),
                        prompt: Text("Filter categories"))
            .modifier(AdaptiveListStyle(plain: sideBySide))
        #endif
    }

    private func row(_ category: MediaCategory, count: Int?) -> some View {
        let locked = model.isLocked(category)
        return Button {
            if locked {
                pinRequest = category
            } else {
                open(category)
            }
        } label: {
            HStack {
                Label(category.name, systemImage: icon(for: category))
                    .lineLimit(1)
                Spacer()
                if locked {
                    Image(systemName: "lock.fill").foregroundStyle(.secondary)
                } else if let count {
                    Text("\(count)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if !sideBySide {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(sideBySide && selected?.id == category.id ? Color.accentColor.opacity(0.18) : nil)
        .contextMenu { CategoryMenu(category: category) }
    }

    private func open(_ category: MediaCategory) {
        if sideBySide {
            selected = category
        } else {
            router?.push(category)
        }
    }

    private func icon(for category: MediaCategory) -> String {
        switch category.id {
        case PseudoCategory.all: return "square.grid.2x2"
        case PseudoCategory.favorites: return "list.star"
        case PseudoCategory.recent: return "clock"
        default:
            switch category.type {
            case .live: return "tv"
            case .movie: return "film"
            case .series: return "play.rectangle.on.rectangle"
            }
        }
    }
}

enum PseudoCategory {
    static let all = "__all__"
    static let favorites = "__favorites__"
    static let recent = "__recent__"

    static func all(_ type: ContentType) -> MediaCategory {
        MediaCategory(id: all, name: String(localized: "All"), type: type)
    }

    static func favorites(_ type: ContentType) -> MediaCategory {
        MediaCategory(id: favorites, name: String(localized: "My List"), type: type)
    }

    static func recent(_ type: ContentType) -> MediaCategory {
        MediaCategory(id: recent, name: String(localized: "Recently Watched"), type: type)
    }
}

#if os(iOS)
private struct AdaptiveListStyle: ViewModifier {
    let plain: Bool

    func body(content: Content) -> some View {
        if plain {
            content.listStyle(.plain)
        } else {
            content.listStyle(.insetGrouped)
        }
    }
}
#endif

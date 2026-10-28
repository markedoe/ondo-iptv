import IPTVCore
import SwiftUI

/// The directors, then the cast, as round photos (TMDb's, kept on the device) over their initials, the name beneath;
/// each opens the person's page. Shown under the credits of a movie's or a series' page.
struct CastShelf: View {
    @Environment(AppModel.self) private var model
    let item: ShowcaseItem
    let details: MediaDetails?
    let metrics: ShelfMetrics

    private static let limit = 20

    /// The people, the directors first, each once.
    private var people: [(name: String, isDirector: Bool)] {
        var seen = Set<String>()
        var list: [(name: String, isDirector: Bool)] = []
        for name in People.names(in: details?.director) where seen.insert(MetadataKey.person(name)).inserted {
            list.append((name, true))
        }
        for name in People.names(in: details?.cast) where seen.insert(MetadataKey.person(name)).inserted {
            list.append((name, false))
        }
        return Array(list.prefix(Self.limit))
    }

    var body: some View {
        let people = people
        if !people.isEmpty {
            Shelf(title: "Cast", metrics: metrics) {
                ForEach(people, id: \.name) { person in
                    NavigationLink(value: PersonRoute(name: person.name)) {
                        PersonCard(name: person.name, isDirector: person.isDirector, photoURL: model.personPhoto(named: person.name), metrics: metrics)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("castPhoto-\(person.name)")
                }
            }
            // The photos kept on the device; again once TMDb has answered for this title.
            .task(id: model.tmdbTitle(for: item)?.fetchedAt) {
                await model.loadPersonPhotos(people.map(\.name))
            }
        }
    }
}

/// A person of a page's credits: a round photo over their initials, the name and, for a director, the role beneath.
private struct PersonCard: View {
    let name: String
    let isDirector: Bool
    let photoURL: URL?
    let metrics: ShelfMetrics

    private var size: CGFloat { metrics.compact ? 76 : 96 }

    var body: some View {
        VStack(spacing: 6) {
            RemoteImage(url: photoURL, placeholderText: name, contentMode: .fill, maxPixelSize: 240)
                .frame(width: size, height: size)
                .clipShape(Circle())
                .accessibilityHidden(true)
            Text(verbatim: name)
                .font(.caption)
                .lineLimit(2)
                .multilineTextAlignment(.center)
            if isDirector {
                Text("Director")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size + 16)
        .accessibilityElement(children: .combine)
    }
}

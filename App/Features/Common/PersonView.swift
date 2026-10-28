import SwiftUI
import IPTVCore

/// Opened by tapping a name in a movie's or a series' details.
struct PersonRoute: Hashable {
    let name: String
}

/// The movies and series of the playlist with this actor or director, under their photo when TMDb gave one.
struct PersonView: View {
    @Environment(AppModel.self) private var model
    let name: String
    @State private var works = PersonWorks()
    @State private var isLoading = true

    var body: some View {
        List {
            if let photo = model.personPhoto(named: name) {
                // TMDb's photo of the person, when known (kept on the device).
                Section {
                    RemoteImage(url: photo, placeholderText: name, contentMode: .fill, maxPixelSize: 480)
                        .frame(width: 128, height: 128)
                        .clipShape(Circle())
                        .frame(maxWidth: .infinity)
                        .accessibilityHidden(true)
                }
                .listRowBackground(Color.clear)
            }
            if !works.movies.isEmpty {
                Section("Movies (\(works.movies.count))") {
                    ForEach(works.movies) { movie in
                        NavigationLink(value: movie) {
                            SearchResultRow(title: movie.name, subtitle: movie.year.map(String.init), imageURL: movie.posterURL, icon: "film")
                        }
                    }
                }
            }
            if !works.series.isEmpty {
                Section("Series (\(works.series.count))") {
                    ForEach(works.series) { series in
                        NavigationLink(value: series) {
                            SearchResultRow(title: series.name, subtitle: series.year.map(String.init), imageURL: series.coverURL,
                                            icon: "play.rectangle.on.rectangle")
                        }
                    }
                }
            }
            if !isLoading && !works.searchedOnline && !works.isEmpty {
                Section {} footer: { onlineHint }
            }
        }
        .overlay {
            if isLoading && works.isEmpty {
                ProgressView()
            } else if !isLoading && works.isEmpty {
                ContentUnavailableView {
                    Label("Nothing in this playlist", systemImage: "person.crop.rectangle.stack")
                } description: {
                    Text("No movie or series of this playlist is known to feature \(name).")
                    if !works.searchedOnline { onlineHint }
                }
            }
        }
        .navigationTitle(name)
        .task(id: name) {
            // The pages opened, then the local people index (offline too), then TMDb's credits.
            works = model.knownWorks(of: name)
            await model.loadPersonPhotos([name])
            works = await model.localWorks(of: name)
            works = await model.works(of: name)
            isLoading = false
        }
    }

    /// Without TMDb, only the pages already opened know their cast.
    private var onlineHint: Text {
        Text("Movies are only found among the pages you have opened. Add your TMDb API Read Access Token in Settings to find all of them.")
    }
}

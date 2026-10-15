import SwiftUI
import IPTVCore

/// Opened by tapping a name in a movie's or a series' details.
struct PersonRoute: Hashable {
    let name: String
}

/// The movies and series of the playlist with this actor or director.
struct PersonView: View {
    @Environment(AppModel.self) private var model
    let name: String
    @State private var works = PersonWorks()
    @State private var isLoading = true

    var body: some View {
        List {
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
            works = model.knownWorks(of: name)
            works = await model.works(of: name)
            isLoading = false
        }
    }

    /// Without TMDb, only the pages already opened know their cast.
    private var onlineHint: Text {
        Text("Only the pages you have opened are searched. Add a TMDb key in Settings to find all their movies and series.")
    }
}

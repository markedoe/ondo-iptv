import SwiftUI
import IPTVCore
import Observation

/// Owns the path of a navigation stack so programmatic pushes (after a PIN prompt, from a context menu…)
/// keep their order with `NavigationLink(value:)` pushes. SwiftUI always stacks `navigationDestination(item:)`
/// and view-destination links after the path, so every push in the main stacks is value based.
@MainActor
@Observable
final class NavigationRouter {
    var path = NavigationPath()

    func push<Value: Hashable>(_ value: Value) {
        path.append(value)
    }
}

/// A `NavigationStack` driven by a `NavigationRouter` published in the environment.
struct RoutedNavigationStack<Root: View>: View {
    @State private var router = NavigationRouter()
    @ViewBuilder var root: () -> Root

    var body: some View {
        NavigationStack(path: $router.path) {
            root()
        }
        .environment(router)
    }
}

/// Value pushed to open one of the user's custom lists.
struct CustomListRoute: Hashable {
    let id: UUID
}

/// Value pushed to open My List on one kind of content (My List → See All), or on its collections (nil).
struct FavoritesRoute: Hashable {
    let type: ContentType?
}

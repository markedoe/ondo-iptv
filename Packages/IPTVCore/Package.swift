// swift-tools-version:6.0
import PackageDescription

// Tools version 6.0: every target compiles in the Swift 6 language mode (complete concurrency checking).
let package = Package(
    name: "IPTVCore",
    // The app's deployment targets (project.yml), and tvOS for a later target (docs/PLAN.md).
    platforms: [.iOS(.v17), .macOS(.v14), .tvOS(.v17)],
    products: [
        .library(name: "IPTVCore", targets: ["IPTVCore"]),
    ],
    targets: [
        // Thin C wrapper around the system zlib, used to inflate gzip'd XMLTV guides
        // and playlists (`.xml.gz`, `.m3u.gz`) that URLSession does not decode itself.
        .target(
            name: "CZlib",
            linkerSettings: [.linkedLibrary("z")]
        ),
        .target(
            name: "IPTVCore",
            dependencies: ["CZlib"]
        ),
        .testTarget(
            name: "IPTVCoreTests",
            dependencies: ["IPTVCore"],
            resources: [.copy("Fixtures")]
        ),
    ]
)

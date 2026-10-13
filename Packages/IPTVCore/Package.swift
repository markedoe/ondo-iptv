// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "IPTVCore",
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

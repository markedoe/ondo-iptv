import XCTest
@testable import IPTVCore

/// A film's Dolby Vision profile, what a screen without Dolby Vision shows of it, and the version that plays first on
/// fake screens: 4K with HDR10 and no Dolby Vision, 1080p without HDR, 4K with Dolby Vision. The same as Android's
/// `DisplayFactsTest` and `StreamSpecsTest.dolbyVisionProfiles`. Invented names only.
final class DolbyVisionChoiceTests: XCTestCase {
    private let samsung4K = PictureSupport(hdr: true, dolbyVision: false, uhd: true)
    private let hd = PictureSupport()
    private let dolbyVision4K = PictureSupport(hdr: true, dolbyVision: true, uhd: true)

    private func movie(_ id: String, _ name: String) -> Movie {
        Movie(id: id, name: name, categoryID: "c", streamURL: URL(string: "http://stream.example.com/\(id).mkv")!, year: 2026)
    }

    private lazy var plain = movie("x1", "|FR| Quartz Garden")
    private lazy var dv = movie("x2", "|FR-4K DV| Quartz Garden")
    private lazy var uhd = movie("x3", "|FR| Quartz Garden 4K")

    private func first(_ versions: [Movie], _ support: PictureSupport, _ specs: [String: StreamSpecs] = [:]) -> (movie: Movie, reason: DefaultReason)? {
        FilmVersions.defaultChoice(versions, inProgress: nil, chosen: [], support: support, categoryName: { _ in nil }, specs: { specs[$0] })
    }

    private func notes(_ movie: Movie, _ support: PictureSupport, _ specs: StreamSpecs? = nil) -> [ScreenNote] {
        FilmVersions.screenNotes(FilmVersions.tags(name: movie.name, categoryName: nil), support: support, specs: specs)
    }

    private func dolbyVision(_ profile: Int?, _ compatibility: Int? = nil) -> StreamSpecs {
        StreamSpecs(width: 3840, height: 2160, range: .dolbyVision, videoCodec: "HEVC", dolbyVisionProfile: profile, dolbyVisionCompatibility: compatibility)
    }

    func testProfiles() {
        XCTAssertEqual(StreamSpecs.dolbyVisionProfile(ofCodecs: "dvhe.08.06"), 8)
        XCTAssertEqual(StreamSpecs.dolbyVisionProfile(ofCodecs: "dvh1.05.06"), 5)
        XCTAssertEqual(StreamSpecs.dolbyVisionProfile(ofCodecs: "dav1.10.09"), 10)
        XCTAssertNil(StreamSpecs.dolbyVisionProfile(ofCodecs: "hvc1.2.4.L153"))
        XCTAssertNil(StreamSpecs.dolbyVisionProfile(ofCodecs: "dvhe"))
        XCTAssertNil(StreamSpecs.dolbyVisionProfile(ofCodecs: nil))
        // A `dvcC` record: version 1.0, profile 8 level 6, RPU and base layer, compatibility 1 (HDR10).
        let record: [UInt8] = [1, 0, 8 << 1, (6 << 3) | 0b101, 1 << 4, 0, 0, 0]
        XCTAssertEqual(StreamSpecs.dolbyVisionConfiguration(record)?.profile, 8)
        XCTAssertEqual(StreamSpecs.dolbyVisionConfiguration(record)?.compatibility, 1)
        XCTAssertNil(StreamSpecs.dolbyVisionConfiguration([1, 0]))

        let stream = StreamSpecs(range: .dolbyVision)
        XCTAssertEqual(stream.dolbyVisionFallback, .unknown)
        XCTAssertEqual(dolbyVision(8).dolbyVisionFallback, .baseLayer)
        XCTAssertEqual(dolbyVision(8, 1).dolbyVisionFallback, .baseLayer)
        XCTAssertEqual(dolbyVision(5).dolbyVisionFallback, .wrongColours)
        XCTAssertEqual(dolbyVision(7).dolbyVisionFallback, .unknown)
        XCTAssertEqual(dolbyVision(10, 0).dolbyVisionFallback, .wrongColours)
        XCTAssertEqual(dolbyVision(10, 1).dolbyVisionFallback, .baseLayer)
        XCTAssertEqual(StreamSpecs(range: .hdr10).dolbyVisionFallback, .none)
        XCTAssertEqual(dolbyVision(8, 1).summary(), "3840×2160 · 4K · Dolby Vision P8.1 · HEVC")
        // The name's tag says Dolby Vision; measured SDR, it is none; the provider's HDR10 alone does not overrule it.
        let tags = FilmVersions.labelTags(name: "Sand Harbor 4K DV", categoryName: nil)
        XCTAssertEqual(FilmVersions.dolbyVisionFallback(tags, specs: nil), .unknown)
        XCTAssertEqual(FilmVersions.dolbyVisionFallback(tags, specs: StreamSpecs(width: 3840, height: 2160, range: .sdr, source: .measured)), .none)
        XCTAssertEqual(FilmVersions.dolbyVisionFallback(tags, specs: StreamSpecs(width: 3840, height: 2160, range: .hdr10)), .unknown)
        XCTAssertEqual(FilmVersions.dolbyVisionFallback(FilmVersions.labelTags(name: "Sand Harbor 4K", categoryName: nil), specs: StreamSpecs(range: .hdr10)), .none)
    }

    /// A 4K screen with HDR10 and no Dolby Vision: 4K first; Dolby Vision first only when its base layer shows right.
    func testA4KHDR10ScreenWithoutDolbyVision() {
        XCTAssertEqual(first([plain, dv], samsung4K)?.movie, plain)
        XCTAssertEqual(first([plain, dv], samsung4K)?.reason, .bestForScreen)
        XCTAssertEqual(notes(dv, samsung4K), [.dolbyVisionUnknown])
        XCTAssertEqual(first([plain, dv, uhd], samsung4K)?.movie, uhd)
        XCTAssertEqual(first([plain, dv], samsung4K, [dv.id: dolbyVision(8, 1)])?.movie, dv)
        XCTAssertEqual(notes(dv, samsung4K, dolbyVision(8, 1)), [.dolbyVisionBaseLayer])
        XCTAssertEqual(first([plain, dv], samsung4K, [dv.id: dolbyVision(5, 0)])?.movie, plain)
        XCTAssertEqual(notes(dv, samsung4K, dolbyVision(5, 0)), [.dolbyVisionWrongColours])
        let measured = [plain.id: StreamSpecs(width: 1920, height: 1080), uhd.id: StreamSpecs(width: 3840, height: 2160, range: .hdr10)]
        XCTAssertEqual(first([plain, uhd], samsung4K, measured)?.movie, uhd)
        XCTAssertEqual(notes(uhd, samsung4K, measured[uhd.id]), [])
    }

    /// A 1080p screen without HDR: the plain version; the others say why.
    func testA1080pScreenWithoutHDR() {
        XCTAssertEqual(first([plain, dv], hd)?.movie, plain)
        XCTAssertEqual(notes(dv, hd), [.dolbyVisionUnknown, .uhdUnsupported])
        XCTAssertEqual(first([plain, dv], hd, [dv.id: dolbyVision(8, 1)])?.movie, plain)
        XCTAssertEqual(notes(dv, hd, dolbyVision(8, 1)), [.dolbyVisionBaseLayer, .uhdUnsupported])
        XCTAssertEqual(notes(uhd, hd, StreamSpecs(width: 3840, height: 2160, range: .hdr10)), [.hdrUnsupported, .uhdUnsupported])
    }

    /// A 4K screen with Dolby Vision: the Dolby Vision version, whatever its profile.
    func testA4KDolbyVisionScreen() {
        XCTAssertEqual(first([plain, dv, uhd], dolbyVision4K)?.movie, dv)
        XCTAssertEqual(first([plain, dv, uhd], dolbyVision4K, [dv.id: dolbyVision(5, 0)])?.movie, dv)
        XCTAssertEqual(notes(dv, dolbyVision4K, dolbyVision(5, 0)), [])
    }

    /// The provider's names of `info`'s fields: plain names only, sorted, never a value nor an address.
    func testTheProvidersFieldNames() throws {
        let json = Data(#"{"name":"A","plot":"B","video":{"codec_name":"h264"},"audio":[],"https://panel.example.com/x":1}"#.utf8)
        let info = try JSONDecoder().decode(XtreamInfoDTO.self, from: json)
        XCTAssertEqual(info.providerInfo, ProviderInfo(keys: ["audio", "name", "plot", "video"], video: true, audio: false))
        // Details kept before the app noted it read without it.
        XCTAssertNil(try JSONDecoder().decode(MediaDetails.self, from: Data(#"{"title":"Sand Harbor"}"#.utf8)).providerInfo)
    }
}

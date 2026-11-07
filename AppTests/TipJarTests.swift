import XCTest
@testable import Ondo

/// The tips: the App Store's prices in order, a word for each outcome, and a tip paid outside the sheet thanked too.
@MainActor
final class TipJarTests: XCTestCase {
    private final class FakeStore: TipStore, @unchecked Sendable {
        var prices: [Tip: String] = [.small: "1,99 €", .medium: "4,99 €", .large: "9,99 €"]
        var result: TipResult = .purchased
        var purchased: [Tip] = []
        var loads = 0
        private var settled: (@Sendable (TipResult) async -> Void)?

        func offers(for tips: [Tip]) async -> [TipOffer] {
            loads += 1
            return tips.compactMap { tip in prices[tip].map { TipOffer(tip: tip, price: $0) } }
        }

        func purchase(_ tip: Tip) async -> TipResult {
            purchased.append(tip)
            return result
        }

        func settleTransactions(_ settled: @escaping @Sendable (TipResult) async -> Void) async {
            self.settled = settled
        }

        /// A transaction the App Store sends later (Ask to Buy approved, or paid while the app was closed).
        func approveLater() async { await settled?(.purchased) }
    }

    func testTheThreeTipsShowTheAppStorePricesInOrder() async {
        let jar = TipJar(store: FakeStore())
        XCTAssertTrue(jar.isLoading)
        await jar.load()
        XCTAssertEqual(jar.offers?.map(\.tip), Tip.allCases)
        XCTAssertEqual(jar.offers?.map(\.price), ["1,99 €", "4,99 €", "9,99 €"])
    }

    func testWithoutTheAppStoreTheTipsSaySoAndLoadAgainLater() async {
        let store = FakeStore()
        store.prices = [:]
        let jar = TipJar(store: store)
        await jar.load()
        XCTAssertTrue(jar.isUnavailable)
        store.prices = [.small: "0,99 $"]
        await jar.load()
        XCTAssertEqual(jar.offers?.count, 1)
        await jar.load()
        XCTAssertEqual(store.loads, 2, "loaded offers are kept")
    }

    func testEachOutcomeHasItsWord() async {
        let store = FakeStore()
        let jar = TipJar(store: store)
        await jar.load()
        let expected: [(TipResult, TipOutcome?)] = [(.purchased, .thanks), (.pending, .pending), (.failed, .failed), (.cancelled, nil)]
        for (result, outcome) in expected {
            store.result = result
            await jar.buy(.medium)
            XCTAssertEqual(jar.outcome, outcome, "\(result)")
        }
        XCTAssertEqual(store.purchased, [.medium, .medium, .medium, .medium])
        XCTAssertFalse(jar.isPurchasing)
    }

    func testATipApprovedLaterIsThankedToo() async throws {
        let store = FakeStore()
        let jar = TipJar(store: store)
        // The jar starts listening for transactions as it is made.
        for _ in 0..<50 where jar.outcome == nil {
            await store.approveLater()
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(jar.outcome, .thanks)
    }

    /// Listening for transactions does not keep the jar alive: the store holds it weakly.
    func testListeningForTransactionsDoesNotKeepTheJarAlive() async {
        let store = FakeStore()
        weak var released: TipJar?
        do {
            let jar = TipJar(store: store)
            released = jar
            await jar.load()
        }
        XCTAssertNil(released)
        await store.approveLater()
    }

    func testTheProductsAreTheConsumablesOfAppStoreConnect() {
        XCTAssertEqual(Tip.allCases.map(\.productID), ["com.markedoe.ondo.tip.small", "com.markedoe.ondo.tip.medium", "com.markedoe.ondo.tip.large"])
        XCTAssertEqual(Tip.with(productID: "com.markedoe.ondo.tip.large"), .large)
        XCTAssertNil(Tip.with(productID: "com.example.other"))
    }

    /// The app sells through the App Store unless a UI test asks for the fixed prices (`-uitest-tips`).
    func testTheAppSellsThroughTheAppStore() {
        XCTAssertFalse(UITestFixture.sellsTips)
        XCTAssertTrue(AppModel.tipStore is AppStoreTipStore)
    }

    /// The review screenshot of the tips shows the three tips at their App Store prices, in order.
    func testTheScreenshotStoreSellsTheThreeTipsAtTheirPrices() async {
        let jar = TipJar(store: FixtureTipStore())
        await jar.load()
        XCTAssertEqual(jar.offers?.map(\.tip), Tip.allCases)
        let prices = jar.offers?.map(\.price) ?? []
        for (price, amount) in zip(prices, ["1", "4", "9"]) {
            XCTAssertTrue(price.contains(amount) && price.contains("99") && price.contains("€"), price)
        }
        await jar.buy(.small)
        XCTAssertEqual(jar.outcome, .thanks)
    }

    func testEveryTipTextIsTranslated() throws {
        let keys = ["Leave a tip", "Small tip", "Medium tip", "Large tip", "Thank you for your tip!", "The tip didn’t go through."]
        for language in ["fr", "es", "pt", "zh-Hans", "ar", "hi"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
            let bundle = try XCTUnwrap(Bundle(path: path))
            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: "⟨missing⟩", table: nil), "⟨missing⟩", "\(key) in \(language)")
            }
        }
    }
}

import Foundation
import Observation
import StoreKit

/// The optional tips: three consumable In-App Purchases, finished at once so that a tip can be given again. A tip
/// unlocks nothing: everything in Ondo is there for everyone, with or without one.
enum Tip: String, CaseIterable, Identifiable, Sendable {
    case small, medium, large

    var id: String { rawValue }
    /// The product of App Store Connect (Consumable).
    var productID: String { "com.markedoe.ondo.tip.\(rawValue)" }

    var title: LocalizedStringResource {
        switch self {
        case .small: "Small tip"
        case .medium: "Medium tip"
        case .large: "Large tip"
        }
    }

    static func with(productID: String) -> Tip? { allCases.first { $0.productID == productID } }
}

/// A tip as the App Store sells it: its price in the viewer's currency ("1,99 €").
struct TipOffer: Identifiable, Equatable, Sendable {
    let tip: Tip
    let price: String
    var id: Tip.ID { tip.id }
}

/// How a purchase ended.
enum TipResult: Equatable, Sendable {
    case purchased, pending, cancelled, failed
}

/// What happened to the last tip, shown once under the tips.
enum TipOutcome: Equatable {
    case thanks, pending, failed
}

/// The App Store, behind what the tips use of it (a fake one in tests).
protocol TipStore: Sendable {
    /// The offers of these tips, in their order; empty when the App Store cannot sell them here.
    func offers(for tips: [Tip]) async -> [TipOffer]
    /// Buys a tip and finishes its transaction once paid.
    func purchase(_ tip: Tip) async -> TipResult
    /// Finishes the tips paid while the app was closed, or approved later (Ask to Buy), as they arrive.
    func settleTransactions(_ settled: @escaping @Sendable (TipResult) async -> Void) async
}

/// The tips' state for Settings: the App Store's offers, and the word said after a tip.
@MainActor
@Observable
final class TipJar {
    /// Nil while the App Store answers; empty when it cannot sell tips here.
    private(set) var offers: [TipOffer]?
    var outcome: TipOutcome?
    private(set) var isPurchasing = false

    @ObservationIgnored private let store: TipStore
    /// Lives as long as the app (AppModel holds the jar).
    @ObservationIgnored private var settling: Task<Void, Never>?

    init(store: TipStore = AppStoreTipStore()) {
        self.store = store
        settling = Task { [weak self] in
            await store.settleTransactions { result in
                await self?.settled(result)
            }
        }
    }

    var isLoading: Bool { offers == nil }
    var isUnavailable: Bool { offers?.isEmpty == true }

    /// Loads the offers when Settings opens, and again after a failure.
    func load() async {
        if let offers, !offers.isEmpty { return }
        offers = nil
        offers = await store.offers(for: Tip.allCases)
    }

    func buy(_ tip: Tip) async {
        guard !isPurchasing else { return }
        isPurchasing = true
        outcome = nil
        defer { isPurchasing = false }
        switch await store.purchase(tip) {
        case .purchased: outcome = .thanks
        case .pending: outcome = .pending
        case .cancelled: break
        case .failed: outcome = .failed
        }
    }

    /// A tip paid outside the purchase sheet (approved later, or paid while the app was closed) is thanked too.
    private func settled(_ result: TipResult) {
        if result == .purchased, !isPurchasing { outcome = .thanks }
    }
}

/// [TipStore] on StoreKit 2: verified transactions only, each finished once paid.
struct AppStoreTipStore: TipStore {
    func offers(for tips: [Tip]) async -> [TipOffer] {
        guard let products = try? await Product.products(for: tips.map(\.productID)) else { return [] }
        return tips.compactMap { tip in
            products.first { $0.id == tip.productID }.map { TipOffer(tip: tip, price: $0.displayPrice) }
        }
    }

    func purchase(_ tip: Tip) async -> TipResult {
        guard let product = try? await Product.products(for: [tip.productID]).first else { return .failed }
        do {
            switch try await product.purchase() {
            case .success(.verified(let transaction)):
                await transaction.finish()
                return .purchased
            case .success(.unverified(let transaction, _)):
                // Not signed by the App Store: finished so that it does not come back, and not thanked.
                await transaction.finish()
                return .failed
            case .pending:
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .failed
            }
        } catch {
            return .failed
        }
    }

    func settleTransactions(_ settled: @escaping @Sendable (TipResult) async -> Void) async {
        for await result in Transaction.unfinished {
            await finish(result, settled: nil)
        }
        for await result in Transaction.updates {
            await finish(result, settled: settled)
        }
    }

    private func finish(_ result: VerificationResult<Transaction>, settled: (@Sendable (TipResult) async -> Void)?) async {
        switch result {
        case .verified(let transaction):
            guard Tip.with(productID: transaction.productID) != nil else { return }
            await transaction.finish()
            if transaction.revocationDate == nil { await settled?(.purchased) }
        case .unverified(let transaction, _):
            guard Tip.with(productID: transaction.productID) != nil else { return }
            await transaction.finish()
        }
    }
}

import Foundation

/// Channel numbers as shown in lists and typed on a keyboard or remote ("zapping by number").
public enum ChannelNumbering {
    /// Number displayed for the channel at `position` (0-based) of a list.
    /// Lists where any channel has an explicit number (`tvg-chno`, Xtream `num`) show those numbers;
    /// other lists are numbered by position.
    public static func displayNumber(at position: Int, numbers: [Int?]) -> Int? {
        guard numbers.indices.contains(position) else { return nil }
        return usesExplicitNumbers(numbers) ? numbers[position] : position + 1
    }

    /// Position of the channel the user typed `number` for, matching `displayNumber(at:numbers:)`.
    public static func position(of number: Int, numbers: [Int?]) -> Int? {
        if usesExplicitNumbers(numbers) {
            return numbers.firstIndex(of: number)
        }
        return numbers.indices.contains(number - 1) ? number - 1 : nil
    }

    private static func usesExplicitNumbers(_ numbers: [Int?]) -> Bool {
        numbers.contains { $0 != nil }
    }
}

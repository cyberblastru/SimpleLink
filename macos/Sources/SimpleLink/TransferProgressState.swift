import Foundation

enum TransferDirection {
    case none
    case sending
    case receiving
}

struct TransferProgressState {
    var direction: TransferDirection = .none
    var done: Int64 = 0
    var total: Int64 = 0
    var label: String = ""

    var active: Bool {
        direction == .sending || direction == .receiving
    }

    var fraction: Double {
        guard total > 0 else { return 0 }
        return min(1, Double(done) / Double(total))
    }
}

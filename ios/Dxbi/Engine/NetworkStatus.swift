import Foundation
import Network
import Observation

/**
 * Whether the connection costs: mobile data (or a phone's hotspot) or Low Data Mode. Then Press Play
 * asks which stream rather than starting the best (often the biggest) by itself.
 */
@MainActor
@Observable
final class NetworkStatus {
    static let shared = NetworkStatus()

    private(set) var mobileData = false
    private(set) var lowDataMode = false

    /** Choose a stream rather than have the best one start */
    var saving: Bool { mobileData || lowDataMode }

    /** Why, in a few words, for the stream list */
    var reason: String {
        lowDataMode ? "Low Data Mode is on" : "You're on mobile data"
    }

    @ObservationIgnored private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let mobile = path.isExpensive
            let low = path.isConstrained
            Task { @MainActor in
                guard let self else { return }
                if self.mobileData != mobile { self.mobileData = mobile }
                if self.lowDataMode != low { self.lowDataMode = low }
            }
        }
        monitor.start(queue: DispatchQueue(label: "lumen.network-status"))
    }
}

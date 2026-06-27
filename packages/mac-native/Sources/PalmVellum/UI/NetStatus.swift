import Foundation
import Network

/// Live online/offline signal for the title-bar dot and the sync engine.
/// The SwiftUI analogue of the PWA's `online`/`offline` events +
/// `@capacitor/network`. A false→true transition is the cue to sync.
@MainActor
final class NetStatus: ObservableObject {
    @Published var online: Bool = true

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "dev.tatliving.palmvellum.net")

    /// Called on every false→true transition (set by the app to kick a sync).
    var onReconnect: (() -> Void)?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let wasOffline = !self.online
                self.online = up
                if up && wasOffline { self.onReconnect?() }
            }
        }
        monitor.start(queue: queue)
    }
}

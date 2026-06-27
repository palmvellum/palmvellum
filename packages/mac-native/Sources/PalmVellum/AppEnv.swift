import Foundation
import SwiftUI
import PalmKit

enum AppPaths {
    static func appSupportDir() -> URL {
        let fm = FileManager.default
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("PalmVellum", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var dbPath: String {
        appSupportDir().appendingPathComponent("palmvellum.sqlite").path
    }
}

/// App-wide singletons: local store + repository + cloud sync. Created once.
@MainActor
final class AppEnv: ObservableObject {
    let repo: Repository
    let cloud: CloudClient
    let sync: SyncEngine
    let calendar: CalendarService

    private var booted = false
    private var lastCalRefresh: Date?

    init() {
        let store = try! PalmStore(path: AppPaths.dbPath)
        repo = Repository(store: store)
        cloud = CloudClient()
        sync = SyncEngine(repo: repo, cloud: cloud)
        calendar = CalendarService(repo: repo, sync: sync)
    }

    /// Restore any persisted session, wire reconnection to sync, do a first sync,
    /// refresh calendar subscriptions, and start the periodic refresh loop.
    func bootstrap(net: NetStatus) {
        guard !booted else { return }
        booted = true
        net.onReconnect = { [weak self] in
            Task { await self?.sync.syncNow() }
        }
        Task {
            await cloud.restoreSession()
            if cloud.isSignedIn { await sync.syncNow() }
            _ = await calendar.refreshSubscriptions()
            lastCalRefresh = Date()
        }
        startCalendarRefreshLoop()
    }

    private func startCalendarRefreshLoop() {
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_600 * 1_000_000_000) // 1h tick
                guard let self else { return }
                let hours = UserDefaults.standard.integer(forKey: "calRefreshHours")
                guard hours > 0 else { continue }
                if let last = self.lastCalRefresh, Date().timeIntervalSince(last) < Double(hours) * 3600 { continue }
                _ = await self.calendar.refreshSubscriptions()
                self.lastCalRefresh = Date()
            }
        }
    }
}

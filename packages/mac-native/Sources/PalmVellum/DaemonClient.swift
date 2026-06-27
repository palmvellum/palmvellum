import Foundation
import AppKit

/// Status reported by the "PalmVellum Sync on Mac" daemon over its localhost API.
struct DaemonStatus: Decodable {
    var ok: Bool = false
    var version: String?
    var user: String?          // signed-in email
    var palmPresent: Bool?     // a Palm is on USB
    var card: String?          // mounted backup-card volume, or nil
    var lastHotsync: String?

    enum CodingKeys: String, CodingKey {
        case ok, version, user, card
        case palmPresent = "palm_present"
        case lastHotsync = "last_hotsync"
    }
}

struct CardSyncResult: Decodable {
    var ok: Bool = false
    var message: String?
    var changed: Int?
}

/// Talks to the "PalmVellum Sync on Mac" daemon (the Go HotSync engine) over its
/// localhost-only HTTP API on :7733. The two apps stay loosely coupled — data
/// converges through Supabase; this is purely a control/status surface so the
/// native GUI can trigger HotSync / card sync and show device state.
@MainActor
final class DaemonClient {
    static let base = URL(string: "http://127.0.0.1:7733")!

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 5
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    func isRunning() async -> Bool {
        var req = URLRequest(url: Self.base.appendingPathComponent("health"))
        req.timeoutInterval = 2
        guard let (_, resp) = try? await session.data(for: req) else { return false }
        return (resp as? HTTPURLResponse)?.statusCode == 200
    }

    func status() async throws -> DaemonStatus {
        let (data, _) = try await session.data(from: Self.base.appendingPathComponent("v1/status"))
        return try JSONDecoder().decode(DaemonStatus.self, from: data)
    }

    @discardableResult
    func cardSync() async throws -> CardSyncResult {
        var req = URLRequest(url: Self.base.appendingPathComponent("v1/card-sync"))
        req.httpMethod = "POST"
        let (data, _) = try await session.data(for: req)
        return (try? JSONDecoder().decode(CardSyncResult.self, from: data)) ?? CardSyncResult(ok: true)
    }

    func hotsync() async throws {
        var req = URLRequest(url: Self.base.appendingPathComponent("v1/hotsync"))
        req.httpMethod = "POST"
        _ = try await session.data(for: req)
    }

    /// Find + launch the "PalmVellum Sync on Mac" app (where the USB-HotSync UI lives).
    /// Returns false if the app isn't installed.
    @discardableResult
    func launchDaemonApp() -> Bool {
        let candidates = [
            "/Applications/PalmVellum Sync on Mac.app",
            "\(NSHomeDirectory())/Applications/PalmVellum Sync on Mac.app",
        ]
        for path in candidates where FileManager.default.fileExists(atPath: path) {
            NSWorkspace.shared.open(URL(fileURLWithPath: path))
            return true
        }
        return false
    }
}

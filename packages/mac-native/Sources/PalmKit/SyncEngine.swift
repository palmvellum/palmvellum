import Foundation
import Combine

/// Orchestrates opt-in cloud sync: claim local rows → pull (detecting
/// conflicts) → push remaining dirty rows. Mirrors the Android `SyncEngine`.
/// The 3-way `remoteUpdatedAt` comparison is the load-bearing conflict logic.
@MainActor
public final class SyncEngine: ObservableObject {
    public enum Status: Equatable { case idle, syncing, success, error }

    @Published public private(set) var status: Status = .idle
    @Published public private(set) var lastError: String?
    @Published public private(set) var lastSyncedAt: Date?

    private let repo: Repository
    public let cloud: CloudClient
    private var inFlight = false

    public init(repo: Repository, cloud: CloudClient) {
        self.repo = repo
        self.cloud = cloud
    }

    public var isSignedIn: Bool { cloud.isSignedIn }

    @discardableResult
    public func syncNow() async -> Bool {
        guard let uid = cloud.userId else { return false }
        guard !inFlight else { return false }
        inFlight = true
        status = .syncing
        lastError = nil
        defer { inFlight = false }
        do {
            try repo.claim(userId: uid)
            try await pull(uid: uid)   // pull first → detect conflicts, apply clean remote changes
            try await push(uid: uid)
            lastSyncedAt = Date()
            status = .success
            return true
        } catch {
            lastError = String(describing: error)
            status = .error
            return false
        }
    }

    // MARK: Pull

    private func pull(uid: String) async throws {
        for dto in try await cloud.fetchEvents(userId: uid) {
            try mergeEvent(dto)
        }
        for dto in try await cloud.fetchRecords(userId: uid) {
            try mergeRecord(dto)
        }
        for dto in try await cloud.fetchDrafts(userId: uid) {
            try mergeDraft(dto)
        }
    }

    private func mergeDraft(_ dto: DraftDTO) throws {
        // Drafts are server-authoritative (the AI fills parsed_events). Apply
        // remote unless there's an unpushed local change.
        let local = try repo.getDraft(id: dto.id)
        if local == nil || local?.isDirty == false {
            try repo.saveSynced(EventDraft(synced: dto))
        }
    }

    private func mergeEvent(_ dto: EventDTO) throws {
        let local = try repo.getEvent(id: dto.id)
        switch SyncDecision.decide(localExists: local != nil, localIsDirty: local?.isDirty ?? false,
                                   localRemoteUpdatedAt: local?.remoteUpdatedAt, remoteUpdatedAt: dto.updated_at) {
        case .applyRemote:
            try repo.saveSynced(EventRecord(synced: dto))
        case .keepLocal:
            break
        case .conflict:
            guard let local else { return }
            var marked = local
            marked.syncState = SyncState.conflict
            try repo.saveSynced(marked)
            try repo.insertConflict(ConflictRow(
                id: "evt-\(dto.id)", entityTable: "events", entityId: dto.id, entityType: nil,
                titleHint: local.title,
                localJson: encode(local), remoteJson: encodeDTO(dto),
                localUpdatedAt: local.updatedAt, remoteUpdatedAt: dto.updated_at,
                detectedAt: Clock.nowIso()))
        }
    }

    private func mergeRecord(_ dto: RecordDTO) throws {
        let local = try repo.getRecord(id: dto.id)
        switch SyncDecision.decide(localExists: local != nil, localIsDirty: local?.isDirty ?? false,
                                   localRemoteUpdatedAt: local?.remoteUpdatedAt, remoteUpdatedAt: dto.updated_at) {
        case .applyRemote:
            try repo.saveSynced(PalmRecord(synced: dto))
        case .keepLocal:
            break
        case .conflict:
            guard let local else { return }
            var marked = local
            marked.syncState = SyncState.conflict
            try repo.saveSynced(marked)
            try repo.insertConflict(ConflictRow(
                id: "rec-\(dto.id)", entityTable: "records", entityId: dto.id, entityType: dto.type,
                titleHint: local.body ?? dto.type,
                localJson: encode(local), remoteJson: encodeDTO(dto),
                localUpdatedAt: local.updatedAt, remoteUpdatedAt: dto.updated_at,
                detectedAt: Clock.nowIso()))
        }
    }

    // MARK: Push

    private func push(uid: String) async throws {
        for e in try repo.dirtyEvents() {
            let returned = try await cloud.upsertEvent(e.push(userId: uid))
            if let server = returned.first {
                var x = e
                x.isDirty = false
                x.syncState = SyncState.synced
                x.remoteUpdatedAt = server.updated_at
                try repo.saveSynced(x)
            }
        }
        for r in try repo.dirtyRecords() {
            let returned = try await cloud.upsertRecord(r.push(userId: uid))
            if let server = returned.first {
                var x = r
                x.isDirty = false
                x.syncState = SyncState.synced
                x.remoteUpdatedAt = server.updated_at
                try repo.saveSynced(x)
            }
        }
        for d in try repo.dirtyDrafts() {
            try await cloud.upsertDraft(d.push(userId: uid))
            var x = d
            x.isDirty = false
            x.syncState = SyncState.synced
            try repo.saveSynced(x)
        }
    }

    // MARK: Conflict resolution

    /// keepLocal=true → re-dirty local so the next push overwrites the server.
    /// keepLocal=false → adopt the remote snapshot locally.
    public func resolveConflict(_ conflict: ConflictRow, keepLocal: Bool) async {
        do {
            if conflict.entityTable == "events" {
                if keepLocal {
                    if var e = try repo.getEvent(id: conflict.entityId) {
                        e.isDirty = true; e.syncState = SyncState.local; e.updatedAt = Clock.nowIso()
                        try repo.saveSynced(e)
                    }
                } else if let dto = try decodeEventDTO(conflict.remoteJson) {
                    try repo.saveSynced(EventRecord(synced: dto))
                }
            } else {
                if keepLocal {
                    if var r = try repo.getRecord(id: conflict.entityId) {
                        r.isDirty = true; r.syncState = SyncState.local; r.updatedAt = Clock.nowIso()
                        try repo.saveSynced(r)
                    }
                } else if let dto = try decodeRecordDTO(conflict.remoteJson) {
                    try repo.saveSynced(PalmRecord(synced: dto))
                }
            }
            try repo.deleteConflict(id: conflict.id)
            _ = await syncNow()
        } catch {
            lastError = String(describing: error)
        }
    }

    // MARK: JSON helpers (for conflict snapshots)

    private func encode<T: Encodable>(_ v: T) -> String {
        guard let data = try? JSONEncoder().encode(v), let s = String(data: data, encoding: .utf8) else { return "{}" }
        return s
    }
    private func encodeDTO(_ dto: EventDTO) -> String { encode(dto) }
    private func encodeDTO(_ dto: RecordDTO) -> String { encode(dto) }
    private func decodeEventDTO(_ s: String) throws -> EventDTO? {
        guard let d = s.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(EventDTO.self, from: d)
    }
    private func decodeRecordDTO(_ s: String) throws -> RecordDTO? {
        guard let d = s.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(RecordDTO.self, from: d)
    }
}

import Foundation
import GRDB

/// The write API + change observation over `PalmStore`. UI writes always stamp
/// `updatedAt` / `isDirty=true` / `syncState=local`; the sync engine uses the
/// separate `*Synced` paths so it doesn't re-dirty rows it just pulled.
public final class Repository {
    public let store: PalmStore
    public init(store: PalmStore) { self.store = store }

    private var dbQueue: DatabaseQueue { store.dbQueue }

    // MARK: Observation

    public func observeEvents(_ onChange: @escaping ([EventRecord]) -> Void) -> ObservationToken {
        ObservationToken(ValueObservation
            .tracking { db in
                try EventRecord
                    .filter(Column("deleted_at") == nil)
                    .order(Column("start_at"))
                    .fetchAll(db)
            }
            .start(in: dbQueue, onError: { _ in }, onChange: onChange))
    }

    public func observeRecords(type: String, _ onChange: @escaping ([PalmRecord]) -> Void) -> ObservationToken {
        ObservationToken(ValueObservation
            .tracking { db in
                try PalmRecord
                    .filter(Column("type") == type && Column("deleted_at") == nil)
                    .order(Column("updated_at").desc)
                    .fetchAll(db)
            }
            .start(in: dbQueue, onError: { _ in }, onChange: onChange))
    }

    public func observeConflictCount(_ onChange: @escaping (Int) -> Void) -> ObservationToken {
        ObservationToken(ValueObservation
            .tracking { db in try ConflictRow.fetchCount(db) }
            .start(in: dbQueue, onError: { _ in }, onChange: onChange))
    }

    public func observeConflicts(_ onChange: @escaping ([ConflictRow]) -> Void) -> ObservationToken {
        ObservationToken(ValueObservation
            .tracking { db in try ConflictRow.order(Column("detected_at").desc).fetchAll(db) }
            .start(in: dbQueue, onError: { _ in }, onChange: onChange))
    }

    // MARK: Events

    public func saveEvent(_ event: EventRecord) throws {
        var e = event
        e.updatedAt = Clock.nowIso()
        e.isDirty = true
        e.syncState = SyncState.local
        try dbQueue.write { db in try e.upsert(db) }
    }

    public func deleteEvent(id: String) throws {
        try dbQueue.write { db in
            guard var e = try EventRecord.fetchOne(db, key: id) else { return }
            let now = Clock.nowIso()
            e.deletedAt = now
            e.updatedAt = now
            e.isDirty = true
            e.syncState = SyncState.local
            try e.upsert(db)
        }
    }

    // MARK: Records

    public func saveRecord(_ record: PalmRecord) throws {
        var r = record
        r.updatedAt = Clock.nowIso()
        r.isDirty = true
        r.syncState = SyncState.local
        try dbQueue.write { db in try r.upsert(db) }
    }

    public func deleteRecord(id: String) throws {
        try dbQueue.write { db in
            guard var r = try PalmRecord.fetchOne(db, key: id) else { return }
            let now = Clock.nowIso()
            r.deletedAt = now
            r.updatedAt = now
            r.isDirty = true
            r.syncState = SyncState.local
            try r.upsert(db)
        }
    }

    // MARK: Sync helpers (used by SyncEngine — do NOT re-dirty)

    public func dirtyEvents() throws -> [EventRecord] {
        try dbQueue.read { db in
            try EventRecord.filter(Column("is_dirty") == true && Column("sync_state") != SyncState.conflict).fetchAll(db)
        }
    }

    public func dirtyRecords() throws -> [PalmRecord] {
        try dbQueue.read { db in
            try PalmRecord.filter(Column("is_dirty") == true && Column("sync_state") != SyncState.conflict).fetchAll(db)
        }
    }

    // MARK: Event drafts (Plan with AI)

    public func observeActiveDrafts(_ onChange: @escaping ([EventDraft]) -> Void) -> ObservationToken {
        ObservationToken(ValueObservation
            .tracking { db in
                try EventDraft
                    .filter(["pending", "parsing", "parsed"].contains(Column("status")))
                    .order(Column("created_at").desc)
                    .fetchAll(db)
            }
            .start(in: dbQueue, onError: { _ in }, onChange: onChange))
    }

    public func saveDraft(_ draft: EventDraft) throws {
        var d = draft
        d.isDirty = true
        d.syncState = SyncState.local
        try dbQueue.write { db in try d.upsert(db) }
    }

    public func saveSynced(_ draft: EventDraft) throws {
        try dbQueue.write { db in var d = draft; try d.upsert(db) }
    }

    public func getDraft(id: String) throws -> EventDraft? {
        try dbQueue.read { db in try EventDraft.fetchOne(db, key: id) }
    }

    public func dirtyDrafts() throws -> [EventDraft] {
        try dbQueue.read { db in try EventDraft.filter(Column("is_dirty") == true).fetchAll(db) }
    }

    public func getEvent(id: String) throws -> EventRecord? {
        try dbQueue.read { db in try EventRecord.fetchOne(db, key: id) }
    }

    public func getRecord(id: String) throws -> PalmRecord? {
        try dbQueue.read { db in try PalmRecord.fetchOne(db, key: id) }
    }

    public func recordsOnce(type: String) throws -> [PalmRecord] {
        try dbQueue.read { db in
            try PalmRecord.filter(Column("type") == type && Column("deleted_at") == nil).fetchAll(db)
        }
    }

    /// Write a row exactly as given, WITHOUT re-dirtying it (used by the sync
    /// engine when applying server rows or marking pushed rows as synced).
    public func saveSynced(_ event: EventRecord) throws {
        try dbQueue.write { db in var e = event; try e.upsert(db) }
    }

    public func saveSynced(_ record: PalmRecord) throws {
        try dbQueue.write { db in var r = record; try r.upsert(db) }
    }

    // MARK: Conflicts

    public func insertConflict(_ c: ConflictRow) throws {
        try dbQueue.write { db in var x = c; try x.upsert(db) }
    }

    public func getConflict(id: String) throws -> ConflictRow? {
        try dbQueue.read { db in try ConflictRow.fetchOne(db, key: id) }
    }

    public func deleteConflict(id: String) throws {
        _ = try dbQueue.write { db in try ConflictRow.deleteOne(db, key: id) }
    }

    /// Soft-delete every active record of a type (e.g. Mail "delete all").
    public func deleteAllRecords(type: String) throws {
        try dbQueue.write { db in
            let now = Clock.nowIso()
            try db.execute(sql: """
                UPDATE records SET deleted_at = ?, updated_at = ?, is_dirty = 1, sync_state = 'local'
                WHERE type = ? AND deleted_at IS NULL
                """, arguments: [now, now, type])
        }
    }

    public func claim(userId: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE events SET user_id = ?, is_dirty = 1 WHERE user_id IS NULL", arguments: [userId])
            try db.execute(sql: "UPDATE records SET user_id = ?, is_dirty = 1 WHERE user_id IS NULL", arguments: [userId])
            try db.execute(sql: "UPDATE event_drafts SET user_id = ?, is_dirty = 1 WHERE user_id IS NULL", arguments: [userId])
        }
    }
}

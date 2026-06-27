import Foundation
import GRDB

// MARK: - Sync metadata convention
//
// Every syncable row carries three LOCAL-ONLY columns that never get pushed to
// the server: `isDirty` (has un-pushed local changes), `remoteUpdatedAt` (last
// server `updated_at` we saw — used for conflict detection), and `syncState`
// (`local` / `synced` / `conflict`). Deletes are soft (`deletedAt` stamped).

public enum SyncState {
    public static let local = "local"
    public static let synced = "synced"
    public static let conflict = "conflict"
}

// MARK: - events (Date Book)

public struct EventRecord: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    public static let databaseTableName = "events"

    public var id: String
    public var userId: String?
    public var title: String
    public var startAt: String
    public var endAt: String?
    public var allDay: Bool
    public var location: String?
    public var notes: String?
    public var alarmMinutes: Int?
    public var repeatRule: String?
    public var source: String
    public var deviceId: String?
    public var createdAt: String
    public var updatedAt: String
    public var deletedAt: String?
    public var isDirty: Bool
    public var remoteUpdatedAt: String?
    public var syncState: String

    enum CodingKeys: String, CodingKey {
        case id, title, location, notes, source
        case userId = "user_id"
        case startAt = "start_at"
        case endAt = "end_at"
        case allDay = "all_day"
        case alarmMinutes = "alarm_minutes"
        case repeatRule = "repeat_rule"
        case deviceId = "device_id"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case isDirty = "is_dirty"
        case remoteUpdatedAt = "remote_updated_at"
        case syncState = "sync_state"
    }

    public static func new(
        title: String,
        startAt: String,
        endAt: String? = nil,
        allDay: Bool = false,
        location: String? = nil,
        notes: String? = nil,
        alarmMinutes: Int? = nil,
        repeatRule: String? = nil,
        id: String = Ulid.new(),
        source: String = "mac-native"
    ) -> EventRecord {
        let now = Clock.nowIso()
        return EventRecord(
            id: id, userId: nil, title: title, startAt: startAt, endAt: endAt,
            allDay: allDay, location: location, notes: notes, alarmMinutes: alarmMinutes,
            repeatRule: repeatRule, source: source, deviceId: nil,
            createdAt: now, updatedAt: now, deletedAt: nil,
            isDirty: true, remoteUpdatedAt: nil, syncState: SyncState.local
        )
    }
}

// MARK: - records (type-discriminated catch-all)

public enum RecordType {
    public static let todo = "todo"
    public static let contact = "contact"
    public static let thought = "thought"   // Memo
    public static let sketch = "sketch"      // Note Pad
    public static let expense = "expense"
    public static let mail = "mail"
    public static let calsub = "calsub"      // calendar subscription
    public static let aiquery = "aiquery"
}

public struct PalmRecord: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    public static let databaseTableName = "records"

    public var id: String
    public var userId: String?
    public var type: String
    public var posture: String
    public var body: String?
    public var tags: String          // JSON array text
    public var metadata: String      // JSON object text
    public var source: String
    public var deviceId: String?
    public var createdAt: String
    public var updatedAt: String
    public var deletedAt: String?
    public var aiStatus: String?
    public var aiResponse: String?
    public var isDirty: Bool
    public var remoteUpdatedAt: String?
    public var syncState: String

    enum CodingKeys: String, CodingKey {
        case id, type, posture, body, tags, metadata, source
        case userId = "user_id"
        case deviceId = "device_id"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case aiStatus = "ai_status"
        case aiResponse = "ai_response"
        case isDirty = "is_dirty"
        case remoteUpdatedAt = "remote_updated_at"
        case syncState = "sync_state"
    }

    public static func new(
        type: String,
        body: String? = nil,
        metadata: String = "{}",
        aiStatus: String? = nil,
        id: String = Ulid.new(),
        source: String = "mac-native"
    ) -> PalmRecord {
        let now = Clock.nowIso()
        return PalmRecord(
            id: id, userId: nil, type: type, posture: "open", body: body,
            tags: "[]", metadata: metadata, source: source, deviceId: nil,
            createdAt: now, updatedAt: now, deletedAt: nil,
            aiStatus: aiStatus, aiResponse: nil,
            isDirty: true, remoteUpdatedAt: nil, syncState: SyncState.local
        )
    }
}

// MARK: - event_drafts (AI "Plan with AI")

public struct EventDraft: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    public static let databaseTableName = "event_drafts"

    public var id: String
    public var userId: String?
    public var rawInput: String
    public var userTz: String
    public var parsedEvents: String   // JSON array text
    public var status: String
    public var aiError: String?
    public var createdAt: String
    public var processedAt: String?
    public var confirmedAt: String?
    public var isDirty: Bool
    public var syncState: String

    enum CodingKeys: String, CodingKey {
        case id, status
        case userId = "user_id"
        case rawInput = "raw_input"
        case userTz = "user_tz"
        case parsedEvents = "parsed_events"
        case aiError = "ai_error"
        case createdAt = "created_at"
        case processedAt = "processed_at"
        case confirmedAt = "confirmed_at"
        case isDirty = "is_dirty"
        case syncState = "sync_state"
    }

    public static func newPending(rawInput: String, userTz: String, id: String = Ulid.new()) -> EventDraft {
        EventDraft(
            id: id, userId: nil, rawInput: rawInput, userTz: userTz, parsedEvents: "[]",
            status: "pending", aiError: nil, createdAt: Clock.nowIso(),
            processedAt: nil, confirmedAt: nil, isDirty: true, syncState: SyncState.local)
    }
}

// MARK: - conflicts (purely local, never synced)

public struct ConflictRow: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    public static let databaseTableName = "conflicts"

    public var id: String
    public var entityTable: String
    public var entityId: String
    public var entityType: String?
    public var titleHint: String
    public var localJson: String
    public var remoteJson: String
    public var localUpdatedAt: String
    public var remoteUpdatedAt: String
    public var detectedAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case entityTable = "entity_table"
        case entityId = "entity_id"
        case entityType = "entity_type"
        case titleHint = "title_hint"
        case localJson = "local_json"
        case remoteJson = "remote_json"
        case localUpdatedAt = "local_updated_at"
        case remoteUpdatedAt = "remote_updated_at"
        case detectedAt = "detected_at"
    }
}

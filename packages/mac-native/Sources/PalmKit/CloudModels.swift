import Foundation
import Supabase

// Wire shapes for PostgREST. Column names are snake_case to match the server
// schema exactly (same rows the PWA + Android read/write). `tags`/`metadata`
// are real JSON on the server, carried here as `AnyJSON`.

struct EventDTO: Codable {
    let id: String
    let user_id: String?
    let title: String
    let start_at: String
    let end_at: String?
    let all_day: Bool?
    let location: String?
    let notes: String?
    let alarm_minutes: Int?
    let repeat_rule: String?
    let source: String?
    let device_id: String?
    let created_at: String
    let updated_at: String
    let deleted_at: String?
}

struct RecordDTO: Codable {
    let id: String
    let user_id: String?
    let type: String
    let posture: String?
    let body: String?
    let tags: AnyJSON?
    let metadata: AnyJSON?
    let source: String?
    let device_id: String?
    let created_at: String
    let updated_at: String
    let deleted_at: String?
    let ai_status: String?
    let ai_response: String?
}

struct EventPush: Encodable {
    let id: String
    let user_id: String?
    let title: String
    let start_at: String
    let end_at: String?
    let all_day: Bool
    let location: String?
    let notes: String?
    let alarm_minutes: Int?
    let repeat_rule: String?
    let source: String
    let device_id: String?
    let created_at: String
    let updated_at: String
    let deleted_at: String?
}

struct RecordPush: Encodable {
    let id: String
    let user_id: String?
    let type: String
    let posture: String
    let body: String?
    let tags: AnyJSON
    let metadata: AnyJSON
    let ai_status: String?
    let source: String
    let device_id: String?
    let created_at: String
    let updated_at: String
    let deleted_at: String?
}

struct DraftDTO: Codable {
    let id: String
    let user_id: String?
    let raw_input: String
    let user_tz: String
    let parsed_events: AnyJSON?
    let status: String
    let ai_error: String?
    let created_at: String
    let processed_at: String?
    let confirmed_at: String?
}

struct DraftPush: Encodable {
    let id: String
    let user_id: String?
    let raw_input: String
    let user_tz: String
    let parsed_events: AnyJSON
    let status: String
    let created_at: String
    let confirmed_at: String?
}

// MARK: - AnyJSON <-> JSON string

enum JSONBridge {
    static func anyJSON(fromString s: String, fallbackObject: Bool) -> AnyJSON {
        if let data = s.data(using: .utf8), let v = try? JSONDecoder().decode(AnyJSON.self, from: data) {
            return v
        }
        return fallbackObject ? .object([:]) : .array([])
    }

    static func string(from json: AnyJSON?, fallback: String) -> String {
        guard let json else { return fallback }
        if let data = try? JSONEncoder().encode(json), let s = String(data: data, encoding: .utf8) {
            return s
        }
        return fallback
    }
}

// MARK: - DTO -> local model (marked synced)

extension EventRecord {
    init(synced dto: EventDTO) {
        self.init(
            id: dto.id, userId: dto.user_id, title: dto.title,
            startAt: dto.start_at, endAt: dto.end_at, allDay: dto.all_day ?? false,
            location: dto.location, notes: dto.notes, alarmMinutes: dto.alarm_minutes,
            repeatRule: dto.repeat_rule, source: dto.source ?? "cloud", deviceId: dto.device_id,
            createdAt: dto.created_at, updatedAt: dto.updated_at, deletedAt: dto.deleted_at,
            isDirty: false, remoteUpdatedAt: dto.updated_at, syncState: SyncState.synced
        )
    }
}

extension PalmRecord {
    init(synced dto: RecordDTO) {
        self.init(
            id: dto.id, userId: dto.user_id, type: dto.type, posture: dto.posture ?? "open",
            body: dto.body,
            tags: JSONBridge.string(from: dto.tags, fallback: "[]"),
            metadata: JSONBridge.string(from: dto.metadata, fallback: "{}"),
            source: dto.source ?? "cloud", deviceId: dto.device_id,
            createdAt: dto.created_at, updatedAt: dto.updated_at, deletedAt: dto.deleted_at,
            aiStatus: dto.ai_status, aiResponse: dto.ai_response,
            isDirty: false, remoteUpdatedAt: dto.updated_at, syncState: SyncState.synced
        )
    }
}

extension EventDraft {
    init(synced dto: DraftDTO) {
        self.init(
            id: dto.id, userId: dto.user_id, rawInput: dto.raw_input, userTz: dto.user_tz,
            parsedEvents: JSONBridge.string(from: dto.parsed_events, fallback: "[]"),
            status: dto.status, aiError: dto.ai_error,
            createdAt: dto.created_at, processedAt: dto.processed_at, confirmedAt: dto.confirmed_at,
            isDirty: false, syncState: SyncState.synced
        )
    }

    func push(userId: String) -> DraftPush {
        DraftPush(
            id: id, user_id: userId, raw_input: rawInput, user_tz: userTz,
            parsed_events: JSONBridge.anyJSON(fromString: parsedEvents, fallbackObject: false),
            status: status, created_at: createdAt, confirmed_at: confirmedAt
        )
    }
}

/// One AI-parsed event proposal inside `event_drafts.parsed_events`.
public struct ParsedEvent: Codable {
    public let title: String?
    public let start_at: String?
    public let end_at: String?
    public let all_day: Bool?
    public let location: String?
    public let notes: String?
    public let alarm_minutes: Int?
}

// MARK: - local model -> push DTO

extension EventRecord {
    func push(userId: String) -> EventPush {
        EventPush(
            id: id, user_id: userId, title: title, start_at: startAt, end_at: endAt,
            all_day: allDay, location: location, notes: notes, alarm_minutes: alarmMinutes,
            repeat_rule: repeatRule, source: source, device_id: deviceId,
            created_at: createdAt, updated_at: updatedAt, deleted_at: deletedAt
        )
    }
}

extension PalmRecord {
    func push(userId: String) -> RecordPush {
        RecordPush(
            id: id, user_id: userId, type: type, posture: posture, body: body,
            tags: JSONBridge.anyJSON(fromString: tags, fallbackObject: false),
            metadata: JSONBridge.anyJSON(fromString: metadata, fallbackObject: true),
            ai_status: aiStatus, source: source, device_id: deviceId,
            created_at: createdAt, updated_at: updatedAt, deleted_at: deletedAt
        )
    }
}

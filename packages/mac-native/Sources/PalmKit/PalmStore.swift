import Foundation
import GRDB

/// Local-first SQLite store (the single source of truth). Mirrors the 4-table
/// design of the Android app (Room) and the PWA (Dexie): `events`, `records`,
/// `event_drafts`, plus a local-only `conflicts` table.
public final class PalmStore {
    public let dbQueue: DatabaseQueue

    public init(path: String) throws {
        dbQueue = try DatabaseQueue(path: path)
        try Self.migrator.migrate(dbQueue)
    }

    /// In-memory store for tests.
    public init() throws {
        dbQueue = try DatabaseQueue()
        try Self.migrator.migrate(dbQueue)
    }

    static var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()

        m.registerMigration("v1") { db in
            try db.execute(sql: """
                CREATE TABLE events (
                    id TEXT PRIMARY KEY NOT NULL,
                    user_id TEXT,
                    title TEXT NOT NULL,
                    start_at TEXT NOT NULL,
                    end_at TEXT,
                    all_day INTEGER NOT NULL DEFAULT 0,
                    location TEXT,
                    notes TEXT,
                    alarm_minutes INTEGER,
                    repeat_rule TEXT,
                    source TEXT NOT NULL DEFAULT 'mac-native',
                    device_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    deleted_at TEXT,
                    is_dirty INTEGER NOT NULL DEFAULT 1,
                    remote_updated_at TEXT,
                    sync_state TEXT NOT NULL DEFAULT 'local'
                );
                CREATE INDEX idx_events_start ON events(start_at);
                CREATE INDEX idx_events_dirty ON events(is_dirty);

                CREATE TABLE records (
                    id TEXT PRIMARY KEY NOT NULL,
                    user_id TEXT,
                    type TEXT NOT NULL,
                    posture TEXT NOT NULL DEFAULT 'open',
                    body TEXT,
                    tags TEXT NOT NULL DEFAULT '[]',
                    metadata TEXT NOT NULL DEFAULT '{}',
                    source TEXT NOT NULL DEFAULT 'mac-native',
                    device_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    deleted_at TEXT,
                    ai_status TEXT,
                    ai_response TEXT,
                    is_dirty INTEGER NOT NULL DEFAULT 1,
                    remote_updated_at TEXT,
                    sync_state TEXT NOT NULL DEFAULT 'local'
                );
                CREATE INDEX idx_records_type ON records(type);
                CREATE INDEX idx_records_dirty ON records(is_dirty);

                CREATE TABLE event_drafts (
                    id TEXT PRIMARY KEY NOT NULL,
                    user_id TEXT,
                    raw_input TEXT NOT NULL,
                    user_tz TEXT NOT NULL,
                    parsed_events TEXT NOT NULL DEFAULT '[]',
                    status TEXT NOT NULL DEFAULT 'pending',
                    ai_error TEXT,
                    created_at TEXT NOT NULL,
                    processed_at TEXT,
                    confirmed_at TEXT,
                    is_dirty INTEGER NOT NULL DEFAULT 1,
                    sync_state TEXT NOT NULL DEFAULT 'local'
                );

                CREATE TABLE conflicts (
                    id TEXT PRIMARY KEY NOT NULL,
                    entity_table TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    entity_type TEXT,
                    title_hint TEXT NOT NULL DEFAULT '',
                    local_json TEXT NOT NULL,
                    remote_json TEXT NOT NULL,
                    local_updated_at TEXT NOT NULL,
                    remote_updated_at TEXT NOT NULL,
                    detected_at TEXT NOT NULL
                );
            """)
        }

        return m
    }
}

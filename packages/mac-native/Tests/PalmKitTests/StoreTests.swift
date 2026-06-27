import Testing
import Foundation
import GRDB
@testable import PalmKit

@Test func eventCrudRoundTrip() throws {
    let store = try PalmStore()
    let repo = Repository(store: store)

    var e = EventRecord.new(title: "Site Visit", startAt: Clock.nowIso())
    try repo.saveEvent(e)

    var all = try store.dbQueue.read { db in try EventRecord.filter(Column("deleted_at") == nil).fetchAll(db) }
    #expect(all.count == 1)
    #expect(all[0].title == "Site Visit")
    #expect(all[0].isDirty == true)
    #expect(all[0].syncState == SyncState.local)

    // Update.
    e = all[0]
    e.title = "Site Visit (rescheduled)"
    try repo.saveEvent(e)
    all = try store.dbQueue.read { db in try EventRecord.filter(Column("deleted_at") == nil).fetchAll(db) }
    #expect(all.count == 1)
    #expect(all[0].title == "Site Visit (rescheduled)")

    // Soft delete keeps the row but hides it from active queries.
    try repo.deleteEvent(id: e.id)
    let active = try store.dbQueue.read { db in try EventRecord.filter(Column("deleted_at") == nil).fetchAll(db) }
    let total = try store.dbQueue.read { db in try EventRecord.fetchCount(db) }
    #expect(active.isEmpty)
    #expect(total == 1)
}

@Test func recordWithTypedFields() throws {
    let store = try PalmStore()
    let repo = Repository(store: store)

    var fields = TodoFields()
    fields.palmPriority = 1
    fields.palmDueDate = "2026-07-01"
    let meta = fields.merged(into: "{}")
    let rec = PalmRecord.new(type: RecordType.todo, body: "Call supplier", metadata: meta)
    try repo.saveRecord(rec)

    let fetched = try store.dbQueue.read { db in
        try PalmRecord.filter(Column("type") == RecordType.todo).fetchAll(db)
    }
    #expect(fetched.count == 1)
    let back = TodoFields(from: fetched[0].metadata)
    #expect(back.palmPriority == 1)
    #expect(back.palmDueDate == "2026-07-01")
    #expect(back.palmCompleted == false)
}

@Test func metadataMergePreservesUnknownKeys() throws {
    // Simulate a record that already carries AI/upload metadata we don't own.
    let existing = #"{"upload_path":"u/x.pdf","palm_category_name":"Work"}"#
    var fields = TodoFields()
    fields.palmPriority = 2
    let merged = fields.merged(into: existing)
    let dict = PalmJSON.dict(merged)
    #expect(dict["upload_path"] as? String == "u/x.pdf")   // preserved
    #expect(dict["palm_priority"] as? Int == 2)            // overwritten by our field
}

@Test func claimAdoptsLocalRows() throws {
    let store = try PalmStore()
    let repo = Repository(store: store)
    try repo.saveEvent(EventRecord.new(title: "x", startAt: Clock.nowIso()))
    try repo.saveRecord(PalmRecord.new(type: RecordType.thought, body: "note"))
    try repo.claim(userId: "user-123")
    let e = try store.dbQueue.read { db in try EventRecord.fetchAll(db) }
    let r = try store.dbQueue.read { db in try PalmRecord.fetchAll(db) }
    #expect(e.allSatisfy { $0.userId == "user-123" })
    #expect(r.allSatisfy { $0.userId == "user-123" })
}

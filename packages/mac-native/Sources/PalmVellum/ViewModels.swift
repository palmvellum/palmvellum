import Foundation
import SwiftUI
import PalmKit

@MainActor
final class DateBookVM: ObservableObject {
    @Published var events: [EventRecord] = []
    @Published var todos: [PalmRecord] = []   // for (TO DO) pseudo-events
    @Published var pendingDrafts: [EventDraft] = []

    private let repo: Repository
    private let sync: SyncEngine?
    private var eventToken: ObservationToken?
    private var todoToken: ObservationToken?
    private var draftToken: ObservationToken?
    private var acceptedDraftIds = Set<String>()

    init(repo: Repository, sync: SyncEngine?) {
        self.repo = repo
        self.sync = sync
        eventToken = repo.observeEvents { [weak self] in self?.events = $0 }
        todoToken = repo.observeRecords(type: RecordType.todo) { [weak self] in self?.todos = $0 }
        draftToken = repo.observeActiveDrafts { [weak self] in self?.handleDrafts($0) }
    }

    func save(_ e: EventRecord) { try? repo.saveEvent(e) }
    func delete(_ id: String) { try? repo.deleteEvent(id: id) }

    // MARK: Plan with AI

    func planWithAI(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let draft = EventDraft.newPending(rawInput: trimmed, userTz: TimeZone.current.identifier)
        try? repo.saveDraft(draft)
        triggerSync()
    }

    private func handleDrafts(_ drafts: [EventDraft]) {
        pendingDrafts = drafts.filter { $0.status == "pending" || $0.status == "parsing" }
        for draft in drafts where draft.status == "parsed" && !acceptedDraftIds.contains(draft.id) {
            acceptedDraftIds.insert(draft.id)
            acceptDraft(draft)
        }
    }

    private func acceptDraft(_ draft: EventDraft) {
        if let data = draft.parsedEvents.data(using: .utf8),
           let parsed = try? JSONDecoder().decode([ParsedEvent].self, from: data) {
            for p in parsed {
                guard let title = p.title, let start = p.start_at else { continue }
                let e = EventRecord.new(
                    title: title, startAt: start, endAt: p.end_at,
                    allDay: p.all_day ?? false, location: p.location,
                    notes: p.notes, alarmMinutes: p.alarm_minutes)
                try? repo.saveEvent(e)
            }
        }
        var d = draft
        d.status = "confirmed"
        d.confirmedAt = Clock.nowIso()
        try? repo.saveDraft(d)   // dirty → pushes the confirmed status
        triggerSync()
    }

    private func triggerSync() {
        guard let sync, sync.isSignedIn else { return }
        Task { await sync.syncNow() }
    }
}

@MainActor
final class TodoVM: ObservableObject {
    @Published var todos: [PalmRecord] = []
    private let repo: Repository
    private var token: ObservationToken?

    init(repo: Repository) {
        self.repo = repo
        token = repo.observeRecords(type: RecordType.todo) { [weak self] in self?.todos = $0 }
    }

    func save(_ r: PalmRecord) { try? repo.saveRecord(r) }
    func delete(_ id: String) { try? repo.deleteRecord(id: id) }

    func toggleDone(_ r: PalmRecord) {
        var fields = TodoFields(from: r.metadata)
        fields.palmCompleted.toggle()
        var copy = r
        copy.metadata = fields.merged(into: r.metadata)
        try? repo.saveRecord(copy)
    }
}

@MainActor
final class AddressVM: ObservableObject {
    @Published var contacts: [PalmRecord] = []
    private let repo: Repository
    private var token: ObservationToken?

    init(repo: Repository) {
        self.repo = repo
        token = repo.observeRecords(type: RecordType.contact) { [weak self] in self?.contacts = $0 }
    }

    func save(_ r: PalmRecord) { try? repo.saveRecord(r) }
    func delete(_ id: String) { try? repo.deleteRecord(id: id) }
}

@MainActor
final class ExpenseVM: ObservableObject {
    @Published var expenses: [PalmRecord] = []
    private let repo: Repository
    private var token: ObservationToken?

    init(repo: Repository) {
        self.repo = repo
        token = repo.observeRecords(type: RecordType.expense) { [weak self] in self?.expenses = $0 }
    }

    func save(_ r: PalmRecord) { try? repo.saveRecord(r) }
    func delete(_ id: String) { try? repo.deleteRecord(id: id) }
}

@MainActor
final class NotePadVM: ObservableObject {
    @Published var sketches: [PalmRecord] = []
    private let repo: Repository
    private var token: ObservationToken?

    init(repo: Repository) {
        self.repo = repo
        token = repo.observeRecords(type: RecordType.sketch) { [weak self] in self?.sketches = $0 }
    }

    func delete(_ id: String) { try? repo.deleteRecord(id: id) }
}

@MainActor
final class MailVM: ObservableObject {
    @Published var inbox: [PalmRecord] = []
    @Published var sources: [MailSource] = []
    @Published var sourcesError: String?

    private let repo: Repository
    let cloud: CloudClient
    private var token: ObservationToken?

    init(repo: Repository, cloud: CloudClient) {
        self.repo = repo
        self.cloud = cloud
        token = repo.observeRecords(type: RecordType.mail) { [weak self] in self?.inbox = $0 }
    }

    var isSignedIn: Bool { cloud.isSignedIn }

    func delete(_ id: String) { try? repo.deleteRecord(id: id) }
    func deleteAll() { try? repo.deleteAllRecords(type: RecordType.mail) }

    func loadSources() async {
        guard let uid = cloud.userId else { sources = []; return }
        do { sources = try await cloud.fetchMailSources(userId: uid); sourcesError = nil }
        catch { sourcesError = String(describing: error) }
    }

    func saveSource(_ push: MailSourcePush) async {
        guard let uid = cloud.userId else { sourcesError = "not signed in"; return }
        let withUid = MailSourcePush(
            id: push.id, user_id: uid, name: push.name, url: push.url, topic: push.topic,
            source_type: push.source_type, enabled: push.enabled, fetch_time: push.fetch_time,
            timezone: push.timezone, output_language: push.output_language)
        do { try await cloud.upsertMailSource(withUid); await loadSources() }
        catch { sourcesError = String(describing: error) }
    }

    func deleteSource(_ id: String) async {
        do { try await cloud.deleteMailSource(id: id); await loadSources() }
        catch { sourcesError = String(describing: error) }
    }

    func fetchNow(_ id: String) async {
        try? await cloud.fetchMailNow(sourceId: id)
    }
}

@MainActor
final class CalSubsVM: ObservableObject {
    @Published var subs: [CalSub] = []
    private let repo: Repository
    private var token: ObservationToken?

    init(repo: Repository) {
        self.repo = repo
        token = repo.observeRecords(type: RecordType.calsub) { [weak self] recs in
            self?.subs = recs.map { rec in
                let name = (PalmJSON.dict(rec.metadata)["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                return CalSub(name: name ?? (rec.body ?? ""), url: rec.body ?? "")
            }
        }
    }
}

@MainActor
final class ConflictsVM: ObservableObject {
    @Published var conflicts: [ConflictRow] = []
    private let repo: Repository
    private var token: ObservationToken?

    init(repo: Repository) {
        self.repo = repo
        token = repo.observeConflicts { [weak self] in self?.conflicts = $0 }
    }
}

@MainActor
final class MemoVM: ObservableObject {
    @Published var memos: [PalmRecord] = []
    @Published var uploadError: String?

    private let repo: Repository
    private let cloud: CloudClient
    private let sync: SyncEngine
    private var token: ObservationToken?

    init(repo: Repository, cloud: CloudClient, sync: SyncEngine) {
        self.repo = repo
        self.cloud = cloud
        self.sync = sync
        token = repo.observeRecords(type: RecordType.thought) { [weak self] in self?.memos = $0 }
    }

    var canUpload: Bool { cloud.isSignedIn }

    func save(_ r: PalmRecord) { try? repo.saveRecord(r) }
    func delete(_ id: String) { try? repo.deleteRecord(id: id) }

    /// Upload a file → pending `thought` record → server `summarize-upload`
    /// fills the body with an AI summary, which arrives on the next pull.
    func uploadFile(data: Data, filename: String, mimetype: String) async {
        guard let uid = cloud.userId else { uploadError = "sign in to upload"; return }
        let id = Ulid.new()
        let ext = (filename as NSString).pathExtension
        let path = "\(uid)/\(id).\(ext)"
        do {
            try await cloud.uploadObject(bucket: "memo-uploads", path: path, data: data, contentType: mimetype)
            var rec = PalmRecord.new(type: RecordType.thought, body: "(FILE) \(filename) …", id: id)
            rec.metadata = PalmJSON.string([
                "upload_path": path, "upload_filename": filename, "upload_mimetype": mimetype])
            rec.aiStatus = "pending"
            try? repo.saveRecord(rec)
            await sync.syncNow()
            uploadError = nil
        } catch {
            uploadError = String(describing: error)
        }
    }
}

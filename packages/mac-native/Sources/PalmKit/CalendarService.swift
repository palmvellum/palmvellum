import Foundation

/// A read-only calendar subscription: a name + an iCal (.ics) feed URL
/// (e.g. a Google Calendar "Secret address in iCal format").
public struct CalSub: Identifiable, Equatable {
    public var id: String { url }
    public let name: String
    public let url: String
    public init(name: String, url: String) { self.name = name; self.url = url }
}

/// Calendar subscriptions + .ics import. Faithful port of the Android
/// `CalendarSubscriptions.kt`.
///
/// The subscription LIST is cloud-synced as `calsub` records (deterministic id
/// from the URL hash → de-dupes across devices/web). Imported/subscribed events
/// get deterministic `ics<hash>` ids so re-fetching doesn't duplicate rows.
@MainActor
public final class CalendarService {
    private let repo: Repository
    private let sync: SyncEngine?

    public init(repo: Repository, sync: SyncEngine?) {
        self.repo = repo
        self.sync = sync
    }

    private func calSub(from rec: PalmRecord) -> CalSub {
        let name = (PalmJSON.dict(rec.metadata)["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return CalSub(name: name ?? (rec.body ?? ""), url: rec.body ?? "")
    }

    public func listOnce() -> [CalSub] {
        ((try? repo.recordsOnce(type: RecordType.calsub)) ?? []).map(calSub(from:))
    }

    public func addSubscription(name: String, url: String) {
        let id = DeterministicId.calsub(url: url)
        let existing = try? repo.getRecord(id: id)
        var rec = existing ?? PalmRecord.new(type: RecordType.calsub, id: id)
        rec.body = url
        rec.metadata = PalmJSON.string(["name": name])
        rec.deletedAt = nil
        try? repo.saveRecord(rec)
        triggerSync()
    }

    public func removeSubscription(url: String) {
        try? repo.deleteRecord(id: DeterministicId.calsub(url: url))
        triggerSync()
    }

    /// One-off import of a .ics document's VEVENTs as new events.
    @discardableResult
    public func importIcs(text: String) -> Int {
        let parsed = Ics.parse(text)
        for e in parsed {
            let event = EventRecord.new(
                title: e.summary, startAt: e.startIso, endAt: e.endIso,
                allDay: e.allDay, location: e.location, notes: e.description)
            try? repo.saveEvent(event)
        }
        triggerSync()
        return parsed.count
    }

    /// Fetch every subscribed feed and upsert its events. Read-only: events
    /// removed upstream are not deleted locally (deliberate simplification).
    @discardableResult
    public func refreshSubscriptions() async -> (changed: Int, error: String?) {
        let subs = listOnce()
        guard !subs.isEmpty else { return (0, nil) }
        var changed = 0
        var lastError: String?

        for sub in subs {
            guard let url = URL(string: sub.url) else { continue }
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                let text = String(data: data, encoding: .utf8) ?? ""
                for e in Ics.parse(text) {
                    let key = e.uid ?? (e.summary + e.startIso)
                    let id = DeterministicId.ics(url: sub.url, key: key)
                    let existing = try? repo.getEvent(id: id)
                    if let existing,
                       existing.title == e.summary, existing.startAt == e.startIso,
                       existing.endAt == e.endIso, existing.allDay == e.allDay,
                       existing.location == e.location, existing.notes == e.description,
                       existing.deletedAt == nil {
                        continue  // unchanged → skip to avoid sync churn
                    }
                    var event = EventRecord.new(
                        title: e.summary, startAt: e.startIso, endAt: e.endIso,
                        allDay: e.allDay, location: e.location, notes: e.description,
                        id: id, source: "ics-sub")
                    if let existing { event.createdAt = existing.createdAt }
                    try? repo.saveEvent(event)
                    changed += 1
                }
            } catch {
                lastError = error.localizedDescription
            }
        }
        triggerSync()
        return (changed, lastError)
    }

    private func triggerSync() {
        guard let sync, sync.isSignedIn else { return }
        Task { await sync.syncNow() }
    }
}

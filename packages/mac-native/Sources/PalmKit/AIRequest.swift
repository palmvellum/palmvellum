import Foundation

/// AI routing for Memo / To Do: a body starting with `(ai)` (case-insensitive)
/// marks the record for the server-side agent. The client just sets
/// `ai_status = pending`; the DB trigger + Edge Function (BYOK) do the rest,
/// and the result arrives on the next pull.
public enum AIRequest {
    public static func isAiRequest(_ body: String?) -> Bool {
        guard let body else { return false }
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return trimmed.hasPrefix("(ai)")
    }

    public static func isBusy(_ aiStatus: String?) -> Bool {
        guard let s = aiStatus else { return false }
        return s == "pending" || s == "processing" || s == "queued"
    }
}

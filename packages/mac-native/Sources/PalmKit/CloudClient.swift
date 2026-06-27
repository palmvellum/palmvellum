import Foundation
import Supabase

/// Thin wrapper over `supabase-swift`: email-OTP auth + the PostgREST calls the
/// sync engine needs. The SDK persists the session and attaches the bearer
/// token to every request automatically (RLS scopes rows to the user).
public final class CloudClient {
    public let client: SupabaseClient

    public init() {
        client = SupabaseClient(
            supabaseURL: SupabaseConfig.url,
            supabaseKey: SupabaseConfig.publishableKey
        )
    }

    // MARK: Auth

    public var userId: String? {
        client.auth.currentUser?.id.uuidString.lowercased()
    }

    public var email: String? {
        client.auth.currentUser?.email
    }

    public var isSignedIn: Bool {
        client.auth.currentSession != nil
    }

    public func sendOtp(email: String) async throws {
        try await client.auth.signInWithOTP(email: email, shouldCreateUser: true)
    }

    public func verifyOtp(email: String, token: String) async throws {
        try await client.auth.verifyOTP(email: email, token: token, type: .email)
    }

    public func signOut() async throws {
        try await client.auth.signOut()
    }

    /// Restore any persisted session at launch (no-op if none).
    public func restoreSession() async {
        _ = try? await client.auth.session
    }

    // MARK: Pull

    func fetchEvents(userId: String) async throws -> [EventDTO] {
        try await client.from("events").select().eq("user_id", value: userId).execute().value
    }

    func fetchRecords(userId: String) async throws -> [RecordDTO] {
        try await client.from("records").select().eq("user_id", value: userId).execute().value
    }

    // MARK: Push

    @discardableResult
    func upsertEvent(_ row: EventPush) async throws -> [EventDTO] {
        try await client.from("events")
            .upsert(row, onConflict: "id", returning: .representation)
            .execute().value
    }

    @discardableResult
    func upsertRecord(_ row: RecordPush) async throws -> [RecordDTO] {
        try await client.from("records")
            .upsert(row, onConflict: "id", returning: .representation)
            .execute().value
    }

    func fetchDrafts(userId: String) async throws -> [DraftDTO] {
        try await client.from("event_drafts").select().eq("user_id", value: userId).execute().value
    }

    @discardableResult
    func upsertDraft(_ row: DraftPush) async throws -> [DraftDTO] {
        try await client.from("event_drafts")
            .upsert(row, onConflict: "id", returning: .representation)
            .execute().value
    }

    // MARK: iCal feed (publish your calendar)

    private struct IcalTokenRow: Decodable { let ical_token: String? }

    public func currentIcalToken() async throws -> String? {
        let rows: [IcalTokenRow] = try await client.from("user_settings").select("ical_token").execute().value
        return rows.first?.ical_token
    }

    public func mintIcalToken() async throws -> String? {
        try? await client.rpc("mint_ical_token").execute()
        return try await currentIcalToken()
    }

    public func revokeIcalToken() async throws {
        try await client.rpc("revoke_ical_token").execute()
    }

    public func icalFeedURL(token: String) -> String {
        "\(SupabaseConfig.url.absoluteString)/functions/v1/ical-feed?token=\(token)"
    }

    // MARK: Storage

    public func uploadObject(bucket: String, path: String, data: Data, contentType: String) async throws {
        try await client.storage.from(bucket).upload(
            path, data: data, options: FileOptions(contentType: contentType, upsert: false))
    }

    // MARK: Mail sources (online-only, direct PostgREST)

    public func fetchMailSources(userId: String) async throws -> [MailSource] {
        try await client.from("mail_sources").select().eq("user_id", value: userId).execute().value
    }

    public func upsertMailSource(_ row: MailSourcePush) async throws {
        try await client.from("mail_sources").upsert(row, onConflict: "id").execute()
    }

    public func deleteMailSource(id: String) async throws {
        try await client.from("mail_sources").delete().eq("id", value: id).execute()
    }

    /// Trigger an immediate digest fetch for one source.
    public func fetchMailNow(sourceId: String) async throws {
        try await client.functions.invoke(
            "fetch-mail-source",
            options: FunctionInvokeOptions(body: ["source_id": sourceId])
        )
    }
}

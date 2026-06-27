import Foundation

/// A per-user mail subscription (`mail_sources` table). Managed online-only via
/// direct PostgREST (not mirrored in the local store), matching the PWA/Android.
public struct MailSource: Codable, Identifiable, Equatable {
    public var id: String
    public var user_id: String?
    public var name: String
    public var url: String?
    public var topic: String?
    public var source_type: String     // "url" | "topic"
    public var enabled: Bool
    public var fetch_time: String?     // "HH:MM:SS"
    public var timezone: String?
    public var output_language: String?
    public var last_fetched_at: String?
    public var last_error: String?

    public init(id: String, user_id: String?, name: String, url: String?, topic: String?,
                source_type: String, enabled: Bool, fetch_time: String?, timezone: String?,
                output_language: String?, last_fetched_at: String? = nil, last_error: String? = nil) {
        self.id = id; self.user_id = user_id; self.name = name; self.url = url; self.topic = topic
        self.source_type = source_type; self.enabled = enabled; self.fetch_time = fetch_time
        self.timezone = timezone; self.output_language = output_language
        self.last_fetched_at = last_fetched_at; self.last_error = last_error
    }
}

/// Writable subset for upsert.
public struct MailSourcePush: Encodable {
    public let id: String
    public let user_id: String
    public let name: String
    public let url: String?
    public let topic: String?
    public let source_type: String
    public let enabled: Bool
    public let fetch_time: String
    public let timezone: String
    public let output_language: String?

    public init(id: String, user_id: String, name: String, url: String?, topic: String?,
                source_type: String, enabled: Bool, fetch_time: String, timezone: String,
                output_language: String?) {
        self.id = id; self.user_id = user_id; self.name = name; self.url = url; self.topic = topic
        self.source_type = source_type; self.enabled = enabled; self.fetch_time = fetch_time
        self.timezone = timezone; self.output_language = output_language
    }
}

import Foundation

/// Supabase project + publishable (frontend-safe) key. SAME project as the PWA
/// and the Android app — see `android-native/.../SupabaseConfig.kt`. The
/// publishable key is meant to ship in clients; row-level security protects data.
public enum SupabaseConfig {
    public static let url = URL(string: "https://jrkwncplngmznfzzqwee.supabase.co")!
    public static let publishableKey = "sb_publishable_UoFQ7p6EPTm0cbqimURGPQ_J1HO_aR-"

    /// Where users manage BYOK AI keys / credits (the PWA settings page).
    public static let webSettingsURL = "https://tatliving.dev/palmvellum/app/settings"
}

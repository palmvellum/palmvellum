package dev.tatliving.palmvellum.organizers.data.sync

/**
 * Supabase project + publishable (frontend-safe) key. Same project as the
 * PWA. The publishable key is meant to live in clients; RLS protects data.
 */
object SupabaseConfig {
    const val URL = "https://jrkwncplngmznfzzqwee.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_UoFQ7p6EPTm0cbqimURGPQ_J1HO_aR-"
}

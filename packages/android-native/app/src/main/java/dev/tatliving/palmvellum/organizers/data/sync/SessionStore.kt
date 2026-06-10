package dev.tatliving.palmvellum.organizers.data.sync

import android.content.Context

/**
 * Persists the Supabase auth session (opt-in cloud sync). When empty, the
 * app runs purely local. SharedPreferences is enough — tokens, not data.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("palm_session", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(v) = prefs.edit().putString("user_id", v).apply()

    var email: String?
        get() = prefs.getString("email", null)
        set(v) = prefs.edit().putString("email", v).apply()

    val isSignedIn: Boolean get() = !accessToken.isNullOrBlank() && !userId.isNullOrBlank()

    fun save(accessToken: String, refreshToken: String, userId: String, email: String?) {
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putString("user_id", userId)
            .putString("email", email)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

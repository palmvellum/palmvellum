package dev.tatliving.palmvellum.organizers.ui.i18n

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tiny runtime i18n for the native app, mirroring the six locales the web app
 * ships (English / Traditional Chinese / Simplified Chinese / Japanese /
 * Korean / Russian).
 *
 * [locale] is Compose state, so any composable that calls [t] recomposes the
 * moment the language changes — no Activity restart, no resource qualifiers.
 * The chosen language is persisted in SharedPreferences and restored on launch.
 *
 * Missing keys fall back to English, then to the raw key, so a half-translated
 * table never crashes or shows a blank.
 */
object I18n {
    /** code -> native display name, in the same order the web language picker uses. */
    val locales: List<Pair<String, String>> = listOf(
        "en" to "English",
        "zh-TW" to "繁體中文",
        "zh-CN" to "简体中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "ru" to "Русский",
    )

    private val tables: Map<String, Map<String, String>> = mapOf(
        "en" to STR_EN,
        "zh-TW" to STR_ZH_TW,
        "zh-CN" to STR_ZH_CN,
        "ja" to STR_JA,
        "ko" to STR_KO,
        "ru" to STR_RU,
    )

    var locale by mutableStateOf("en")
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("palm_i18n", Context.MODE_PRIVATE)
        prefs = p
        locale = p.getString("locale", null) ?: deviceDefault()
    }

    fun setLanguage(code: String) {
        if (!tables.containsKey(code)) return
        locale = code
        prefs?.edit()?.putString("locale", code)?.apply()
    }

    /** Look up [key] in the active table, falling back to English then the key. */
    fun t(key: String): String {
        val active = tables[locale] ?: STR_EN
        return active[key] ?: STR_EN[key] ?: key
    }

    /** [t] with positional formatting — translations use %s / %d / %1$s etc. */
    fun t(key: String, vararg args: Any?): String =
        try { String.format(t(key), *args) } catch (e: Exception) { t(key) }

    /** Best-effort map of the device language onto one of our six locales. */
    private fun deviceDefault(): String {
        val loc = java.util.Locale.getDefault()
        return when (loc.language) {
            "zh" -> if (loc.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "ja" -> "ja"
            "ko" -> "ko"
            "ru" -> "ru"
            else -> "en"
        }
    }
}

package dev.tatliving.palmvellum.organizers.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Tiny GET helper for fetching arbitrary external text (iCal feeds). */
object Net {
    suspend fun getText(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PalmVellum/1.0")
                setRequestProperty("Accept", "text/calendar, text/plain, */*")
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw Exception("HTTP $code: ${text.take(120)}")
                text
            } finally {
                conn.disconnect()
            }
        }
    }
}

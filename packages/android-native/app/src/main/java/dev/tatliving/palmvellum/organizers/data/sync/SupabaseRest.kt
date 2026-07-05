package dev.tatliving.palmvellum.organizers.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Supabase REST client over HttpURLConnection — Auth (email OTP) +
 * PostgREST. Deliberately avoids the supabase-kt/ktor dependency matrix.
 * All calls are suspend + run on Dispatchers.IO.
 */
class SupabaseRest(private val session: SessionStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val base = SupabaseConfig.URL
    private val apikey = SupabaseConfig.PUBLISHABLE_KEY

    private data class Resp(val code: Int, val body: String)

    private fun http(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun authHeaders(): Map<String, String> = buildMap {
        put("apikey", apikey)
        session.accessToken?.let { put("Authorization", "Bearer $it") }
    }

    // ── Auth (email OTP) ────────────────────────────────────────
    suspend fun sendOtp(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("email", email)
            put("create_user", false)
        }.toString()
        val r = http(
            "POST", "$base/auth/v1/otp",
            mapOf("apikey" to apikey),
            body,
        )
        if (r.code in 200..299) Result.success(Unit)
        else Result.failure(Exception(errorMsg(r)))
    }

    suspend fun verifyOtp(email: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("email", email)
            put("token", token)
            put("type", "email")
        }.toString()
        val r = http("POST", "$base/auth/v1/verify", mapOf("apikey" to apikey), body)
        if (r.code !in 200..299) return@withContext Result.failure(Exception(errorMsg(r)))
        runCatching {
            val obj = json.parseToJsonElement(r.body).jsonObject
            val access = obj["access_token"]!!.jsonPrimitive.content
            val refresh = obj["refresh_token"]!!.jsonPrimitive.content
            val user = obj["user"]!!.jsonObject
            val uid = user["id"]!!.jsonPrimitive.content
            val mail = user["email"]?.jsonPrimitive?.content
            session.save(access, refresh, uid, mail)
            uid
        }.fold({ Result.success(it) }, { Result.failure(it) })
    }

    private fun refreshToken(): Boolean {
        val refresh = session.refreshToken ?: return false
        val body = buildJsonObject { put("refresh_token", refresh) }.toString()
        val r = http(
            "POST", "$base/auth/v1/token?grant_type=refresh_token",
            mapOf("apikey" to apikey), body,
        )
        if (r.code !in 200..299) return false
        return runCatching {
            val obj = json.parseToJsonElement(r.body).jsonObject
            session.accessToken = obj["access_token"]!!.jsonPrimitive.content
            session.refreshToken = obj["refresh_token"]!!.jsonPrimitive.content
            true
        }.getOrDefault(false)
    }

    // ── PostgREST ───────────────────────────────────────────────
    suspend fun select(table: String, query: String): Result<JsonArray> = withContext(Dispatchers.IO) {
        var r = http("GET", "$base/rest/v1/$table?$query", authHeaders())
        if (r.code == 401 && refreshToken()) r = http("GET", "$base/rest/v1/$table?$query", authHeaders())
        if (r.code !in 200..299) return@withContext Result.failure(Exception(errorMsg(r)))
        runCatching { json.parseToJsonElement(r.body).jsonArray }
            .fold({ Result.success(it) }, { Result.failure(it) })
    }

    /**
     * Like [select] but pages past PostgREST's hard `db.max_rows` cap
     * (1000 on Supabase), which silently overrides any `limit` in the
     * query. A plain unpaged select returns AT MOST 1000 rows, dropping
     * the rest with no error — so a user with >1000 events would only
     * ever see the first 1000 on-device. Page with `order=id.asc` +
     * `limit`/`offset` until a short page signals the end. Page size is
     * kept below the server cap so a short page reliably means "done"
     * regardless of the project's `db.max_rows` setting.
     */
    suspend fun selectAll(table: String, query: String, pageSize: Int = 500): Result<JsonArray> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<kotlinx.serialization.json.JsonElement>()
            var offset = 0
            while (true) {
                val paged = "$query&order=id.asc&limit=$pageSize&offset=$offset"
                val arr = select(table, paged).getOrElse { return@withContext Result.failure(it) }
                out.addAll(arr)
                if (arr.size < pageSize) break
                offset += pageSize
            }
            Result.success(JsonArray(out))
        }

    /** Upsert (merge on PK) a batch of rows; returns the stored representation. */
    suspend fun upsert(table: String, rows: List<JsonObject>): Result<JsonArray> = withContext(Dispatchers.IO) {
        val payload = JsonArray(rows).toString()
        val headers = authHeaders() + mapOf(
            "Prefer" to "resolution=merge-duplicates,return=representation",
        )
        var r = http("POST", "$base/rest/v1/$table", headers, payload)
        if (r.code == 401 && refreshToken()) r = http("POST", "$base/rest/v1/$table", authHeaders() + mapOf("Prefer" to "resolution=merge-duplicates,return=representation"), payload)
        if (r.code !in 200..299) return@withContext Result.failure(Exception(errorMsg(r)))
        runCatching { json.parseToJsonElement(r.body).jsonArray }
            .fold({ Result.success(it) }, { Result.failure(it) })
    }

    /** Patch (partial update) rows matching a PostgREST filter (e.g. "id=eq.$id"). */
    suspend fun patch(table: String, query: String, row: JsonObject): Result<JsonArray> = withContext(Dispatchers.IO) {
        val payload = row.toString()
        fun headers() = authHeaders() + mapOf("Prefer" to "return=representation")
        var r = http("PATCH", "$base/rest/v1/$table?$query", headers(), payload)
        if (r.code == 401 && refreshToken()) r = http("PATCH", "$base/rest/v1/$table?$query", headers(), payload)
        if (r.code !in 200..299) return@withContext Result.failure(Exception(errorMsg(r)))
        runCatching { json.parseToJsonElement(r.body).jsonArray }
            .fold({ Result.success(it) }, { Result.failure(it) })
    }

    /** Delete rows matching a PostgREST filter (e.g. "id=eq.$id"). */
    suspend fun delete(table: String, query: String): Result<Unit> = withContext(Dispatchers.IO) {
        var r = http("DELETE", "$base/rest/v1/$table?$query", authHeaders())
        if (r.code == 401 && refreshToken()) r = http("DELETE", "$base/rest/v1/$table?$query", authHeaders())
        if (r.code in 200..299) Result.success(Unit)
        else Result.failure(Exception(errorMsg(r)))
    }

    // ── Storage ─────────────────────────────────────────────────
    /**
     * Upload raw bytes to a Storage bucket object (memo-uploads). The bucket
     * is private with RLS keyed on the first path segment = auth.uid(), so the
     * path must be "<user_id>/<file>". POST = create (no overwrite).
     */
    suspend fun uploadObject(
        bucket: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = "$base/storage/v1/object/$bucket/$path"
        val ct = contentType.ifBlank { "application/octet-stream" }
        fun headers() = authHeaders() + mapOf("x-upsert" to "false")
        var r = httpBytes("POST", url, headers(), bytes, ct)
        if (r.code == 401 && refreshToken()) r = httpBytes("POST", url, headers(), bytes, ct)
        if (r.code in 200..299) Result.success(Unit)
        else Result.failure(Exception(errorMsg(r)))
    }

    private fun httpBytes(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        contentType: String,
    ): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMsg(r: Resp): String {
        val snippet = r.body.take(300)
        return "HTTP ${r.code}: $snippet"
    }
}

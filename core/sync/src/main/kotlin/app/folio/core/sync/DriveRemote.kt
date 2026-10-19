package app.folio.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Google Drive v3 as the sync remote, using only the app's private folder (`appDataFolder`,
 * scope `drive.appdata`): invisible in the user's Drive and to other apps, and free.
 *
 * - The key is the file name; `appProperties.h` holds the MD5 of the content as stored
 *   locally, so a listing alone tells the engine what changed.
 * - JSON items are gzip-compressed on upload and inflated on download.
 * - Small uploads are one multipart request; large ones (PDFs, huge pages) use resumable sessions.
 * - [changedSince] polls Drive's change feed cheaply, so other devices' edits are noticed fast.
 * - [token] returns an OAuth access token; `token(true)` must fetch a fresh one (after a 401).
 * - 429 and 5xx responses are retried with backoff.
 */
class DriveRemote(
    private val token: (forceRefresh: Boolean) -> String,
    private val base: String = "https://www.googleapis.com",
    /** Receives one line per request (method, path, status, milliseconds), for diagnosing speed. */
    private val trace: ((String) -> Unit)? = null,
) : RemoteSide {
    private val json = Json { ignoreUnknownKeys = true }

    override fun list(): List<RemoteSide.Entry> {
        val newest = LinkedHashMap<String, Pair<RemoteSide.Entry, String>>() // name → (entry, modifiedTime)
        var pageToken: String? = null
        do {
            val url = "$base/drive/v3/files?spaces=appDataFolder&pageSize=1000" +
                "&fields=" + enc("nextPageToken,files(id,name,appProperties,modifiedTime)") +
                (pageToken?.let { "&pageToken=" + enc(it) } ?: "")
            val body = json.parseToJsonElement(request("GET", url).body.decodeToString()).jsonObject
            for (f in body["files"]?.jsonArray.orEmpty()) {
                val o = f.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val h = o["appProperties"]?.jsonObject?.get("h")?.jsonPrimitive?.contentOrNull ?: ""
                val mod = o["modifiedTime"]?.jsonPrimitive?.contentOrNull ?: ""
                // Two devices may have created the same name at once: use the newest.
                val cur = newest[name]
                if (cur == null || mod > cur.second) newest[name] = RemoteSide.Entry(name, id, h) to mod
            }
            pageToken = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (pageToken != null)
        return newest.values.map { it.first }
    }

    override fun download(e: RemoteSide.Entry): ByteArray {
        val bytes = request("GET", "$base/drive/v3/files/${enc(e.id)}?alt=media").body
        return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
    }

    override fun upload(key: String, bytes: ByteArray, hash: String, existing: RemoteSide.Entry?): RemoteSide.Entry {
        val content = if (key.endsWith(".json")) gzip(bytes) else bytes
        val meta = buildJsonObject {
            if (existing == null) {
                put("name", key)
                putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive("appDataFolder")) }
            }
            putJsonObject("appProperties") { put("h", hash) }
        }
        if (content.size <= SINGLE_REQUEST_MAX) return uploadInOneRequest(key, meta.toString(), content, hash, existing)
        val start = if (existing == null) {
            request("POST", "$base/upload/drive/v3/files?uploadType=resumable", meta.toString().toByteArray(), "application/json; charset=UTF-8",
                extra = mapOf("X-Upload-Content-Type" to "application/octet-stream", "X-Upload-Content-Length" to content.size.toString()))
        } else {
            request("POST", "$base/upload/drive/v3/files/${enc(existing.id)}?uploadType=resumable", meta.toString().toByteArray(), "application/json; charset=UTF-8",
                extra = mapOf("X-HTTP-Method-Override" to "PATCH", "X-Upload-Content-Type" to "application/octet-stream", "X-Upload-Content-Length" to content.size.toString()))
        }
        val session = start.location ?: throw IOException("Drive didn't start an upload session for $key")
        val done = json.parseToJsonElement(request("PUT", session, content, "application/octet-stream").body.decodeToString()).jsonObject
        val id = done["id"]?.jsonPrimitive?.contentOrNull ?: existing?.id ?: throw IOException("Drive didn't return an id for $key")
        return RemoteSide.Entry(key, id, hash)
    }

    /** Small files: metadata and content in one multipart request (one round trip instead of two). */
    private fun uploadInOneRequest(key: String, meta: String, content: ByteArray, hash: String, existing: RemoteSide.Entry?): RemoteSide.Entry {
        val boundary = "folio" + java.util.UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream(content.size + meta.length + 256).apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(content)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val type = "multipart/related; boundary=$boundary"
        val r = if (existing == null) request("POST", "$base/upload/drive/v3/files?uploadType=multipart&fields=id", body, type)
        else request("POST", "$base/upload/drive/v3/files/${enc(existing.id)}?uploadType=multipart&fields=id", body, type, extra = mapOf("X-HTTP-Method-Override" to "PATCH"))
        val id = json.parseToJsonElement(r.body.decodeToString()).jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: existing?.id
            ?: throw IOException("Drive didn't return an id for $key")
        return RemoteSide.Entry(key, id, hash)
    }

    /** A position in Drive's change feed for the app folder; pass it to [changedSince]. */
    fun startPageToken(): String =
        json.parseToJsonElement(request("GET", "$base/drive/v3/changes/startPageToken").body.decodeToString())
            .jsonObject["startPageToken"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Drive didn't return a change token")

    /**
     * Whether anything in the app folder changed after [token] (a tiny request when nothing
     * did), and the token to ask with next time.
     */
    fun changedSince(token: String): Pair<Boolean, String> {
        var t = token
        var any = false
        while (true) {
            val url = "$base/drive/v3/changes?spaces=appDataFolder&pageSize=100&pageToken=" + enc(t) +
                "&fields=" + enc("nextPageToken,newStartPageToken,changes(fileId)")
            val o = json.parseToJsonElement(request("GET", url).body.decodeToString()).jsonObject
            if (o["changes"]?.jsonArray?.isNotEmpty() == true) any = true
            o["newStartPageToken"]?.jsonPrimitive?.contentOrNull?.let { return any to it }
            t = o["nextPageToken"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Drive's change feed ended without a token")
        }
    }

    override fun delete(e: RemoteSide.Entry) {
        request("DELETE", "$base/drive/v3/files/${enc(e.id)}", okCodes = setOf(200, 204, 404))
    }

    // ------------------------------------------------------------------ HTTP

    private class Response(val code: Int, val body: ByteArray, val location: String?)

    private fun request(
        method: String, url: String, body: ByteArray? = null, type: String? = null,
        extra: Map<String, String> = emptyMap(), okCodes: Set<Int> = setOf(200, 201, 204),
    ): Response {
        var refresh = false
        var attempt = 0
        while (true) {
            val t0 = System.nanoTime()
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000; c.readTimeout = 120_000
                c.setRequestProperty("Authorization", "Bearer " + token(refresh))
                extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    type?.let { c.setRequestProperty("Content-Type", it) }
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                trace?.invoke("$method ${URL(url).path} $code ${bytes.size}B ${(System.nanoTime() - t0) / 1_000_000}ms")
                when {
                    code in okCodes -> return Response(code, bytes, c.getHeaderField("Location"))
                    code == 401 && !refresh -> { refresh = true; continue }
                    (code == 429 || code >= 500) && attempt < RETRIES -> { Thread.sleep(500L shl attempt++); continue }
                    else -> throw DriveException(code, "Drive $method failed ($code): " + bytes.decodeToString().take(300))
                }
            } catch (e: IOException) {
                c.disconnect() // a broken connection mustn't go back to the pool
                throw e
            }
            // No disconnect() on success: the connection stays in the keep-alive pool, so the
            // next request skips a new TLS handshake (most of a small request's time).
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    companion object {
        private const val RETRIES = 3
        /** Up to this size an upload is a single request; above it, a resumable session. */
        const val SINGLE_REQUEST_MAX = 4 * 1024 * 1024
    }
}

class DriveException(val code: Int, message: String) : IOException(message)

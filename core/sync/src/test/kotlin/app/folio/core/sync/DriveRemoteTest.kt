package app.folio.core.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tiny stand-in for the Drive v3 endpoints the client uses (list, media download, resumable upload, delete). */
private class FakeDrive {
    class F(val id: String, val name: String, var h: String, var bytes: ByteArray, var mod: Int, val parents: List<String>)
    val files = LinkedHashMap<String, F>()
    private val sessions = HashMap<String, Pair<String?, Map<String, Any?>>>() // session → (existing id, metadata)
    private var counter = 0
    var validToken = "good"
    var requests = 0
    val changeLog = ArrayList<String>() // file ids, one per change
    var multipartUploads = 0
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"
    private val json = Json

    init {
        server.createContext("/") { ex -> try { handle(ex) } catch (e: Throwable) { reply(ex, 500, e.toString().toByteArray()) } }
        server.start()
    }

    private fun query(ex: HttpExchange) = ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun reply(ex: HttpExchange, code: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }

    private fun handle(ex: HttpExchange) {
        requests++
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $validToken") return reply(ex, 401)
        val path = ex.requestURI.path
        val q = query(ex)
        val body = ex.requestBody.readBytes()
        when {
            ex.requestMethod == "GET" && path == "/drive/v3/files" -> {
                require(q["spaces"] == "appDataFolder")
                val all = files.values.toList()
                val start = q["pageToken"]?.toInt() ?: 0
                val page = all.drop(start).take(2) // tiny pages so the client must follow nextPageToken
                val items = page.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","modifiedTime":"${"%05d".format(it.mod)}","appProperties":{"h":"${it.h}"}}""" }
                val next = if (start + 2 < all.size) ""","nextPageToken":"${start + 2}"""" else ""
                reply(ex, 200, """{"files":[$items]$next}""".toByteArray())
            }
            ex.requestMethod == "GET" && path.startsWith("/drive/v3/files/") && q["alt"] == "media" -> {
                val f = files[path.removePrefix("/drive/v3/files/")] ?: return reply(ex, 404)
                reply(ex, 200, f.bytes)
            }
            ex.requestMethod == "GET" && path == "/drive/v3/changes/startPageToken" -> reply(ex, 200, """{"startPageToken":"${changeLog.size}"}""".toByteArray())
            ex.requestMethod == "GET" && path == "/drive/v3/changes" -> {
                require(q["spaces"] == "appDataFolder")
                val from = q.getValue("pageToken").toInt()
                val items = changeLog.drop(from).joinToString(",") { """{"fileId":"$it"}""" }
                reply(ex, 200, """{"changes":[$items],"newStartPageToken":"${changeLog.size}"}""".toByteArray())
            }
            ex.requestMethod == "POST" && path.startsWith("/upload/drive/v3/files") && q["uploadType"] == "multipart" -> {
                multipartUploads++
                val existing = path.removePrefix("/upload/drive/v3/files").removePrefix("/").ifEmpty { null }
                if (existing != null) require(ex.requestHeaders.getFirst("X-HTTP-Method-Override") == "PATCH")
                val boundary = ex.requestHeaders.getFirst("Content-Type").substringAfter("boundary=")
                val raw = String(body, Charsets.ISO_8859_1)
                val parts = raw.split("--$boundary").filter { it.startsWith("\r\n") }.map { it.substringAfter("\r\n\r\n").removeSuffix("\r\n") }
                val meta = json.parseToJsonElement(parts[0]).jsonObject
                val content = parts[1].toByteArray(Charsets.ISO_8859_1)
                val h = meta["appProperties"]?.jsonObject?.get("h")?.jsonPrimitive?.content!!
                val f = if (existing != null) files.getValue(existing).also { it.bytes = content; it.h = h; it.mod = ++counter }
                else F("id${++counter}", meta["name"]!!.jsonPrimitive.content, h, content, counter, meta["parents"]!!.jsonArray.map { it.jsonPrimitive.content }).also { files[it.id] = it }
                changeLog += f.id
                reply(ex, 200, """{"id":"${f.id}"}""".toByteArray())
            }
            ex.requestMethod == "POST" && path.startsWith("/upload/drive/v3/files") && q["uploadType"] == "resumable" -> {
                val existing = path.removePrefix("/upload/drive/v3/files").removePrefix("/").ifEmpty { null }
                if (existing != null) require(ex.requestHeaders.getFirst("X-HTTP-Method-Override") == "PATCH")
                val meta = json.parseToJsonElement(body.decodeToString()).jsonObject
                val m = mapOf(
                    "name" to meta["name"]?.jsonPrimitive?.content,
                    "parents" to meta["parents"]?.jsonArray?.map { it.jsonPrimitive.content },
                    "h" to meta["appProperties"]?.jsonObject?.get("h")?.jsonPrimitive?.content,
                )
                val s = "s${++counter}"
                sessions[s] = existing to m
                reply(ex, 200, headers = mapOf("Location" to "$base/session/$s"))
            }
            ex.requestMethod == "PUT" && path.startsWith("/session/") -> {
                val (existing, m) = sessions.remove(path.removePrefix("/session/")) ?: return reply(ex, 404)
                val f = if (existing != null) files.getValue(existing).also { it.bytes = body; it.h = m["h"] as String; it.mod = ++counter }
                else F("id${++counter}", m["name"] as String, m["h"] as String, body, counter, @Suppress("UNCHECKED_CAST") (m["parents"] as List<String>)).also { files[it.id] = it }
                changeLog += f.id
                reply(ex, 200, """{"id":"${f.id}"}""".toByteArray())
            }
            ex.requestMethod == "DELETE" -> { val id = path.removePrefix("/drive/v3/files/"); if (files.remove(id) != null) changeLog += id; reply(ex, 204) }
            else -> reply(ex, 400)
        }
    }
}

class DriveRemoteTest {
    private val fake = FakeDrive()
    private var refreshes = 0
    private val drive = DriveRemote({ force -> if (force) { refreshes++; "good" } else if (refreshes == 0) "expired" else "good" }, fake.base)
    @AfterTest fun stop() = fake.server.stop(0)

    @Test fun uploadsListsDownloadsUpdatesAndDeletes() {
        val page = """{"schemaVersion":3,"id":"p1","strokes":[]}""".repeat(50).toByteArray()
        val e = drive.upload("n/nb/p/p1.json", page, SyncEngine.md5(page), null)
        assertEquals(1, refreshes, "the expired token was refreshed once")
        val stored = fake.files.getValue(e.id)
        assertEquals(listOf("appDataFolder"), stored.parents)
        assertTrue(stored.bytes.size < page.size && stored.bytes[0] == 0x1f.toByte(), "JSON is stored gzip-compressed")
        assertContentEquals(page, drive.download(e))

        val pdf = "%PDF-1.4 binary".toByteArray()
        drive.upload("n/nb/a/x.pdf", pdf, SyncEngine.md5(pdf), null)
        for (i in 0 until 3) drive.upload("n/nb/p/q$i.json", "{\"i\":$i}".toByteArray(), "h$i", null)
        val listed = drive.list().associateBy { it.key }
        assertEquals(5, listed.size, "all pages of the listing are followed")
        assertEquals(SyncEngine.md5(page), listed.getValue("n/nb/p/p1.json").hash)
        assertContentEquals(pdf, drive.download(listed.getValue("n/nb/a/x.pdf")), "binary attachments are stored as is")

        val v2 = "{\"v\":2}".toByteArray()
        drive.upload("n/nb/p/p1.json", v2, SyncEngine.md5(v2), listed.getValue("n/nb/p/p1.json"))
        assertEquals(5, fake.files.size, "an update replaces, never duplicates")
        assertContentEquals(v2, drive.download(drive.list().first { it.key == "n/nb/p/p1.json" }))

        drive.delete(listed.getValue("n/nb/a/x.pdf"))
        assertEquals(4, drive.list().size)
        drive.delete(listed.getValue("n/nb/a/x.pdf")) // already gone: not an error
    }

    @Test fun smallFilesGoInOneRequestLargeOnesResumably() {
        val small = "{\"s\":1}".toByteArray()
        drive.upload("n/nb/p/s.json", small, SyncEngine.md5(small), null)
        assertEquals(1, fake.multipartUploads)
        val big = ByteArray(DriveRemote.SINGLE_REQUEST_MAX + 10) { (it * 31).toByte() }
        val e = drive.upload("n/nb/a/big.pdf", big, SyncEngine.md5(big), null)
        assertEquals(1, fake.multipartUploads, "a large file used a resumable session")
        assertContentEquals(big, drive.download(e))
        val binary = ByteArray(3000) { (it % 256).toByte() } // every byte value survives the multipart body
        val b = drive.upload("n/nb/a/bin.img", binary, SyncEngine.md5(binary), null)
        assertContentEquals(binary, drive.download(b))
        val v2 = "{\"s\":2}".toByteArray()
        drive.upload("n/nb/p/s.json", v2, SyncEngine.md5(v2), drive.list().first { it.key == "n/nb/p/s.json" })
        assertEquals(3, fake.files.size, "a one-request update replaces, never duplicates")
        assertContentEquals(v2, drive.download(drive.list().first { it.key == "n/nb/p/s.json" }))
    }

    @Test fun theChangeFeedReportsOnlyNewChanges() {
        val t0 = drive.startPageToken()
        val (none, t1) = drive.changedSince(t0)
        assertEquals(false, none)
        val x = "{}".toByteArray()
        drive.upload("n/nb/p/x.json", x, SyncEngine.md5(x), null)
        val (some, t2) = drive.changedSince(t1)
        assertEquals(true, some)
        assertEquals(false, drive.changedSince(t2).first, "nothing new since the last check")
        drive.delete(drive.list().first())
        assertEquals(true, drive.changedSince(t2).first, "a deletion is a change too")
    }

    @Test fun twoDevicesSyncThroughTheFakeDrive() {
        // Plain helper devices (kept simple: state in a map).
        val aFiles = LinkedHashMap<String, ByteArray>(); val bFiles = LinkedHashMap<String, ByteArray>()
        var aBase: Map<String, String> = emptyMap(); var bBase: Map<String, String> = emptyMap()
        fun side(m: MutableMap<String, ByteArray>) = object : LocalSide {
            override fun snapshot() = m.mapValues { SyncEngine.md5(it.value) }
            override fun read(key: String) = m[key]
            override fun write(key: String, bytes: ByteArray) { m[key] = bytes }
            override fun delete(key: String) { m.remove(key) }
            override fun resolve(key: String, mine: ByteArray, theirs: ByteArray) = theirs.also { m[key] = it }
        }
        val aState = object : StateStore { override fun load() = aBase; override fun save(bases: Map<String, String>) { aBase = HashMap(bases) } }
        val bState = object : StateStore { override fun load() = bBase; override fun save(bases: Map<String, String>) { bBase = HashMap(bases) } }
        aFiles["n/1/meta.json"] = "{\"title\":\"Notes\"}".toByteArray()
        aFiles["n/1/p/x.json"] = "{\"strokes\":[1,2,3]}".toByteArray()
        aFiles["n/1/a/z.pdf"] = "%PDF".toByteArray()
        SyncEngine(side(aFiles), drive, aState).sync()
        SyncEngine(side(bFiles), drive, bState).sync()
        assertEquals(aFiles.keys, bFiles.keys)
        for (k in aFiles.keys) assertContentEquals(aFiles[k], bFiles[k], k)
        bFiles.remove("n/1/a/z.pdf"); bFiles["n/1/p/x.json"] = "{\"strokes\":[]}".toByteArray()
        SyncEngine(side(bFiles), drive, bState).sync()
        SyncEngine(side(aFiles), drive, aState).sync()
        assertEquals(bFiles.keys, aFiles.keys)
        assertContentEquals(bFiles["n/1/p/x.json"], aFiles["n/1/p/x.json"])
    }
}

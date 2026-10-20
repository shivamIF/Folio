package app.folio.core.interop

import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Reads Samsung Notes ".sdocx" files (independently reverse-engineered from the
 * user's own notes; see docs/formats/SDOCX.md). Read-only import into Folio.
 *
 * Container: ZIP with note.note, pageIdInfo.dat (ordered page ids),
 * <pageId>.page per page, media/ (PDFs, cached tiles), end_tag.bin.
 * Locked (encrypted) notes are refused: they need the owner's password.
 *
 * Page: little-endian; at 0x16 u32 width (1200) and u32 height.
 * Object: u16 36 + ASCII uuid, i64 time, f64 bbox[4], …, then after the
 * u32 1200 page marker: u32 size1, u16 type (1 = stroke), u32 size2, payload.
 * Stroke payload: 6-byte header (02 …), u16 ?, u16 n, f64 x0, f64 y0,
 * (n-1) × (i16 dx, i16 dy) sign-magnitude in 1/32 units, f32 pressure base +
 * (n-1) × i16 sign-magnitude deltas in 1/4096, … ; it ends with
 * [RGBA] f32 size, u32 0, f32 size (colour absent = default black).
 */
class SamsungNotesImporter(file: File) : AutoCloseable {
    private val zip = SafeZip(file)

    override fun close() = zip.close()

    fun openAttachment(key: String): InputStream = zip.open(key)

    fun read(fallbackTitle: String = "Samsung Notes"): ImportedNotebook {
        val prefix = zip.names.firstOrNull { it.endsWith("pageIdInfo.dat") }?.removeSuffix("pageIdInfo.dat")
            ?: throw FormatException("This file isn't a Samsung Notes note (no pageIdInfo.dat)")
        if (isEncrypted(prefix)) throw FormatException("This note is locked. Unlock it in Samsung Notes, export it again, then import.")
        // Samsung Notes exports are named "<title>_<yyMMdd>_<HHmmss>.sdocx"; the file name is the reliable title.
        val title = titleFromFileName(fallbackTitle)
        val pageIds = utf16Uuids(zip.read(prefix + "pageIdInfo.dat", 4L * 1024 * 1024))
        if (pageIds.isEmpty()) throw FormatException("No pages found in this note")

        val pages = ArrayList<ImportedPage>()
        val pdfs = HashSet<String>()
        var skipped = 0
        for (id in pageIds) {
            val name = prefix + "$id.page"
            if (!zip.has(name)) continue
            val b = zip.read(name, 256L * 1024 * 1024)
            val parsed = parsePage(b)
            skipped += parsed.skipped
            val bg = pdfBackground(b)?.let { (mediaId, pageIndex) -> pdfEntry(prefix, mediaId)?.let { it to pageIndex } }
            if (bg != null) pdfs += bg.first
            pages += if (bg == null) parsed.page else parsed.page.copy(pdfKey = bg.first, pdfPageIndex = bg.second)
        }
        if (pages.isEmpty()) throw FormatException("No readable pages in this note")
        val warnings = ArrayList<String>()
        if (skipped > 0) warnings += "$skipped item(s) such as pictures or attached sticky notes weren't imported yet."
        return ImportedNotebook(title.ifBlank { fallbackTitle }, pages, pdfs, warnings)
    }

    /**
     * The page's PDF background, if any: (media id, 0-based PDF page). Page header: u16 flags
     * at 0x0E; optional blocks after 0x80 (flag 0x1: 32 bytes, 0x10: 4), then colour and a
     * width (4 + 4); with flag 0x100 comes {u16 1, u32 media id, u32 page, 8 zero bytes, u32 w, u32 h}.
     * Checked on every page of the sample notes (two PDFs in one note, pages in sequence).
     */
    private fun pdfBackground(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 0x100) return null
        val flags = le16(b, 0x0e)
        if (flags and 0x100 == 0) return null
        var p = 0x80
        if (flags and 0x1 != 0) p += 32
        if (flags and 0x10 != 0) p += 4
        p += 8
        if (p + 26 > b.size || le16(b, p) != 1) return null
        val mediaId = le32(b, p + 2); val page = le32(b, p + 6)
        if (mediaId < 0 || page < 0 || page > 100_000) return null
        return mediaId to page
    }

    /** media/<id>@….pdf inside the note. */
    private fun pdfEntry(prefix: String, mediaId: Int): String? =
        zip.names.firstOrNull { it.startsWith(prefix + "media/$mediaId@") && it.endsWith(".pdf", ignoreCase = true) }

    private class ParsedPage(val page: ImportedPage, val skipped: Int)

    private fun parsePage(b: ByteArray): ParsedPage {
        val w = if (b.size >= 0x1e) le32(b, 0x16).takeIf { it in 100..20_000 } ?: PAGE_W else PAGE_W
        val h = if (b.size >= 0x1e) le32(b, 0x1a).takeIf { it in 100..200_000 } ?: (w * 1.414f).toInt() else (w * 1.414f).toInt()
        val scale = FOLIO_WIDTH / w
        val objects = asciiUuidOffsets(b)
        val strokes = ArrayList<Stroke>()
        val texts = ArrayList<app.folio.core.model.TextBox>()
        var skipped = 0
        for ((k, start) in objects.withIndex()) {
            val idEnd = start + 38
            val next = if (k + 1 < objects.size) objects[k + 1] else b.size
            val id = String(b, start + 2, 36, Charsets.US_ASCII)
            val stroke = runCatching { strokeAt(b, idEnd, next, scale, id) }.getOrNull()
            if (stroke != null) { strokes += stroke; continue }
            val text = runCatching { textAt(b, idEnd, next, scale) }.getOrNull()
            if (text != null) { texts += text; continue }
            val shape = runCatching { vectorShapeAt(b, idEnd, next, scale, id) }.getOrNull()
            if (shape != null) strokes += shape else skipped++
        }
        return ParsedPage(
            ImportedPage(width = (w * scale).coerceIn(100f, 10_000f), height = (h * scale).coerceIn(100f, 10_000f), strokes = strokes, texts = texts),
            skipped,
        )
    }

    /** Decodes the stroke object whose id ends at [from] (the next object starts at [limit]); null if it isn't a stroke. */
    private fun strokeAt(b: ByteArray, from: Int, limit: Int, scale: Float, id: String): Stroke? {
        // The stroke header follows a "1200, page height" marker. Strokes with extra metadata
        // (handwriting recognition, shape-tool info) put it far into the object, so search all of it.
        val end = minOf(limit, b.size - 32)
        var p = -1; var size2 = 0
        var marker = from
        search@ while (true) {
            marker = indexOfInt(b, PAGE_W, marker, end)
            if (marker < 0) return null
            for (j in marker + 8 until minOf(marker + 72, end)) {
                val s1 = le32(b, j); val type = le16(b, j + 4); val s2 = le32(b, j + 6)
                // Plain strokes have 24 or 28 bytes between the sizes; shape-tool strokes 16–36.
                if (type == 1 && s2 in 1 until s1 && s1 - s2 in 8..64 && b[j + 10].toInt() == 0x02 && j + 10 + s2 <= b.size) {
                    p = j + 10; size2 = s2; break@search
                }
            }
            marker += 4
        }
        val n = le16(b, p + 8)
        if (n !in 1..200_000 || p + 26 + 4L * (n - 1) + 4 + 2L * (n - 1) > p + size2) return null
        val xs = FloatArray(n); val ys = FloatArray(n)
        var x = f64(b, p + 10); var y = f64(b, p + 18)
        if (!x.isFinite() || !y.isFinite()) return null
        xs[0] = x.toFloat(); ys[0] = y.toFloat()
        var q = p + 26
        for (i in 1 until n) {
            x += sm(le16(b, q)) / 32.0; y += sm(le16(b, q + 2)) / 32.0; q += 4
            xs[i] = x.toFloat(); ys[i] = y.toFloat()
        }
        unrotate(b, from, xs, ys)
        val ps = FloatArray(n)
        var pr = java.lang.Float.intBitsToFloat(le32(b, q)).toDouble().takeIf { it.isFinite() } ?: 0.5
        q += 4
        ps[0] = pr.toFloat().coerceIn(0f, 1f)
        for (i in 1 until n) { pr += sm(le16(b, q)) / 4096.0; q += 2; ps[i] = pr.toFloat().coerceIn(0f, 1f) }

        // Trailer around tail = payload + size2. Header byte 4, bit 0x04: the stroke has its own
        // colour, stored as RGBA at tail-6 with the size (f32) at tail-2. Otherwise the size is at
        // tail-6 (repeated at tail+2) and the colour is the default black. Checked on 35,860 strokes.
        val tail = p + size2
        val hasColor = (b[p + 4].toInt() and 0x04) != 0
        val (argb, size) = if (hasColor) {
            val r = b[tail - 6].toLong() and 0xFF; val g = b[tail - 5].toLong() and 0xFF
            val bl = b[tail - 4].toLong() and 0xFF; val a = b[tail - 3].toLong() and 0xFF
            ((a shl 24) or (r shl 16) or (g shl 8) or bl) to java.lang.Float.intBitsToFloat(le32(b, tail - 2))
        } else {
            0xFF000000L to java.lang.Float.intBitsToFloat(le32(b, tail - 6))
        }
        val alpha = (argb ushr 24) and 0xFF
        val highlighter = alpha < 0xF0
        val width = (size.takeIf { it.isFinite() && it > 0.05f && it < 300f } ?: 1.5f) * WIDTH_FACTOR
        return Stroke(
            id = safeId(id), tool = if (highlighter) ToolType.HIGHLIGHTER else ToolType.FOUNTAIN,
            color = argb or 0xFF000000L, width = width * scale,
            x = FloatArray(n) { xs[it] * scale }, y = FloatArray(n) { ys[it] * scale },
            pressure = if (highlighter) FloatArray(n) { 1f } else ps,
            tilt = FloatArray(n), orientation = FloatArray(n), t = IntArray(n),
        )
    }

    /**
     * A text object (type 6): frame = the object's bounding box; then a UTF-16 string
     * (u32 length in chars, then the text) followed by style spans: u32 count, then records
     * {u16 length (excluding itself), u16 kind, …, value at +18}; kind 3 = font size (f32), kind 1 = colour (ARGB).
     * Decoded from one sample (a title box); anything that doesn't parse cleanly is skipped.
     */
    private fun textAt(b: ByteArray, idEnd: Int, limit: Int, scale: Float): app.folio.core.model.TextBox? {
        val end = minOf(limit, b.size - 8)
        val marker = indexOfInt(b, PAGE_W, idEnd, end).takeIf { it >= 0 } ?: return null
        var payload = -1
        for (j in marker + 8 until minOf(marker + 72, end)) {
            val s1 = le32(b, j); val type = le16(b, j + 4); val s2 = le32(b, j + 6)
            if (type == 6 && s2 in 1 until s1 && s1 - s2 in 8..64) { payload = j + 10; break }
        }
        if (payload < 0) return null
        val l = f64(b, idEnd + 8); val t = f64(b, idEnd + 16); val r = f64(b, idEnd + 24); val bt = f64(b, idEnd + 32)
        if (!(l.isFinite() && t.isFinite() && r.isFinite() && bt.isFinite()) || r <= l || bt <= t) return null
        // The text block: u16 6, u32 block length, u32 length in chars, then UTF-16 text that
        // fits inside the block and reads as text (mostly letters, no control characters).
        var i = payload
        var text: String? = null; var after = -1
        while (i + 10 < end) {
            if (le16(b, i) == 6) {
                val block = le32(b, i + 2); val n = le32(b, i + 6)
                if (n in 1..20_000 && block >= 4 + 2 * n && i + 6 + block <= end + 64) {
                    val str = String(b, i + 10, 2 * n, Charsets.UTF_16LE)
                    val letters = str.count { it.isLetterOrDigit() }
                    if (letters >= maxOf(1, n / 3) && str.all { it == '\n' || it == '\t' || it == '\r' || (!it.isISOControl() && !it.isSurrogate()) }) {
                        text = str; after = i + 10 + 2 * n; break
                    }
                }
            }
            i++
        }
        if (text.isNullOrBlank()) return null
        var size = 18f; var color = 0xFF1B1B1FL
        val count = le32(b, after)
        var q = after + 4
        if (count in 1..64) for (k in 0 until count) {
            if (q + 24 > end) break
            val len = le16(b, q); val kind = le16(b, q + 2)
            if (len !in 12..64) break
            when (kind) {
                3 -> java.lang.Float.intBitsToFloat(le32(b, q + 18)).takeIf { it in 4f..400f }?.let { size = it }
                1 -> color = le32(b, q + 18).toLong() and 0xFFFFFFFFL
            }
            q += len + 2 // the length doesn't count its own two bytes
        }
        // Samsung text sizes are points on an A4-wide page (1200 units ≈ 595 pt).
        val fontSize = size * (PAGE_W / 595.28f) * scale
        val pad = 6f
        val body = text.trimEnd()
        return app.folio.core.model.TextBox(
            x = (l * scale).toFloat() + pad, y = (t * scale).toFloat() + pad,
            width = ((r - l) * scale).toFloat() - 2 * pad, height = maxOf(fontSize * 1.3f * (body.count { it == '\n' } + 1), 10f),
            text = body, fontSize = fontSize, color = color or 0xFF000000L,
        )
    }

    /**
     * A shape-tool vector object (type 6, subtype 0x08), e.g. an arrow's line and heads:
     * payload `01 00 01 08`, u32 point count, the points as f64 (x, y), and 13 bytes later
     * the line width (f32). Imported as an even-pressure stroke. Seen in one sample (an arrow).
     */
    private fun vectorShapeAt(b: ByteArray, idEnd: Int, limit: Int, scale: Float, id: String): Stroke? {
        val end = minOf(limit, b.size - 8)
        val marker = indexOfInt(b, PAGE_W, idEnd, end).takeIf { it >= 0 } ?: return null
        var p = -1
        for (j in marker + 8 until minOf(marker + 72, end)) {
            val s1 = le32(b, j); val type = le16(b, j + 4); val s2 = le32(b, j + 6)
            if (type == 6 && s2 in 1 until s1 && s1 - s2 in 8..64) { p = j + 10; break }
        }
        if (p < 0 || p + 8 > end || le16(b, p) != 1 || b[p + 2].toInt() != 1 || b[p + 3].toInt() != 0x08) return null
        val n = le32(b, p + 4)
        val ptsStart = p + 8
        if (n !in 2..5000 || ptsStart + 16L * n + 17 > end) return null
        val xs = FloatArray(n); val ys = FloatArray(n)
        for (i in 0 until n) {
            val x = f64(b, ptsStart + 16 * i); val y = f64(b, ptsStart + 16 * i + 8)
            if (!x.isFinite() || !y.isFinite()) return null
            xs[i] = (x * scale).toFloat(); ys[i] = (y * scale).toFloat()
        }
        val w = java.lang.Float.intBitsToFloat(le32(b, ptsStart + 16 * n + 13)).takeIf { it.isFinite() && it in 0.2f..100f } ?: 2f
        return Stroke(
            id = safeId(id), tool = ToolType.BALLPOINT, color = 0xFF000000L, width = w * scale,
            x = xs, y = ys, pressure = FloatArray(n) { 1f },
            tilt = FloatArray(n), orientation = FloatArray(n), t = IntArray(n),
        )
    }

    /**
     * Strokes rotated after drawing keep their original points plus an angle (f32 degrees,
     * 45 bytes after the id) and their rotated bounding box (f64 ×4, 8 bytes after the id).
     * If the points don't fit that box, rotate them by -angle about the box centre; that made
     * every one of the 367 rotated strokes in the samples fit exactly. Left unchanged otherwise.
     */
    private fun unrotate(b: ByteArray, idEnd: Int, xs: FloatArray, ys: FloatArray) {
        if (idEnd + 49 > b.size) return
        val l = f64(b, idEnd + 8); val t = f64(b, idEnd + 16); val r = f64(b, idEnd + 24); val bt = f64(b, idEnd + 32)
        if (!(l.isFinite() && t.isFinite() && r.isFinite() && bt.isFinite())) return
        fun fits(px: FloatArray, py: FloatArray, tol: Double): Boolean {
            for (i in px.indices) if (px[i] < l - tol || px[i] > r + tol || py[i] < t - tol || py[i] > bt + tol) return false
            return true
        }
        if (fits(xs, ys, 1.5)) return
        val angle = java.lang.Float.intBitsToFloat(le32(b, idEnd + 45))
        if (!angle.isFinite() || kotlin.math.abs(angle) > 720f || angle == 0f) return
        val a = Math.toRadians(-angle.toDouble()); val c = kotlin.math.cos(a); val sn = kotlin.math.sin(a)
        val cx = (l + r) / 2; val cy = (t + bt) / 2
        val rx = FloatArray(xs.size); val ry = FloatArray(ys.size)
        for (i in xs.indices) {
            val dx = xs[i] - cx; val dy = ys[i] - cy
            rx[i] = (cx + dx * c - dy * sn).toFloat(); ry[i] = (cy + dx * sn + dy * c).toFloat()
        }
        if (fits(rx, ry, 2.0)) { rx.copyInto(xs); ry.copyInto(ys) }
    }

    private fun indexOfInt(b: ByteArray, v: Int, from: Int, to: Int): Int {
        val b0 = (v and 0xFF).toByte(); val b1 = ((v ushr 8) and 0xFF).toByte()
        val b2 = ((v ushr 16) and 0xFF).toByte(); val b3 = ((v ushr 24) and 0xFF).toByte()
        var i = maxOf(0, from)
        while (i + 3 < to) {
            if (b[i] == b0 && b[i + 1] == b1 && b[i + 2] == b2 && b[i + 3] == b3) return i
            i++
        }
        return -1
    }

    private fun isEncrypted(prefix: String): Boolean = runCatching {
        // end_tag.bin carries encryption info for locked notes; a password-protected note has no readable pages.
        val tag = zip.read(prefix + "end_tag.bin", 1L shl 20)
        String(tag, Charsets.ISO_8859_1).contains("encrypt", ignoreCase = true)
    }.getOrDefault(false)

    companion object {
        /** Samsung page width in its own units. */
        const val PAGE_W = 1200
        const val FOLIO_WIDTH = 1000f
        /** Samsung pen size → Folio nominal width (tuned visually; see docs/formats/SDOCX.md). */
        var WIDTH_FACTOR = 2.0f

        private fun sm(v: Int) = if (v and 0x8000 != 0) -(v and 0x7FFF) else v
        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, i: Int) = AppleLz4.le32(b, i)
        private fun f64(b: ByteArray, i: Int): Double {
            var v = 0L
            for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
            return java.lang.Double.longBitsToDouble(v)
        }

        private val HEX = "0123456789abcdef".toSet()
        private fun isUuidAscii(b: ByteArray, at: Int): Boolean {
            if (at + 36 > b.size) return false
            for (k in 0 until 36) {
                val c = b[at + k].toInt().toChar()
                if (k == 8 || k == 13 || k == 18 || k == 23) { if (c != '-') return false } else if (c !in HEX) return false
            }
            return true
        }

        /** Offsets of "u16 36 + ASCII uuid" object headers, in file order. */
        fun asciiUuidOffsets(b: ByteArray): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i + 38 <= b.size) {
                if (b[i].toInt() == 0x24 && b[i + 1].toInt() == 0 && isUuidAscii(b, i + 2)) { out += i; i += 38 } else i++
            }
            return out
        }

        /** UTF-16LE uuid strings in file order (page ids in pageIdInfo.dat). */
        fun utf16Uuids(b: ByteArray): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i + 74 <= b.size) {
                if (b[i].toInt() == 0x24 && b[i + 1].toInt() == 0) {
                    val s = runCatching { String(b, i + 2, 72, Charsets.UTF_16LE) }.getOrNull()
                    if (s != null && runCatching { UUID.fromString(s) }.isSuccess) { out += s; i += 74; continue }
                }
                i++
            }
            return out.distinct()
        }

        /** "My_class_notes_260811_134331" → "My class notes"; underscores become spaces. */
        fun titleFromFileName(name: String): String =
            name.removeSuffix(".sdocx").replace(Regex("_\\d{6}_\\d{6}$"), "").replace('_', ' ').trim().ifEmpty { "Samsung Notes" }

        private fun safeId(raw: String) = raw.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { UUID.randomUUID().toString() }
    }
}

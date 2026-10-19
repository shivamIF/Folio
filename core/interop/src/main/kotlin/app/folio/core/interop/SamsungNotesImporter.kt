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
        var skipped = 0
        for (id in pageIds) {
            val name = prefix + "$id.page"
            if (!zip.has(name)) continue
            val b = zip.read(name, 256L * 1024 * 1024)
            val parsed = parsePage(b)
            skipped += parsed.skipped
            pages += parsed.page
        }
        if (pages.isEmpty()) throw FormatException("No readable pages in this note")
        val warnings = ArrayList<String>()
        if (skipped > 0) warnings += "$skipped item(s) such as text boxes, images or shapes weren't imported yet."
        return ImportedNotebook(title.ifBlank { fallbackTitle }, pages, emptySet(), warnings)
    }

    private class ParsedPage(val page: ImportedPage, val skipped: Int)

    private fun parsePage(b: ByteArray): ParsedPage {
        val w = if (b.size >= 0x1e) le32(b, 0x16).takeIf { it in 100..20_000 } ?: PAGE_W else PAGE_W
        val h = if (b.size >= 0x1e) le32(b, 0x1a).takeIf { it in 100..200_000 } ?: (w * 1.414f).toInt() else (w * 1.414f).toInt()
        val scale = FOLIO_WIDTH / w
        val objects = asciiUuidOffsets(b)
        val strokes = ArrayList<Stroke>()
        var skipped = 0
        for ((k, start) in objects.withIndex()) {
            val idEnd = start + 38
            val next = if (k + 1 < objects.size) objects[k + 1] else b.size
            val stroke = runCatching { strokeAt(b, idEnd, next, scale, String(b, start + 2, 36, Charsets.US_ASCII)) }.getOrNull()
            if (stroke != null) strokes += stroke else skipped++
        }
        return ParsedPage(
            ImportedPage(width = (w * scale).coerceIn(100f, 10_000f), height = (h * scale).coerceIn(100f, 10_000f), strokes = strokes),
            skipped,
        )
    }

    /** Decodes the stroke object whose id ends at [from]; null if it isn't a stroke. */
    private fun strokeAt(b: ByteArray, from: Int, limit: Int, scale: Float, id: String): Stroke? {
        val end = minOf(limit, from + 1200, b.size - 32)
        var marker = -1
        for (i in from until end) if (le32(b, i) == PAGE_W) { marker = i; break }
        if (marker < 0) return null
        var p = -1; var size2 = 0
        for (j in marker + 8 until minOf(marker + 72, end)) {
            val s1 = le32(b, j); val type = le16(b, j + 4); val s2 = le32(b, j + 6)
            if (type == 1 && s2 in 1 until s1 && (s1 - s2 == 24 || s1 - s2 == 28) && b[j + 10].toInt() == 0x02) { p = j + 10; size2 = s2; break }
        }
        if (p < 0 || p + size2 > b.size) return null
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
        val ps = FloatArray(n)
        var pr = java.lang.Float.intBitsToFloat(le32(b, q)).toDouble().takeIf { it.isFinite() } ?: 0.5
        q += 4
        ps[0] = pr.toFloat().coerceIn(0f, 1f)
        for (i in 1 until n) { pr += sm(le16(b, q)) / 4096.0; q += 2; ps[i] = pr.toFloat().coerceIn(0f, 1f) }

        // Trailer: [RGBA] f32 size, u32 0, f32 size. A repeated size at (end-6, end+2) means no colour.
        val tail = p + size2
        val (argb, size) = if (tail + 6 <= b.size && le32(b, tail - 6) == le32(b, tail + 2) && le32(b, tail - 2) == 0) {
            0xFF000000L to java.lang.Float.intBitsToFloat(le32(b, tail - 6))
        } else {
            val r = b[tail - 6].toLong() and 0xFF; val g = b[tail - 5].toLong() and 0xFF
            val bl = b[tail - 4].toLong() and 0xFF; val a = b[tail - 3].toLong() and 0xFF
            ((a shl 24) or (r shl 16) or (g shl 8) or bl) to java.lang.Float.intBitsToFloat(le32(b, tail - 2))
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

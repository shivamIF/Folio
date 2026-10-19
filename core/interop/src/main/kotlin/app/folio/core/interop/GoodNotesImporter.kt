package app.folio.core.interop

import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A page produced by an importer, before attachments are stored. */
data class ImportedPage(
    val width: Float,
    val height: Float,
    val strokes: List<Stroke>,
    /** Background PDF page: key of an attachment in [ImportedNotebook.attachmentKeys] and a 0-based page index. */
    val pdfKey: String? = null,
    val pdfPageIndex: Int = 0,
)

data class ImportedNotebook(
    val title: String,
    val pages: List<ImportedPage>,
    /** PDF attachments the pages reference, by key; open them with the importer's openAttachment. */
    val attachmentKeys: Set<String>,
    /** Human-readable notes about content that could not be brought over. */
    val warnings: List<String>,
)

/**
 * Reads GoodNotes 6 ".goodnotes" documents (independently reverse-engineered
 * from the user's own files; see docs/formats/GOODNOTES.md).
 *
 * Layout: a ZIP of length-prefixed protobuf records.
 * - index.events.pb: document (30), pages (2), page slots with fractional
 *   order keys (54), attachments (6), text boxes (130/133)
 * - notes/<slot id + 1>: per-page element log; a header record per element
 *   (field 3 = 1 marks a deletion) and content records (field 7)
 * - stroke geometry: Apple-LZ4-compressed "tpl" image
 *   vuA(v)A(S(uu))A(S(uuuu))vA(f): version, width (f32 bits), segment kinds
 *   (0 move, 1 quadratic), move points, quad control+end points, ?, widths
 * Coordinates are 132 units per inch.
 */
class GoodNotesImporter(file: File) : AutoCloseable {
    private val zip = SafeZip(file)

    override fun close() = zip.close()

    fun openAttachment(key: String): InputStream = zip.open(key)

    fun read(): ImportedNotebook {
        if (!zip.has("index.events.pb")) throw FormatException("This file isn't a GoodNotes 6 notebook (no index.events.pb)")
        val warnings = ArrayList<String>()
        val events = Proto.stream(zip.read("index.events.pb"))

        var title = "Imported notebook"
        val pages = HashMap<String, GnPage>()
        val slots = ArrayList<Slot>()
        val attachmentFiles = HashMap<String, String>() // attachment record id → zip entry
        var textBoxes = 0
        if (zip.has("index.attachments.pb")) {
            for (r in Proto.stream(zip.read("index.attachments.pb"))) {
                val id = r.string(1) ?: continue; val path = r.string(2) ?: continue
                attachmentFiles[id] = path
            }
        }
        for (r in events) {
            r.msg(30)?.let { doc -> doc.msg(2)?.string(1)?.takeIf { it.isNotBlank() }?.let { title = it } }
            r.msg(2)?.let { p ->
                val id = p.string(2) ?: return@let
                val size = p.msg(8)
                pages[id] = GnPage(
                    attachment = p.string(4), pdfPage = p.int(5)?.toInt(),
                    width = size?.float(1) ?: DEFAULT_W, height = size?.float(2) ?: DEFAULT_H,
                )
            }
            r.msg(6)?.let { a ->
                val id = a.string(1); val file = a.string(2)
                if (id != null && file != null) attachmentFiles.putIfAbsent(id, "attachments/$file")
            }
            r.msg(54)?.let { s ->
                val slotId = s.string(2) ?: return@let
                val pageId = s.msg(3)?.string(1) ?: return@let
                val key = s.msg(4)?.string(1) ?: ""
                slots += Slot(key, pageId, slotId)
            }
            if (r.has(130)) textBoxes++
        }
        if (slots.isEmpty()) throw FormatException("No pages found in this GoodNotes file")

        val out = ArrayList<ImportedPage>()
        val usedAttachments = HashSet<String>()
        var images = 0; var otherElements = 0; var shapesSkipped = 0
        for (slot in slots.sortedBy { it.key }) {
            val page = pages[slot.pageId] ?: continue
            val scale = UNITS_PER_GN
            val strokes = ArrayList<Stroke>()
            val notesName = "notes/" + plusOne(slot.slotId)
            if (zip.has(notesName)) {
                val elements = readElements(zip.read(notesName))
                images += elements.images; otherElements += elements.unsupported; shapesSkipped += elements.shapes
                for (s in elements.strokes) strokes += s.scaled(scale)
            }
            val att = page.attachment?.let { attachmentFiles[it] }?.takeIf { zip.has(it) && isPdf(it) }
            if (att != null) usedAttachments += att
            out += ImportedPage(
                width = (page.width * scale).coerceIn(100f, 10_000f),
                height = (page.height * scale).coerceIn(100f, 10_000f),
                strokes = strokes,
                pdfKey = att,
                pdfPageIndex = ((page.pdfPage ?: 1) - 1).coerceAtLeast(0),
            )
        }
        if (textBoxes > 0) warnings += "$textBoxes typed text box(es) weren't imported (Folio has no text boxes yet)."
        if (images > 0) warnings += "$images image(s) weren't imported (Folio has no images on pages yet)."
        if (shapesSkipped > 0) warnings += "$shapesSkipped shape(s) such as rectangles or ellipses weren't imported yet."
        if (otherElements > 0) warnings += "$otherElements other item(s) of an unknown kind were skipped."
        return ImportedNotebook(title, out, usedAttachments, warnings)
    }

    private fun isPdf(entry: String): Boolean = runCatching {
        zip.open(entry).use { s -> val h = ByteArray(5); s.read(h) == 5 && String(h, Charsets.US_ASCII) == "%PDF-" }
    }.getOrDefault(false)

    private class GnPage(val attachment: String?, val pdfPage: Int?, val width: Float, val height: Float)
    private class Slot(val key: String, val pageId: String, val slotId: String)
    private class Elements(val strokes: List<Stroke>, val images: Int, val unsupported: Int, val shapes: Int = 0)

    /** Replays a page's element log: later headers win; deleted elements are dropped. */
    private fun readElements(bytes: ByteArray): Elements {
        val records = Proto.stream(bytes)
        val deleted = HashMap<String, Boolean>()
        // Content records carry only field 7; everything with an id in field 1 is a header.
        fun isContent(r: Proto) = r.has(7) && !r.has(1)
        for (r in records) {
            if (isContent(r)) continue
            val id = r.first(1)?.takeIf { it.wireType == 2 }?.string ?: continue
            deleted[id] = r.int(3) == 1L
        }
        val strokes = ArrayList<Stroke>(); var images = 0; var unsupported = 0; var shapes = 0
        for (r in records) {
            if (!isContent(r)) continue
            val el = r.msg(7) ?: continue
            val id = el.string(1) ?: continue
            if (deleted[id] == true) continue
            when (el.int(3)?.toInt() ?: 0) {
                0 -> {
                    val color = colorOf(el.msg(4))
                    val blob = el.first(2)?.bytes
                    val curve = blob?.let { b -> runCatching { strokeFrom(id, Tpl.read(AppleLz4.decode(b)), color) }.getOrNull() }
                    when {
                        // Freehand ink: quadratic-curve path in the compressed blob.
                        !curve.isNullOrEmpty() -> strokes += curve
                        // Straightened / vector ink (most highlighter marks): geometry in field 9.
                        el.msg(9) != null -> when (val shape = shapeFrom(id, el.msg(9)!!, color)) {
                            null -> shapes++
                            else -> strokes += shape
                        }
                        curve == null -> unsupported++
                        else -> Unit // valid but empty
                    }
                }
                5 -> images++
                1 -> Unit // empty placeholder element, nothing to draw
                else -> unsupported++
            }
        }
        return Elements(strokes, images, unsupported, shapes)
    }

    /** RGBA floats in fields 1..4 (missing = 0). Translucent colours are highlighters. */
    private fun colorOf(c: Proto?): Pair<Long, Boolean> {
        fun ch(n: Int) = ((c?.float(n) ?: 0f).coerceIn(0f, 1f) * 255).roundToInt().toLong()
        val a = c?.float(4) ?: 1f
        val argb = (0xFFL shl 24) or (ch(1) shl 16) or (ch(2) shl 8) or ch(3)
        return argb to (a < 0.95f)
    }

    private fun strokeFrom(id: String, img: Tpl.Image, color: Pair<Long, Boolean>): List<Stroke>? {
        if (!img.format.startsWith("vuA(v)A(S(uu))A(S(uuuu))")) return null
        val v = img.values
        val width = (v[1] as Tpl.Scalar).float32.takeIf { it.isFinite() && it > 0f && it < 500f } ?: 2f
        val kinds = (v[2] as Tpl.Arr).items.map { (it as Tpl.Scalar).bits.toInt() }
        val moves = (v[3] as Tpl.Arr).items.map { s -> (s as Tpl.Struct).members.map { (it as Tpl.Scalar).float32 } }
        val quads = (v[4] as Tpl.Arr).items.map { s -> (s as Tpl.Struct).members.map { (it as Tpl.Scalar).float32 } }
        val pieces = ArrayList<Stroke>()
        var mi = 0; var qi = 0
        var xs = ArrayList<Float>(); var ys = ArrayList<Float>()
        fun flush() {
            if (xs.isNotEmpty()) pieces += makeStroke(if (pieces.isEmpty()) id else "$id-${pieces.size}", xs, ys, width, color)
            xs = ArrayList(); ys = ArrayList()
        }
        for (k in kinds) when (k) {
            0 -> {
                flush()
                val p = moves.getOrNull(mi++) ?: return null
                xs += p[0]; ys += p[1]
            }
            1 -> {
                val q = quads.getOrNull(qi++) ?: return null
                if (xs.isEmpty()) return null
                val x0 = xs.last(); val y0 = ys.last()
                val steps = (hypot(q[2] - x0, q[3] - y0) / 1.5f).toInt().coerceIn(2, 24)
                for (s in 1..steps) {
                    val t = s.toFloat() / steps; val u = 1 - t
                    xs += u * u * x0 + 2 * u * t * q[0] + t * t * q[2]
                    ys += u * u * y0 + 2 * u * t * q[1] + t * t * q[3]
                }
            }
            else -> return null
        }
        flush()
        return pieces
    }

    /**
     * Vector geometry in field 9: 9.1 = polyline { 1: point, 1: point, … }, 9.2 = curve
     * through three points; width in 9.15. Other kinds (likely rectangles,
     * ellipses) return null and are reported, not guessed.
     */
    private fun shapeFrom(id: String, g: Proto, color: Pair<Long, Boolean>): List<Stroke>? {
        val width = g.float(15)?.takeIf { it.isFinite() && it > 0f && it < 500f } ?: 2f
        fun pt(p: Proto?) = p?.let { (it.float(1) ?: 0f) to (it.float(2) ?: 0f) }
        // 9.1 is a polyline message whose repeated field 1 holds the points.
        val poly = g.msg(1)?.all(1)?.mapNotNull { f -> pt(runCatching { f.message() }.getOrNull()) }.orEmpty()
        val curve = g.msg(2)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        fun lineTo(x: Float, y: Float) {
            if (xs.isEmpty()) { xs += x; ys += y; return }
            val x0 = xs.last(); val y0 = ys.last()
            val steps = (hypot(x - x0, y - y0) / 4f).toInt().coerceIn(1, 200)
            for (s in 1..steps) { val t = s.toFloat() / steps; xs += x0 + (x - x0) * t; ys += y0 + (y - y0) * t }
        }
        when {
            poly.size >= 2 -> poly.forEach { (x, y) -> lineTo(x, y) }
            curve != null -> {
                val a = pt(curve.msg(1)) ?: return null; val m = pt(curve.msg(2)) ?: return null; val b = pt(curve.msg(3)) ?: return null
                // Quadratic through the middle point at t = 0.5.
                val cx = 2 * m.first - (a.first + b.first) / 2; val cy = 2 * m.second - (a.second + b.second) / 2
                xs += a.first; ys += a.second
                for (s in 1..24) {
                    val t = s / 24f; val u = 1 - t
                    xs += u * u * a.first + 2 * u * t * cx + t * t * b.first
                    ys += u * u * a.second + 2 * u * t * cy + t * t * b.second
                }
            }
            else -> return null
        }
        return listOf(makeStroke(id, xs, ys, width, color))
    }

    private fun makeStroke(id: String, xs: List<Float>, ys: List<Float>, width: Float, color: Pair<Long, Boolean>): Stroke {
        val n = xs.size
        return Stroke(
            id = stableId(id), tool = if (color.second) ToolType.HIGHLIGHTER else ToolType.BALLPOINT,
            color = color.first, width = width,
            x = xs.toFloatArray(), y = ys.toFloatArray(), pressure = FloatArray(n) { 1f },
            tilt = FloatArray(n), orientation = FloatArray(n), t = IntArray(n),
        )
    }

    companion object {
        /** GoodNotes units are 132 per inch; Folio has 1000 units per 210 mm (A4 width). */
        const val UNITS_PER_GN = (72f / 132f) / (595.2756f / 1000f)
        private const val DEFAULT_W = 1093f
        private const val DEFAULT_H = 1545f

        /** The notes file of a page slot is named after the slot id plus one. */
        fun plusOne(uuid: String): String = runCatching {
            val u = UUID.fromString(uuid)
            var lo = u.leastSignificantBits + 1; var hi = u.mostSignificantBits
            if (lo == 0L) hi += 1
            UUID(hi, lo).toString().uppercase()
        }.getOrDefault(uuid)

        /** Folio ids must be [A-Za-z0-9-]{1,64}. */
        private fun stableId(raw: String) = raw.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { UUID.randomUUID().toString() }

        private fun Stroke.scaled(s: Float) = Stroke(
            id, tool, color, width * s, FloatArray(size) { x[it] * s }, FloatArray(size) { y[it] * s },
            pressure, tilt, orientation, t, startedAt,
        )
    }
}

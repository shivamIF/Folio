package app.folio.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.UUID

/**
 * Newest on-disk schema this build reads. Bump together with a migration.
 *
 * Files are written with the lowest schema their content needs ([Page.requiredSchema]), not
 * always this one, so pages and libraries without schema-6 features still open in schema-5
 * builds (ADR-11).
 */
const val SCHEMA_VERSION = 6

/** The schema written for content that uses nothing newer. */
const val BASE_SCHEMA = 5

fun newId(): String = UUID.randomUUID().toString()

/** ARGB colour packed into a Long so JSON stays unsigned-safe across platforms. */
typealias Argb = Long

@Serializable
enum class PaperTemplate { BLANK, RULED, GRID, DOTTED }

/**
 * Standard paper sizes. All share one physical scale (A4 width = 1000 units),
 * so a pen width means the same physical thickness on every size.
 */
enum class PageFormat(val label: String, val widthMm: Float, val heightMm: Float) {
    A4("A4", 210f, 297f),
    A5("A5", 148f, 210f),
    LETTER("Letter", 215.9f, 279.4f),
    LEGAL("Legal", 215.9f, 355.6f);

    /** (width, height) in page units for the given orientation. */
    fun size(landscape: Boolean): Pair<Float, Float> {
        val w = widthMm * UNITS_PER_MM
        val h = heightMm * UNITS_PER_MM
        return if (landscape) h to w else w to h
    }

    companion object {
        const val UNITS_PER_MM = 1000f / 210f

        /** Recognises a stored size (either orientation), or null for custom sizes. */
        fun match(width: Float, height: Float): Pair<PageFormat, Boolean>? {
            for (f in entries) for (land in listOf(false, true)) {
                val (w, h) = f.size(land)
                if (kotlin.math.abs(w - width) < 0.5f && kotlin.math.abs(h - height) < 0.5f) return f to land
            }
            return null
        }
    }
}

@Serializable
data class Cover(
    val color: Argb = 0xFF3D5A80,
    val style: CoverStyle = CoverStyle.PLAIN,
)

@Serializable
enum class CoverStyle { PLAIN, STRIPE, CORNER }

/**
 * Glyph drawn on a folder. Stored by name; the app maps each one to its own artwork.
 * A name this build doesn't know (added by a newer one) reads as [NONE].
 */
@Serializable(with = FolderIconSerializer::class)
enum class FolderIcon {
    NONE, BOOK, NOTES, SCHOOL, INSTITUTION, LAW, GLOBE, HISTORY, ETHICS, ECONOMY, CHART,
    SCIENCE, BIOLOGY, MATH, FUNCTIONS, CODE, LANGUAGE, PSYCHOLOGY, IDEA, COMPASS, ART,
    MUSIC, WORK, HOME, HEART, STAR, SMILE,
}

internal object FolderIconSerializer : KSerializer<FolderIcon> {
    override val descriptor = PrimitiveSerialDescriptor("app.folio.FolderIcon", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: FolderIcon) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): FolderIcon =
        decoder.decodeString().let { n -> FolderIcon.entries.firstOrNull { it.name == n } } ?: FolderIcon.NONE
}

/**
 * [icon], [favorite] and [deletedAt] were added without a schema bump: they are optional with
 * defaults, so files from before them load unchanged, and an older schema-5 build still opens
 * new files (it ignores the three fields). Every change to a folder, starring and trashing
 * included, moves [modifiedAt] on, because sync merges folders by newest [modifiedAt].
 */
@Serializable
data class Folder(
    val id: String = newId(),
    val name: String,
    val parentId: String? = null,
    val color: Argb = 0xFF98C1D9,
    val createdAt: Long,
    val modifiedAt: Long = createdAt,
    val icon: FolderIcon = FolderIcon.NONE,
    val favorite: Boolean = false,
    /** Set when moved to Trash (epoch ms); its subfolders and notebooks go with it. Null for live folders. */
    val deletedAt: Long? = null,
)

/**
 * Notebook metadata. Kept small so the library can be rendered without
 * loading any page content.
 */
@Serializable
data class NotebookMeta(
    val id: String = newId(),
    val title: String,
    val folderId: String? = null,
    val cover: Cover = Cover(),
    val paper: PaperTemplate = PaperTemplate.RULED,
    val pageWidth: Float = PAGE_WIDTH,
    val pageHeight: Float = PAGE_HEIGHT,
    val pageIds: List<String> = emptyList(),
    val createdAt: Long,
    val modifiedAt: Long = createdAt,
    val favorite: Boolean = false,
    /** Set when moved to Trash (epoch ms); null for live notebooks. Absent in older files. */
    val deletedAt: Long? = null,
    /** Created by importing a PDF (the library shows its first page instead of a cover). */
    val fromPdf: Boolean = false,
    /**
     * Bookmarked pages (ids), from the editor's page sorter. [bookmarks], [pageColor],
     * [darkAdjust] and [density] were added without a schema bump, like [Folder.icon]: they
     * are optional with defaults, so a schema-5 build still reads the library (it ignores
     * them, and drops them if it rewrites the notebook's entry).
     */
    val bookmarks: List<String> = emptyList(),
    /** Page background colour (Page settings); null = the paper's own white. */
    val pageColor: Argb? = null,
    /** "Adjust colour in Dark mode": dark pages with lightened ink while the app is dark. */
    val darkAdjust: Boolean = true,
    /** Line spacing of ruled, grid and dotted paper (Note style). */
    val density: PaperDensity = PaperDensity.NORMAL,
) {
    companion object {
        /** Page units; roughly A4 at 120 units per inch. */
        const val PAGE_WIDTH = 1000f
        const val PAGE_HEIGHT = 1414f
    }
}

@Serializable
data class LibraryIndex(
    val schemaVersion: Int = BASE_SCHEMA,
    val folders: List<Folder> = emptyList(),
    val notebooks: List<NotebookMeta> = emptyList(),
) {
    /** Ids of folders that are in Trash, directly or because an ancestor is. */
    fun trashedFolderIds(): Set<String> {
        val byId = folders.associateBy { it.id }
        val out = HashSet<String>()
        for (f in folders) {
            var cur: Folder? = f
            var steps = 0
            // The step limit guards against a parent cycle in a damaged file.
            while (cur != null && steps++ <= folders.size) {
                if (cur.deletedAt != null) { out += f.id; break }
                cur = cur.parentId?.let(byId::get)
            }
        }
        return out
    }

    /** [rootId] and every folder below it. */
    fun descendantFolderIds(rootId: String): Set<String> {
        val out = linkedSetOf(rootId)
        var grew = true
        while (grew) grew = out.addAll(folders.filter { it.parentId in out }.map { it.id })
        return out
    }
}

/** Line spacing of ruled, grid and dotted paper. Unknown names (from newer builds) read as [NORMAL]. */
@Serializable(with = PaperDensitySerializer::class)
enum class PaperDensity(val factor: Float) { NORMAL(1f), NARROW(0.75f), WIDE(1.35f) }

internal object PaperDensitySerializer : KSerializer<PaperDensity> {
    override val descriptor = PrimitiveSerialDescriptor("app.folio.PaperDensity", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PaperDensity) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): PaperDensity =
        decoder.decodeString().let { n -> PaperDensity.entries.firstOrNull { it.name == n } } ?: PaperDensity.NORMAL
}

/**
 * Persisted by name. FINELINER and MARKER arrived in schema 4, AIRBRUSH in schema 5, TAPE in
 * schema 6 (a page holding tape is written as schema 6, so older builds refuse it cleanly).
 *
 * Calligraphy, crayon and watercolour existed only in test builds and were withdrawn; strokes
 * saved with them load as the nearest remaining tool (the [JsonNames] aliases).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ToolType {
    @JsonNames("CALLIGRAPHY") FOUNTAIN, BALLPOINT, @JsonNames("CRAYON") PENCIL, BRUSH,
    @JsonNames("WATERCOLOR") HIGHLIGHTER, FINELINER, MARKER, AIRBRUSH, TAPE;

    /**
     * Furthest the drawn ink can reach from the centreline, in nominal widths (a tilted pencil
     * spreads wide; square caps reach their corners). Used for bounds and hit-testing; the brush
     * engine's BrushSpec.reach is tested to match. Not persisted.
     */
    val inkReach: Float get() = when (this) {
        PENCIL -> 1.6f
        AIRBRUSH -> 1.0f
        BRUSH -> 0.85f
        MARKER, HIGHLIGHTER, TAPE -> 0.71f
        FOUNTAIN, BALLPOINT, FINELINER -> 0.5f
    }

    val isHighlighter get() = this == HIGHLIGHTER
}

/** How a line is drawn along its path (pen popup's line pills). */
@Serializable(with = LineStyleSerializer::class)
enum class LineStyle { SOLID, DASHED, DOTTED }

internal object LineStyleSerializer : KSerializer<LineStyle> {
    override val descriptor = PrimitiveSerialDescriptor("app.folio.LineStyle", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LineStyle) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): LineStyle =
        decoder.decodeString().let { n -> LineStyle.entries.firstOrNull { it.name == n } } ?: LineStyle.SOLID
}

/**
 * A stroke's look beyond tool, colour and width (schema 6). [DEFAULT] is how every stroke
 * before schema 6 looks, and is never written to disk.
 *
 * - [line]: dashed or dotted lines (pens);
 * - [fixedWidth]: ignore pressure (the "Fixed thickness" pill);
 * - [roundTip]: the highlighter's round tip instead of its chisel;
 * - [pattern]: tape pattern, 0 = plain colour, 1–9 = Folio's procedural patterns;
 * - [peeled]: tape tapped open, so what's under it shows through.
 */
data class StrokeLook(
    val line: LineStyle = LineStyle.SOLID,
    val fixedWidth: Boolean = false,
    val roundTip: Boolean = false,
    val pattern: Int = 0,
    val peeled: Boolean = false,
) {
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = StrokeLook()
        const val PATTERNS = 9
    }
}

/**
 * One handwriting stroke in page coordinates. Per-point channels are stored as
 * parallel primitive arrays: compact in memory, cheap to serialise and easy to
 * map onto other formats.
 *
 * [tilt] is radians from perpendicular (0 = upright) and [orientation] is the
 * radians azimuth reported by Android; both are 0 when the device doesn't report them.
 * [t] is milliseconds since [startedAt], which allows stroke replay.
 *
 * Stored packed (schema 2); see [StrokeSerializer].
 */
@Serializable(with = StrokeSerializer::class)
class Stroke(
    val id: String = newId(),
    val tool: ToolType,
    val color: Argb,
    /** Nominal width in page units at full pressure. */
    val width: Float,
    val x: FloatArray,
    val y: FloatArray,
    val pressure: FloatArray,
    val tilt: FloatArray,
    val orientation: FloatArray,
    val t: IntArray,
    val startedAt: Long = 0,
    /** Line style, fixed width, tip, tape pattern and state (schema 6). */
    val look: StrokeLook = StrokeLook.DEFAULT,
) {
    init {
        val n = x.size
        require(y.size == n && pressure.size == n && tilt.size == n && orientation.size == n && t.size == n) {
            "stroke channels must have equal length"
        }
    }

    val size: Int get() = x.size

    /** Strokes are immutable once built, so the bounds are computed once (not serialised). */
    private var boundsCache: Rect? = null

    /**
     * Owned by the platform renderer (e.g. the Android outline Path), computed once per
     * stroke and shared by its render threads. Never serialised or read by core code.
     */
    @Volatile
    @JvmField
    var renderCache: Any? = null

    fun bounds(): Rect = boundsCache ?: computeBounds().also { boundsCache = it }

    private fun computeBounds(): Rect {
        if (size == 0) return Rect.EMPTY
        var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until size) {
            if (x[i] < l) l = x[i]; if (x[i] > r) r = x[i]
            if (y[i] < tp) tp = y[i]; if (y[i] > b) b = y[i]
        }
        val pad = width * maxOf(1f, tool.inkReach)
        return Rect(l - pad, tp - pad, r + pad, b + pad)
    }

    /**
     * Scales around (ax, ay) and then translates. Line width scales too, so a
     * resized selection keeps its proportions. [newId] gives the copy a fresh
     * identity (used for duplicates).
     */
    fun transformed(scale: Float, ax: Float, ay: Float, dx: Float, dy: Float, newId: Boolean = false): Stroke = Stroke(
        if (newId) newId() else id, tool, color, width * scale,
        FloatArray(size) { ax + (x[it] - ax) * scale + dx }, FloatArray(size) { ay + (y[it] - ay) * scale + dy },
        pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt, look,
    )

    /**
     * Rotated by [degrees] (clockwise on the page, y down) around ([cx], [cy]). Orientation
     * (the pen's azimuth) turns with it, so a tilted pencil keeps its shading direction.
     */
    fun rotated(degrees: Float, cx: Float, cy: Float): Stroke {
        if (degrees == 0f) return this
        val a = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val rad = a.toFloat()
        return Stroke(
            id, tool, color, width,
            FloatArray(size) { cx + (x[it] - cx) * c - (y[it] - cy) * s }, FloatArray(size) { cy + (x[it] - cx) * s + (y[it] - cy) * c },
            pressure.copyOf(), tilt.copyOf(), FloatArray(size) { orientation[it] + rad }, t.copyOf(), startedAt, look,
        )
    }

    fun recolored(newColor: Argb): Stroke = Stroke(
        id, tool, newColor, width, x.copyOf(), y.copyOf(), pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt, look,
    )

    /** Same path in a new colour and width (Change style). A highlighter keeps its translucency. */
    fun restyled(newColor: Argb, newWidth: Float): Stroke = Stroke(
        id, tool, if (tool == ToolType.HIGHLIGHTER) (newColor and 0xFFFFFF) or (color and 0xFF000000) else newColor, newWidth,
        x.copyOf(), y.copyOf(), pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt, look,
    )

    fun withLook(newLook: StrokeLook): Stroke = Stroke(
        id, tool, color, width, x.copyOf(), y.copyOf(), pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt, newLook,
    )

    fun translated(dx: Float, dy: Float): Stroke = Stroke(
        id, tool, color, width,
        FloatArray(size) { x[it] + dx }, FloatArray(size) { y[it] + dy },
        pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt, look,
    )

    /** True if this stroke needs schema 6 to be stored faithfully. */
    val needsSchema6: Boolean get() = tool == ToolType.TAPE || !look.isDefault

    override fun equals(other: Any?): Boolean = other is Stroke && id == other.id && tool == other.tool &&
        color == other.color && width == other.width && x.contentEquals(other.x) && y.contentEquals(other.y) &&
        pressure.contentEquals(other.pressure) && tilt.contentEquals(other.tilt) &&
        orientation.contentEquals(other.orientation) && t.contentEquals(other.t) && startedAt == other.startedAt &&
        look == other.look

    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Stroke($id, $tool, n=$size)"
}

@Serializable
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(o: Rect) = left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom
    fun contains(px: Float, py: Float) = px in left..right && py in top..bottom
    fun inflate(d: Float) = Rect(left - d, top - d, right + d, bottom + d)
    val width get() = right - left
    val height get() = bottom - top
    fun union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    companion object { val EMPTY = Rect(0f, 0f, 0f, 0f) }
}

/** A page of an imported PDF shown as this page's background; the file lives in the notebook's attachments. */
@Serializable
data class PdfBackground(val attachmentId: String, val pageIndex: Int)

/**
 * A typed text box. [x], [y] is the top-left corner in page units; text wraps at [width].
 * [height] is the laid-out height, kept for hit-testing and selection (renderers may
 * re-measure). [fontSize] is in page units (A4 width = 1000).
 */
@Serializable
data class TextBox(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val fontSize: Float = 24f,
    val color: Argb = 0xFF1B1B1F,
) {
    val bounds: Rect get() = Rect(x, y, x + width, y + height)
    fun transformed(scale: Float, ax: Float, ay: Float, dx: Float, dy: Float, newId: Boolean = false) = copy(
        id = if (newId) newId() else id,
        x = ax + (x - ax) * scale + dx, y = ay + (y - ay) * scale + dy,
        width = width * scale, height = height * scale, fontSize = fontSize * scale,
    )
}

/** A picture placed on the page; the file is the notebook attachment [attachmentId]. */
@Serializable
data class PageImage(
    val id: String = newId(),
    val attachmentId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val bounds: Rect get() = Rect(x, y, x + width, y + height)
    fun transformed(scale: Float, ax: Float, ay: Float, dx: Float, dy: Float, newId: Boolean = false) = copy(
        id = if (newId) newId() else id,
        x = ax + (x - ax) * scale + dx, y = ay + (y - ay) * scale + dy, width = width * scale, height = height * scale,
    )
}

/**
 * A sticky note on the page (schema 6): a coloured square you write on. Its ink lives with it,
 * in the note's own coordinates (0,0 = its top-left corner), so moving the note moves the ink.
 * Minimised, it shows as a small tab at the page's top-left.
 */
@Serializable
data class StickyNote(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Argb = 0xFFFFE9A8,
    val minimized: Boolean = false,
    val strokes: List<Stroke> = emptyList(),
) {
    val bounds: Rect get() = Rect(x, y, x + width, y + height)
    fun transformed(scale: Float, ax: Float, ay: Float, dx: Float, dy: Float, newId: Boolean = false) = copy(
        id = if (newId) newId() else id,
        x = ax + (x - ax) * scale + dx, y = ay + (y - ay) * scale + dy, width = width * scale, height = height * scale,
        strokes = strokes.map { it.transformed(scale, 0f, 0f, 0f, 0f, newId) },
    )
}

/** A table (schema 6): [rows] × [cols] cells of text, row by row in [cells]. */
@Serializable
data class PageTable(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rows: Int,
    val cols: Int,
    val cells: List<String> = List(rows * cols) { "" },
    val fontSize: Float = 22f,
    val color: Argb = 0xFF1B1B1F,
) {
    init { require(rows in 1..MAX && cols in 1..MAX) { "table size out of range" } }
    val bounds: Rect get() = Rect(x, y, x + width, y + height)
    fun cell(r: Int, c: Int): String = cells.getOrElse(r * cols + c) { "" }
    fun withCell(r: Int, c: Int, text: String): PageTable =
        copy(cells = List(rows * cols) { if (it == r * cols + c) text else cells.getOrElse(it) { "" } })
    fun transformed(scale: Float, ax: Float, ay: Float, dx: Float, dy: Float, newId: Boolean = false) = copy(
        id = if (newId) newId() else id,
        x = ax + (x - ax) * scale + dx, y = ay + (y - ay) * scale + dy,
        width = width * scale, height = height * scale, fontSize = fontSize * scale,
    )

    companion object { const val MAX = 20 }
}

/** A voice recording or audio file placed on the page (schema 6); the file is attachment [attachmentId]. */
@Serializable
data class PageAudio(
    val id: String = newId(),
    val attachmentId: String,
    val x: Float,
    val y: Float,
    val title: String,
    val durationMs: Long = 0,
) {
    val width: Float get() = CHIP_WIDTH
    val height: Float get() = CHIP_HEIGHT
    val bounds: Rect get() = Rect(x, y, x + CHIP_WIDTH, y + CHIP_HEIGHT)
    fun transformed(scale: Float, ax: Float, ay: Float, dx: Float, dy: Float, newId: Boolean = false) = copy(
        id = if (newId) newId() else id, x = ax + (x - ax) * scale + dx, y = ay + (y - ay) * scale + dy,
    )

    companion object {
        const val CHIP_WIDTH = 300f
        const val CHIP_HEIGHT = 64f
    }
}

/**
 * Schema history: 1 = stroke channels as number arrays; 2 = packed points;
 * 3 = text boxes and images (an older app refuses these pages instead of dropping them);
 * 4 = fine-liner and marker tools (an older app would reject the unknown tool names);
 * 5 = airbrush; 6 = tape, stroke looks (dashes, fixed width, round highlighter, tape patterns),
 * sticky notes, tables and audio.
 *
 * A page is stamped with the lowest version its content needs ([requiredSchema], at least
 * [BASE_SCHEMA]) whenever it is encoded.
 */
@Serializable
data class Page(
    val schemaVersion: Int = BASE_SCHEMA,
    val id: String = newId(),
    val width: Float = NotebookMeta.PAGE_WIDTH,
    val height: Float = NotebookMeta.PAGE_HEIGHT,
    val paper: PaperTemplate = PaperTemplate.RULED,
    val strokes: List<Stroke> = emptyList(),
    /** Optional; absent in notebooks created before PDF import existed. */
    val pdf: PdfBackground? = null,
    /** Drawn over the ink. Absent before schema 3. */
    val texts: List<TextBox> = emptyList(),
    /** Drawn under the ink. Absent before schema 3. */
    val images: List<PageImage> = emptyList(),
    /** Schema 6; written only when not empty. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val stickies: List<StickyNote> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tables: List<PageTable> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val audio: List<PageAudio> = emptyList(),
) {
    /** The lowest schema that stores this page faithfully (older builds then refuse it instead of misreading it). */
    fun requiredSchema(): Int =
        if (stickies.isNotEmpty() || tables.isNotEmpty() || audio.isNotEmpty() || strokes.any { it.needsSchema6 }) 6 else BASE_SCHEMA
}

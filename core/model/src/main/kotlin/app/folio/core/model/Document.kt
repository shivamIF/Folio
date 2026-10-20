package app.folio.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.UUID

/** Current on-disk schema version of every persisted model object. Bump together with a migration. */
const val SCHEMA_VERSION = 5

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
) {
    companion object {
        /** Page units; roughly A4 at 120 units per inch. */
        const val PAGE_WIDTH = 1000f
        const val PAGE_HEIGHT = 1414f
    }
}

@Serializable
data class LibraryIndex(
    val schemaVersion: Int = SCHEMA_VERSION,
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

/**
 * Persisted by name. FINELINER and MARKER arrived in schema 4, AIRBRUSH in schema 5.
 *
 * Calligraphy, crayon and watercolour existed only in test builds and were withdrawn; strokes
 * saved with them load as the nearest remaining tool (the [JsonNames] aliases).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ToolType {
    @JsonNames("CALLIGRAPHY") FOUNTAIN, BALLPOINT, @JsonNames("CRAYON") PENCIL, BRUSH,
    @JsonNames("WATERCOLOR") HIGHLIGHTER, FINELINER, MARKER, AIRBRUSH;

    /**
     * Furthest the drawn ink can reach from the centreline, in nominal widths (a tilted pencil
     * spreads wide; square caps reach their corners). Used for bounds and hit-testing; the brush
     * engine's BrushSpec.reach is tested to match. Not persisted.
     */
    val inkReach: Float get() = when (this) {
        PENCIL -> 1.6f
        AIRBRUSH -> 1.0f
        BRUSH -> 0.85f
        MARKER, HIGHLIGHTER -> 0.71f
        FOUNTAIN, BALLPOINT, FINELINER -> 0.5f
    }

    val isHighlighter get() = this == HIGHLIGHTER
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
        pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt,
    )

    fun recolored(newColor: Argb): Stroke = Stroke(
        id, tool, newColor, width, x.copyOf(), y.copyOf(), pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt,
    )

    fun translated(dx: Float, dy: Float): Stroke = Stroke(
        id, tool, color, width,
        FloatArray(size) { x[it] + dx }, FloatArray(size) { y[it] + dy },
        pressure.copyOf(), tilt.copyOf(), orientation.copyOf(), t.copyOf(), startedAt,
    )

    override fun equals(other: Any?): Boolean = other is Stroke && id == other.id && tool == other.tool &&
        color == other.color && width == other.width && x.contentEquals(other.x) && y.contentEquals(other.y) &&
        pressure.contentEquals(other.pressure) && tilt.contentEquals(other.tilt) &&
        orientation.contentEquals(other.orientation) && t.contentEquals(other.t) && startedAt == other.startedAt

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
 * Schema history: 1 = stroke channels as number arrays; 2 = packed points;
 * 3 = text boxes and images (an older app refuses these pages instead of dropping them);
 * 4 = fine-liner and marker tools (an older app would reject the unknown tool names).
 * Pages are stamped with the current version whenever they are encoded.
 */
@Serializable
data class Page(
    val schemaVersion: Int = SCHEMA_VERSION,
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
)

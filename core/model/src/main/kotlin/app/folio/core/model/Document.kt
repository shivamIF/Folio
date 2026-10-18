package app.folio.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Current on-disk schema version of every persisted model object. Bump together with a migration. */
const val SCHEMA_VERSION = 1

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

@Serializable
data class Folder(
    val id: String = newId(),
    val name: String,
    val parentId: String? = null,
    val color: Argb = 0xFF98C1D9,
    val createdAt: Long,
    val modifiedAt: Long = createdAt,
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
)

@Serializable
enum class ToolType {
    FOUNTAIN, BALLPOINT, PENCIL, BRUSH, HIGHLIGHTER;

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
 */
@Serializable
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

    fun bounds(): Rect {
        if (size == 0) return Rect.EMPTY
        var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until size) {
            if (x[i] < l) l = x[i]; if (x[i] > r) r = x[i]
            if (y[i] < tp) tp = y[i]; if (y[i] > b) b = y[i]
        }
        val pad = width
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
)

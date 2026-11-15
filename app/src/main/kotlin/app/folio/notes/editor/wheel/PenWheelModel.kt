package app.folio.notes.editor.wheel

import app.folio.core.model.Argb
import app.folio.notes.editor.EditorTool
import app.folio.notes.editor.ToolState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** What one segment of the pen palette does. */
sealed interface WheelItem {
    data class Tool(val tool: EditorTool) : WheelItem
    data class Colour(val color: Argb) : WheelItem
    data class Size(val width: Float) : WheelItem
    data object Undo : WheelItem
    data object Redo : WheelItem
    /** Opens the outer arc of colours. */
    data object ColourMenu : WheelItem
    /** Opens the outer arc of thicknesses. */
    data object SizeMenu : WheelItem
}

/**
 * The pen palette: a short arc of the most-used tools that opens beside the S Pen (side button,
 * or press and hold the pen still). Colour and thickness open a second arc outside the first
 * with their choices. Folio's own design; free of Android types so layout and hit-testing are
 * unit tested. Angles are degrees clockwise from straight up.
 */
object PenWheelModel {
    /** Angle the main arc covers. */
    const val SWEEP = 164f
    const val SUB_COLOURS = 6

    /** The main arc, in order along it. */
    fun items(s: ToolState): List<WheelItem> = listOf(
        WheelItem.SizeMenu, WheelItem.ColourMenu,
        WheelItem.Tool(EditorTool.PEN), WheelItem.Tool(EditorTool.HIGHLIGHTER), WheelItem.Tool(EditorTool.ERASER),
        WheelItem.Tool(EditorTool.SELECTION), WheelItem.Undo, WheelItem.Redo,
    )

    /** The outer arc a menu item opens; empty for the others. */
    fun subItems(s: ToolState, item: WheelItem): List<WheelItem> = when (item) {
        WheelItem.ColourMenu -> (s.quickColors + s.recentColors).map { it or 0xFF000000 }.distinct().take(SUB_COLOURS).map { WheelItem.Colour(it) }
        WheelItem.SizeMenu -> s.quickSizesOf(s.sizeTool).map { WheelItem.Size(it) }
        else -> emptyList()
    }

    /**
     * Which way the arc faces from the pen: up and to the left, clear of a right hand, unless
     * the pen is too close to the left edge ([x] < [room]); then up and to the right.
     */
    fun facing(x: Float, room: Float): Float = if (x < room) 60f else 300f

    /**
     * The segment of an arc under a point ([dx], [dy]) from its centre, or -1. The arc starts at
     * [startDeg], covers [sweepDeg] clockwise in [count] equal segments, between radii [inner] and [outer].
     */
    fun arcSliceAt(dx: Float, dy: Float, count: Int, startDeg: Float, sweepDeg: Float, inner: Float, outer: Float): Int {
        if (count <= 0 || sweepDeg <= 0f) return -1
        val r = hypot(dx, dy)
        if (r < inner || r > outer) return -1
        var a = (atan2(dx.toDouble(), -dy.toDouble()) * 180 / PI).toFloat() - startDeg
        a = ((a % 360f) + 360f) % 360f
        if (a >= sweepDeg) return -1
        return (a / (sweepDeg / count)).toInt().coerceIn(0, count - 1)
    }

    /** Start angle and sweep of the outer arc of [n] segments centred on main segment [index] of [count]. */
    fun subArc(index: Int, count: Int, n: Int, startDeg: Float, midMain: Float, midSub: Float): Pair<Float, Float> {
        val step = SWEEP / count
        val sweep = n * step * midMain / midSub
        return (startDeg + (index + 0.5f) * step - sweep / 2f) to sweep
    }

    /** The centre nearest ([x], [y]) that keeps a circle of [radius] (plus [margin]) inside [width] x [height]. */
    fun clampCentre(x: Float, y: Float, radius: Float, margin: Float, width: Float, height: Float): Pair<Float, Float> {
        val m = radius + margin
        fun fit(v: Float, size: Float) = if (size < 2 * m) size / 2f else v.coerceIn(m, size - m)
        return fit(x, width) to fit(y, height)
    }
}

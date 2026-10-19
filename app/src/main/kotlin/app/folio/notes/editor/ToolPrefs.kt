package app.folio.notes.editor

import android.content.Context
import app.folio.core.ink.PressureCurve
import app.folio.core.model.Argb
import app.folio.core.model.ToolType

enum class LassoShape { FREEFORM, RECTANGLE }

data class ToolState(
    val tool: ToolType = ToolType.FOUNTAIN,
    /** The pen style the toolbar's pen slot comes back to when you leave pencil or highlighter. */
    val lastPen: ToolType = ToolType.FOUNTAIN,
    val eraser: Boolean = false,
    /** Lasso select tool (not persisted: the app always reopens on a writing tool). */
    val lasso: Boolean = false,
    val lassoShape: LassoShape = LassoShape.FREEFORM,
    /** Text tool (not persisted, like the lasso). */
    val text: Boolean = false,
    /** Font size for new text boxes, in page units (A4 width = 1000). */
    val textSize: Float = 28f,
    val eraserPartial: Boolean = false,
    val eraserSizeDp: Float = 14f,
    val eraseHighlighterOnly: Boolean = false,
    val color: Argb = 0xFF1B1B1F,
    /** Width per tool, so switching tools keeps each one's own size. */
    val widths: Map<ToolType, Float> = DEFAULT_WIDTHS,
    val stabilization: Float = 0.3f,
    val penOnly: Boolean = true,
    /** 0 = light hand … 1 = firm hand; see [PressureCurve.fromSensitivity]. */
    val pressureSensitivity: Float = PressureCurve.sensitivityOf(PressureCurve.DEFAULT_CEILING),
    /** Most recent first, at most [RECENT_MAX]. */
    val recentColors: List<Argb> = emptyList(),
) {
    val width: Float get() = widths[tool] ?: 3f

    /** Selects writing tool [t], leaving eraser/lasso/text. A pen choice is remembered in [lastPen]. */
    fun select(t: ToolType): ToolState =
        copy(tool = t, eraser = false, lasso = false, text = false, lastPen = if (t in PENS) t else lastPen)

    /** Picks [c] as the ink colour, leaves eraser/lasso, and moves [c] to the front of [recentColors]. */
    fun withColor(c: Argb): ToolState = copy(
        color = c, eraser = false, lasso = false,
        recentColors = (listOf(c) + recentColors.filter { it != c }).take(RECENT_MAX),
    )

    companion object {
        val DEFAULT_WIDTHS = mapOf(
            ToolType.FOUNTAIN to 3.5f, ToolType.BALLPOINT to 2.5f, ToolType.PENCIL to 3f,
            ToolType.BRUSH to 8f, ToolType.HIGHLIGHTER to 18f, ToolType.FINELINER to 1.8f, ToolType.MARKER to 6f,
            ToolType.AIRBRUSH to 14f,
        )

        /** The size slider's range: broad tools (highlighter, spray) go much wider. */
        fun sizeRange(tool: ToolType): ClosedFloatingPointRange<Float> = when (tool) {
            ToolType.HIGHLIGHTER -> 6f..40f
            ToolType.AIRBRUSH -> 3f..48f
            else -> 0.8f..16f
        }
        val PALETTE: List<Argb> = listOf(
            0xFF1B1B1F, 0xFF3D5A80, 0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFFEF6C00, 0xFF6A1B9A, 0xFFF9D71C,
        )

        const val RECENT_MAX = 6

        /** Pen styles that share the toolbar's pen slot (pencil and highlighter have their own). */
        val PENS = listOf(
            ToolType.FOUNTAIN, ToolType.BALLPOINT, ToolType.FINELINER, ToolType.BRUSH, ToolType.MARKER, ToolType.AIRBRUSH,
        )

        /** Full swatch grid in the tool dropdown, one row per colour family, dark to light. Original palette. */
        val SWATCHES: List<List<Argb>> = listOf(
            listOf(0xFF1B1B1F, 0xFF4A4A52, 0xFF8A8A93, 0xFFC9C9CF, 0xFFFFFFFF, 0xFF6B4F3A),
            listOf(0xFF0D2B52, 0xFF1565C0, 0xFF3D5A80, 0xFF4F9BD9, 0xFF98C1D9, 0xFF00838F),
            listOf(0xFF1B5E20, 0xFF2E7D32, 0xFF43A047, 0xFF8BC34A, 0xFF2A9D8F, 0xFFB7E4C7),
            listOf(0xFF8E1B1B, 0xFFC62828, 0xFFEE6C4D, 0xFFEF6C00, 0xFFF4A261, 0xFFF9D71C),
            listOf(0xFF4A148C, 0xFF6A1B9A, 0xFF8E7DBE, 0xFFD81B60, 0xFFF48FB1, 0xFFFFE680),
        )

        /** Three one-tap sizes per tool: fine, medium, bold. The middle one is the default width. */
        val SIZE_PRESETS: Map<ToolType, List<Float>> = mapOf(
            ToolType.FOUNTAIN to listOf(2f, 3.5f, 6f), ToolType.BALLPOINT to listOf(1.5f, 2.5f, 4.5f),
            ToolType.PENCIL to listOf(2f, 3f, 5.5f), ToolType.BRUSH to listOf(5f, 8f, 13f),
            ToolType.HIGHLIGHTER to listOf(10f, 18f, 30f), ToolType.FINELINER to listOf(1f, 1.8f, 3f),
            ToolType.MARKER to listOf(4f, 6f, 10f), ToolType.AIRBRUSH to listOf(8f, 14f, 28f),
        )
    }
}

/** Persists the last-used tool settings in SharedPreferences. */
class ToolPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    fun load(): ToolState {
        val d = ToolState()
        val tool = runCatching { ToolType.valueOf(sp.getString("tool", d.tool.name)!!) }.getOrDefault(d.tool)
        val lastPen = runCatching { ToolType.valueOf(sp.getString("lastPen", d.lastPen.name)!!) }
            .getOrDefault(d.lastPen).takeIf { it in ToolState.PENS } ?: d.lastPen
        return ToolState(
            tool = tool,
            lastPen = if (tool in ToolState.PENS) tool else lastPen,
            color = sp.getLong("color", d.color),
            widths = ToolType.entries.associateWith { sp.getFloat("width_${it.name}", d.widths.getValue(it)) },
            stabilization = sp.getFloat("stabilization", d.stabilization),
            penOnly = sp.getBoolean("penOnly", d.penOnly),
            pressureSensitivity = sp.getFloat("pressureSensitivity", d.pressureSensitivity),
            eraserPartial = sp.getBoolean("eraserPartial", d.eraserPartial),
            eraserSizeDp = sp.getFloat("eraserSizeDp", d.eraserSizeDp),
            eraseHighlighterOnly = sp.getBoolean("eraseHighlighterOnly", d.eraseHighlighterOnly),
            textSize = sp.getFloat("textSize", d.textSize),
            recentColors = sp.getString("recentColors", "")!!.split(',')
                .mapNotNull { it.toLongOrNull(16) }.take(ToolState.RECENT_MAX),
            lassoShape = runCatching { LassoShape.valueOf(sp.getString("lassoShape", d.lassoShape.name)!!) }.getOrDefault(d.lassoShape),
        )
    }

    fun save(s: ToolState) {
        sp.edit().apply {
            putString("tool", s.tool.name)
            putString("lastPen", s.lastPen.name)
            putLong("color", s.color)
            s.widths.forEach { (t, w) -> putFloat("width_${t.name}", w) }
            putFloat("stabilization", s.stabilization)
            putBoolean("penOnly", s.penOnly)
            putFloat("pressureSensitivity", s.pressureSensitivity)
            putString("lassoShape", s.lassoShape.name)
            putBoolean("eraserPartial", s.eraserPartial)
            putFloat("eraserSizeDp", s.eraserSizeDp)
            putBoolean("eraseHighlighterOnly", s.eraseHighlighterOnly)
            putFloat("textSize", s.textSize)
            putString("recentColors", s.recentColors.joinToString(",") { it.toString(16) })
        }.apply()
    }
}

package app.folio.notes.editor

import android.content.Context
import app.folio.core.ink.PressureCurve
import app.folio.core.model.Argb
import app.folio.core.model.ToolType

data class ToolState(
    val tool: ToolType = ToolType.FOUNTAIN,
    /** The pen kind the toolbar's pen slot comes back to when you leave pencil or highlighter. */
    val lastPen: ToolType = ToolType.FOUNTAIN,
    val eraser: Boolean = false,
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

    /** Selects [t] and turns the eraser off. A pen choice is remembered in [lastPen]. */
    fun select(t: ToolType): ToolState = copy(tool = t, eraser = false, lastPen = if (t in PENS) t else lastPen)

    /** Picks [c] as the ink colour, turns the eraser off and moves [c] to the front of [recentColors]. */
    fun withColor(c: Argb): ToolState =
        copy(color = c, eraser = false, recentColors = (listOf(c) + recentColors.filter { it != c }).take(RECENT_MAX))

    companion object {
        val DEFAULT_WIDTHS = mapOf(
            ToolType.FOUNTAIN to 3.5f, ToolType.BALLPOINT to 2.5f, ToolType.PENCIL to 3f,
            ToolType.BRUSH to 8f, ToolType.HIGHLIGHTER to 18f,
        )
        const val RECENT_MAX = 6

        /** Tools that share the toolbar's pen slot. */
        val PENS = listOf(ToolType.FOUNTAIN, ToolType.BALLPOINT, ToolType.BRUSH)

        /** Quick colours in the toolbar. */
        val PALETTE: List<Argb> = listOf(
            0xFF1B1B1F, 0xFF3D5A80, 0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFFEF6C00, 0xFF6A1B9A, 0xFFF9D71C,
        )

        /** Full swatch grid in the tool dropdown, one row per colour family, dark to light. Original palette. */
        val SWATCHES: List<List<Argb>> = listOf(
            listOf(0xFF1B1B1F, 0xFF4A4A52, 0xFF8A8A93, 0xFFC9C9CF, 0xFFFFFFFF, 0xFF6B4F3A),
            listOf(0xFF0D2B52, 0xFF1565C0, 0xFF3D5A80, 0xFF4F9BD9, 0xFF98C1D9, 0xFF00838F),
            listOf(0xFF1B5E20, 0xFF2E7D32, 0xFF43A047, 0xFF8BC34A, 0xFF2A9D8F, 0xFFB7E4C7),
            listOf(0xFF8E1B1B, 0xFFC62828, 0xFFEE6C4D, 0xFFEF6C00, 0xFFF4A261, 0xFFF9D71C),
            listOf(0xFF4A148C, 0xFF6A1B9A, 0xFF8E7DBE, 0xFFD81B60, 0xFFF48FB1, 0xFFFFE680),
        )

        /** Three one-tap sizes per tool: fine, medium, bold. */
        val SIZE_PRESETS: Map<ToolType, List<Float>> = mapOf(
            ToolType.FOUNTAIN to listOf(2f, 3.5f, 6f), ToolType.BALLPOINT to listOf(1.5f, 2.5f, 4.5f),
            ToolType.PENCIL to listOf(2f, 3f, 5.5f), ToolType.BRUSH to listOf(5f, 8f, 13f),
            ToolType.HIGHLIGHTER to listOf(10f, 18f, 30f),
        )

        fun sizeRange(t: ToolType): ClosedFloatingPointRange<Float> = if (t == ToolType.HIGHLIGHTER) 6f..40f else 0.8f..16f
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
            recentColors = sp.getString("recentColors", "")!!.split(',')
                .mapNotNull { it.toLongOrNull(16) }.take(ToolState.RECENT_MAX),
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
            putString("recentColors", s.recentColors.joinToString(",") { it.toString(16) })
        }.apply()
    }
}

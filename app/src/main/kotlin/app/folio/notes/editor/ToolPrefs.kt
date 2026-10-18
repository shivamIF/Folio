package app.folio.notes.editor

import android.content.Context
import app.folio.core.ink.PressureCurve
import app.folio.core.model.Argb
import app.folio.core.model.ToolType

data class ToolState(
    val tool: ToolType = ToolType.FOUNTAIN,
    val eraser: Boolean = false,
    val color: Argb = 0xFF1B1B1F,
    /** Width per tool, so switching tools keeps each one's own size. */
    val widths: Map<ToolType, Float> = DEFAULT_WIDTHS,
    val stabilization: Float = 0.3f,
    val penOnly: Boolean = true,
    /** 0 = light hand … 1 = firm hand; see [PressureCurve.fromSensitivity]. */
    val pressureSensitivity: Float = PressureCurve.sensitivityOf(PressureCurve.DEFAULT_CEILING),
) {
    val width: Float get() = widths[tool] ?: 3f

    companion object {
        val DEFAULT_WIDTHS = mapOf(
            ToolType.FOUNTAIN to 3.5f, ToolType.BALLPOINT to 2.5f, ToolType.PENCIL to 3f,
            ToolType.BRUSH to 8f, ToolType.HIGHLIGHTER to 18f,
        )
        val PALETTE: List<Argb> = listOf(
            0xFF1B1B1F, 0xFF3D5A80, 0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFFEF6C00, 0xFF6A1B9A, 0xFFF9D71C,
        )
    }
}

/** Persists the last-used tool settings in SharedPreferences. */
class ToolPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    fun load(): ToolState {
        val d = ToolState()
        val tool = runCatching { ToolType.valueOf(sp.getString("tool", d.tool.name)!!) }.getOrDefault(d.tool)
        return ToolState(
            tool = tool,
            color = sp.getLong("color", d.color),
            widths = ToolType.entries.associateWith { sp.getFloat("width_${it.name}", d.widths.getValue(it)) },
            stabilization = sp.getFloat("stabilization", d.stabilization),
            penOnly = sp.getBoolean("penOnly", d.penOnly),
            pressureSensitivity = sp.getFloat("pressureSensitivity", d.pressureSensitivity),
        )
    }

    fun save(s: ToolState) {
        sp.edit().apply {
            putString("tool", s.tool.name)
            putLong("color", s.color)
            s.widths.forEach { (t, w) -> putFloat("width_${t.name}", w) }
            putFloat("stabilization", s.stabilization)
            putBoolean("penOnly", s.penOnly)
            putFloat("pressureSensitivity", s.pressureSensitivity)
        }.apply()
    }
}

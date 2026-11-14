package app.folio.core.ink

import app.folio.core.model.Stroke
import app.folio.core.model.TextBox
import app.folio.core.model.ToolType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InkShapingTest {
    private fun stroke(tool: ToolType, xs: FloatArray, ys: FloatArray, width: Float = 3f, id: String = app.folio.core.model.newId(), ps: FloatArray? = null) = Stroke(
        id, tool, 0xFF000000, width, xs, ys, ps ?: FloatArray(xs.size) { 0.5f }, FloatArray(xs.size), FloatArray(xs.size), IntArray(xs.size) { it * 10 },
    )

    @Test fun straightKeepsEndsAndPressureTaper() {
        val wobbly = stroke(
            ToolType.FOUNTAIN, floatArrayOf(0f, 25f, 50f, 75f, 100f), floatArrayOf(0f, 8f, -6f, 5f, 0f),
            ps = floatArrayOf(0.1f, 0.8f, 0.8f, 0.8f, 0.2f),
        )
        val s = InkShaping.straight(wobbly)
        assertEquals(0f, s.x.first()); assertEquals(100f, s.x.last(), 1e-3f)
        assertTrue(s.y.all { kotlin.math.abs(it) < 1e-3f }, "every point on the line")
        assertEquals(0.1f, s.pressure.first(), 1e-3f)
        assertEquals(0.2f, s.pressure.last(), 1e-3f)
        assertTrue(s.pressure[s.size / 2] > 0.7f, "the middle keeps its pressure")
        assertEquals(wobbly.id, s.id)
    }

    @Test fun highlighterFitsTheWritingItCovers() {
        // A word 30 units tall around y = 115, and an unrelated line far below.
        val word = stroke(ToolType.FOUNTAIN, floatArrayOf(10f, 20f, 30f, 40f), floatArrayOf(100f, 130f, 100f, 130f), width = 2f)
        val far = stroke(ToolType.FOUNTAIN, floatArrayOf(10f, 60f), floatArrayOf(400f, 400f))
        val hl = stroke(ToolType.HIGHLIGHTER, floatArrayOf(0f, 60f), floatArrayOf(108f, 108f), width = 18f)
        val all = listOf(word, far, hl)
        val target = InkShaping.covered(hl, all, StrokeIndex(all), emptyList())
        assertNotNull(target)
        assertEquals(99f, target.top, 1e-3f); assertEquals(131f, target.bottom, 1e-3f)
        val fitted = InkShaping.fitTo(hl, target)
        assertEquals(32f * 1.12f, fitted.width, 1e-3f)
        assertEquals(115f, fitted.y[0], 1e-3f) // moved to the word's middle
    }

    @Test fun textBoxesCount() {
        val hl = stroke(ToolType.HIGHLIGHTER, floatArrayOf(0f, 200f), floatArrayOf(50f, 50f), width = 20f)
        val box = TextBox(x = 10f, y = 35f, width = 180f, height = 30f, text = "Hi", fontSize = 24f)
        val target = InkShaping.covered(hl, listOf(hl), StrokeIndex(listOf(hl)), listOf(box))
        assertNotNull(target)
        assertEquals(35f, target.top)
    }

    @Test fun coversNothingGivesNull() {
        val hl = stroke(ToolType.HIGHLIGHTER, floatArrayOf(0f, 60f), floatArrayOf(10f, 10f), width = 18f)
        assertNull(InkShaping.covered(hl, listOf(hl), StrokeIndex(listOf(hl)), emptyList()))
    }

    @Test fun aJitteryTapBecomesOneRoundDot() {
        val tap = stroke(ToolType.BALLPOINT, floatArrayOf(100f, 100.8f, 101f, 100.4f), floatArrayOf(50f, 50.6f, 49.5f, 50.2f), ps = floatArrayOf(0.1f, 0.3f, 0.2f, 0.05f))
        val dot = assertNotNull(InkShaping.asDot(tap, slop = 2f))
        assertEquals(1, dot.size)
        assertEquals(100.5f, dot.x[0], 0.01f)
        assertEquals(InkShaping.DOT_MIN_EFFORT, dot.pressure[0])
        assertEquals(tap.id, dot.id)
        // A short dash that leaves the slop stays a stroke.
        assertNull(InkShaping.asDot(stroke(ToolType.BALLPOINT, floatArrayOf(100f, 103f, 106f), floatArrayOf(50f, 50f, 50f)), slop = 2f))
    }
}

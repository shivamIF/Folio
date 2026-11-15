package app.folio.notes.editor.wheel

import app.folio.notes.editor.EditorTool
import app.folio.notes.editor.ToolState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PenWheelModelTest {
    @Test fun arcSegmentsRunClockwiseAndOnlyInsideTheArc() {
        // An arc from 270° (left) clockwise 180° to 90° (right), 4 segments of 45°.
        fun at(dx: Float, dy: Float) = PenWheelModel.arcSliceAt(dx, dy, 4, 270f, 180f, 50f, 100f)
        assertEquals(0, at(-70f, -20f)) // just above left
        assertEquals(1, at(-30f, -70f))
        assertEquals(2, at(30f, -70f))
        assertEquals(3, at(70f, -20f))
        assertEquals(-1, at(0f, 70f))   // below: outside the arc
        assertEquals(-1, at(0f, -20f))  // inside the inner radius
        assertEquals(-1, at(0f, -150f)) // beyond the outer radius
    }

    @Test fun outerArcIsCentredOnItsSegmentWithEqualSizedSegments() {
        val (start, sweep) = PenWheelModel.subArc(index = 1, count = 8, n = 3, startDeg = 218f, midMain = 100f, midSub = 150f)
        val step = PenWheelModel.SWEEP / 8
        assertEquals(3 * step * 100f / 150f, sweep, 0.01f)
        assertEquals(218f + 1.5f * step, start + sweep / 2, 0.01f)
    }

    @Test fun facesAwayFromTheHandUnlessAtTheLeftEdge() {
        assertEquals(300f, PenWheelModel.facing(600f, 200f))
        assertEquals(60f, PenWheelModel.facing(80f, 200f))
    }

    @Test fun centreStaysOnScreen() {
        assertEquals(110f to 110f, PenWheelModel.clampCentre(5f, 20f, 100f, 10f, 1200f, 1800f))
        assertEquals(1090f to 1690f, PenWheelModel.clampCentre(1195f, 1799f, 100f, 10f, 1200f, 1800f))
    }

    @Test fun menusOpenColoursAndThicknesses() {
        val s = ToolState().withColor(0xFF112233)
        val items = PenWheelModel.items(s)
        assertTrue(WheelItem.Tool(EditorTool.PEN) in items && WheelItem.Undo in items && WheelItem.ColourMenu in items)
        val colours = PenWheelModel.subItems(s, WheelItem.ColourMenu)
        assertTrue(colours.size in 1..PenWheelModel.SUB_COLOURS && colours.all { it is WheelItem.Colour })
        assertEquals(s.quickSizesOf(s.sizeTool).map { WheelItem.Size(it) }, PenWheelModel.subItems(s, WheelItem.SizeMenu))
        assertTrue(PenWheelModel.subItems(s, WheelItem.Undo).isEmpty())
    }
}

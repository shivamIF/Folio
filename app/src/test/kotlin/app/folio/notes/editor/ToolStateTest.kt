package app.folio.notes.editor

import app.folio.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolStateTest {
    @Test fun recentColoursAreMostRecentFirstDedupedAndCapped() {
        var s = ToolState()
        (1L..8L).forEach { s = s.withColor(0xFF000000 + it) }
        s = s.withColor(0xFF000005)
        assertEquals(ToolState.RECENT_MAX, s.recentColors.size)
        assertEquals(listOf(0xFF000005, 0xFF000008, 0xFF000007, 0xFF000006, 0xFF000004, 0xFF000003), s.recentColors)
        assertEquals(0xFF000005, s.color)
    }

    @Test fun pickingAColourLeavesEraserAndLasso() {
        val s = ToolState(eraser = true, lasso = true).withColor(0xFFC62828)
        assertFalse(s.eraser)
        assertFalse(s.lasso)
    }

    @Test fun everyToolHasThreeSizePresetsInsideItsRangeAroundItsDefault() {
        ToolType.entries.forEach { t ->
            val p = ToolState.SIZE_PRESETS.getValue(t)
            assertEquals(3, p.size)
            p.forEach { w -> assertTrue("$t $w", w in ToolState.sizeRange(t)) }
            assertEquals("default $t width should be the medium preset", ToolState.DEFAULT_WIDTHS.getValue(t), p[1])
        }
    }

    @Test fun penSlotRemembersLastPenAcrossPencil() {
        val s = ToolState().select(ToolType.MARKER).select(ToolType.PENCIL)
        assertEquals(ToolType.PENCIL, s.tool)
        assertEquals(ToolType.MARKER, s.lastPen)
        assertEquals(ToolType.MARKER, ToolSlot.PEN.toolIn(s))
    }

    @Test fun selectingAWritingToolLeavesEraserLassoAndText() {
        val s = ToolState(eraser = true, lasso = true, text = true).select(ToolType.HIGHLIGHTER)
        assertFalse(s.eraser || s.lasso || s.text)
    }

    @Test fun everyPenStyleSharesThePenSlot() {
        assertEquals(ToolType.entries.size - 2, ToolState.PENS.toSet().size)
        ToolState.PENS.forEach { assertEquals(ToolSlot.PEN, ToolSlot.of(it)) }
        assertEquals(ToolSlot.PENCIL, ToolSlot.of(ToolType.PENCIL))
        assertEquals(ToolSlot.HIGHLIGHTER, ToolSlot.of(ToolType.HIGHLIGHTER))
    }

    @Test fun swatchGridIsRectangularAndHasNoDuplicates() {
        assertTrue(ToolState.SWATCHES.all { it.size == 6 })
        val all = ToolState.SWATCHES.flatten()
        assertEquals(all.size, all.toSet().size)
    }
}

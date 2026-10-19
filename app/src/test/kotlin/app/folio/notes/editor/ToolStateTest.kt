package app.folio.notes.editor

import app.folio.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolStateTest {
    @Test fun penSlotRemembersLastPenAcrossPencil() {
        val s = ToolState().select(ToolType.BRUSH).select(ToolType.PENCIL)
        assertEquals(ToolType.PENCIL, s.tool)
        assertEquals(ToolType.BRUSH, s.lastPen)
        assertEquals(ToolType.BRUSH, ToolSlot.PEN.toolIn(s))
    }

    @Test fun selectingAToolTurnsEraserOff() {
        assertFalse(ToolState(eraser = true).select(ToolType.HIGHLIGHTER).eraser)
    }

    @Test fun recentColoursAreMostRecentFirstDedupedAndCapped() {
        var s = ToolState()
        (1L..8L).forEach { s = s.withColor(0xFF000000 + it) }
        s = s.withColor(0xFF000005)
        assertEquals(ToolState.RECENT_MAX, s.recentColors.size)
        assertEquals(listOf(0xFF000005, 0xFF000008, 0xFF000007, 0xFF000006, 0xFF000004, 0xFF000003), s.recentColors)
        assertEquals(0xFF000005, s.color)
    }

    @Test fun everyToolHasThreeSizePresetsInsideItsRange() {
        ToolType.entries.forEach { t ->
            val p = ToolState.SIZE_PRESETS.getValue(t)
            assertEquals(3, p.size)
            p.forEach { w -> assert(w in ToolState.sizeRange(t)) { "$t $w" } }
            assert(ToolState.DEFAULT_WIDTHS.getValue(t) in p) { "default $t width should be a preset" }
        }
    }

    @Test fun slotOfMapsEveryPenToThePenSlot() {
        ToolState.PENS.forEach { assertEquals(ToolSlot.PEN, ToolSlot.of(it)) }
        assertEquals(ToolSlot.PENCIL, ToolSlot.of(ToolType.PENCIL))
        assertEquals(ToolSlot.HIGHLIGHTER, ToolSlot.of(ToolType.HIGHLIGHTER))
    }
}

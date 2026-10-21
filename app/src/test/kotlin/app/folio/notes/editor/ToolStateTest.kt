package app.folio.notes.editor

import app.folio.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun eachToolKeepsItsOwnColour() {
        var s = ToolState().withColor(0xFFAA0000)
        s = s.activate(EditorTool.HIGHLIGHTER).withColor(0xFF00AA00)
        assertEquals(0xFF00AA00, s.color)
        s = s.activate(EditorTool.PEN)
        assertEquals(0xFFAA0000, s.color)
        assertEquals(0xFF00AA00, s.colorOf(EditorTool.HIGHLIGHTER))
    }

    @Test fun pickingAColourFromTheEraserOrSelectionGoesBackToThePen() {
        val s = ToolState(active = EditorTool.ERASER).withColor(0xFFC62828)
        assertEquals(EditorTool.PEN, s.active)
        assertEquals(0xFFC62828, s.colorOf(EditorTool.PEN))
        assertEquals(EditorTool.SELECTION, ToolState(active = EditorTool.SELECTION).activate(EditorTool.SELECTION).active)
    }

    @Test fun theCanvasToolFollowsTheActiveTool() {
        val s = ToolState(pen = ToolType.MARKER)
        assertEquals(ToolType.MARKER, s.tool)
        assertEquals(ToolType.HIGHLIGHTER, s.activate(EditorTool.HIGHLIGHTER).tool)
        assertEquals(ToolType.TAPE, s.activate(EditorTool.TAPE).tool)
        assertEquals(ToolType.MARKER, s.activate(EditorTool.ERASER).tool)
        assertTrue(s.activate(EditorTool.ERASER).eraser)
        assertTrue(s.activate(EditorTool.SELECTION).lasso)
        assertTrue(s.activate(EditorTool.TEXT).text)
    }

    @Test fun choosingAPenTypeSwitchesToThePen() {
        val s = ToolState(active = EditorTool.LASER).selectPen(ToolType.PENCIL)
        assertEquals(EditorTool.PEN, s.active)
        assertEquals(ToolType.PENCIL, s.pen)
        assertEquals(EditorTool.HIGHLIGHTER, s.selectPen(ToolType.HIGHLIGHTER).active)
    }

    @Test fun highlighterOpacityIsCarriedInTheStrokeColour() {
        val s = ToolState(active = EditorTool.HIGHLIGHTER, highlighterOpacity = 50).withColor(0xFFFFEE00)
        assertEquals(0x80, ((s.strokeColor ushr 24) and 0xFF).toInt())
        assertEquals(0xFFEE00, s.strokeColor and 0xFFFFFF)
        assertEquals(0xFF, ((ToolState().strokeColor ushr 24) and 0xFF).toInt())
    }

    @Test fun quickSlotsAreEditedPerSlotAndPerTool() {
        var s = ToolState().withQuickColor(2, 0xFF123456)
        assertEquals(0xFF123456, s.quickColors[2])
        assertEquals(5, s.quickColors.size)
        s = s.withQuickSize(ToolType.MARKER, 0, 9f)
        assertEquals(9f, s.quickSizesOf(ToolType.MARKER)[0])
        assertEquals(ToolState.SIZE_PRESETS.getValue(ToolType.FOUNTAIN), s.quickSizesOf(ToolType.FOUNTAIN))
        // Out-of-range sizes are clamped to the tool's range.
        assertEquals(16f, s.withQuickSize(ToolType.FOUNTAIN, 1, 99f).quickSizesOf(ToolType.FOUNTAIN)[1])
    }

    @Test fun everyToolHasThreeSizePresetsInsideItsRangeAroundItsDefault() {
        ToolType.entries.forEach { t ->
            val p = ToolState.SIZE_PRESETS.getValue(t)
            assertEquals(3, p.size)
            p.forEach { w -> assertTrue("$t $w", w in ToolState.sizeRange(t)) }
            assertEquals("default $t width should be the medium preset", ToolState.DEFAULT_WIDTHS.getValue(t), p[1])
        }
    }

    @Test fun favouritesRoundTripAndApply() {
        val p = PenPreset(ToolType.BRUSH, 0xFF336699, 7.5f, PenLine.DASHED)
        assertEquals(p, PenPreset.decode(p.encode()))
        assertNull(PenPreset.decode("NOPE:1:2:3"))
        assertNull(PenPreset.decode("HIGHLIGHTER:ff00ff00:5.0:VARIABLE")) // only pens are favourites
        val s = ToolState(active = EditorTool.ERASER).apply(p)
        assertEquals(EditorTool.PEN, s.active)
        assertEquals(ToolType.BRUSH, s.pen)
        assertEquals(0xFF336699, s.color)
        assertEquals(7.5f, s.width)
        assertEquals(PenLine.DASHED, s.penLine)
        assertEquals(p, s.currentPreset())
    }

    @Test fun straightenAndAdjustFollowTheActiveTool() {
        val s = ToolState(straightenHighlighter = true, adjustTapeToText = true)
        assertFalse(s.straighten)
        assertTrue(s.activate(EditorTool.HIGHLIGHTER).straighten)
        assertTrue(s.activate(EditorTool.TAPE).adjustToText)
        assertFalse(s.activate(EditorTool.PEN).adjustToText)
    }

    @Test fun penRowHoldsEveryPenOnce() {
        assertEquals(ToolType.entries.size - 2, ToolState.PENS.toSet().size)
        assertFalse(ToolType.HIGHLIGHTER in ToolState.PENS || ToolType.TAPE in ToolState.PENS)
    }
}

class SizeScaleTest {
    @Test fun stepsCoverTheRangeAndRoundTrip() {
        for (t in ToolType.entries) {
            val r = ToolState.sizeRange(t)
            assertEquals(r.start, SizeScale.widthOf(t, 1), 1e-4f)
            assertEquals(r.endInclusive, SizeScale.widthOf(t, SizeScale.stepsOf(t)), 1e-3f)
            for (step in 1..SizeScale.stepsOf(t)) assertEquals(step, SizeScale.stepOf(t, SizeScale.widthOf(t, step)))
        }
        assertEquals(10, SizeScale.stepsOf(ToolType.TAPE))
        assertEquals(50, SizeScale.stepsOf(ToolType.FOUNTAIN))
    }

    @Test fun stepsGrowSteadily() {
        val w = (1..50).map { SizeScale.widthOf(ToolType.FOUNTAIN, it) }
        val ratios = w.zipWithNext { a, b -> b / a }
        assertTrue(ratios.all { kotlin.math.abs(it - ratios[0]) < 1e-3f })
    }
}

class ToolbarConfigTest {
    @Test fun defaultsMatchTheReferenceLayout() {
        val c = ToolbarConfig()
        assertEquals(
            listOf(ToolbarItem.PEN_COLOURS, ToolbarItem.PEN_SIZES, ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.SPEN_TO_TEXT,
                ToolbarItem.CHANGE_STYLE, ToolbarItem.TAPE, ToolbarItem.EASY_PAD, ToolbarItem.LASER),
            c.shown,
        )
        assertTrue(ToolbarItem.AUTO_FIX_SHAPES in c.hidden && ToolbarItem.LOCK_CANVAS in c.hidden && ToolbarItem.CLEAN_UP in c.hidden)
        assertEquals(ToolbarItem.entries.toSet(), (c.shown + c.hidden).toSet())
    }

    @Test fun encodeDecodeRoundTrips() {
        val c = ToolbarConfig().move(ToolbarItem.LASER, 0).copy(colourCount = 3, sizeCount = 1)
        assertEquals(c, ToolbarConfig.decode(c.encode()))
    }

    @Test fun movingAcrossTheLineShowsOrHides() {
        val c = ToolbarConfig()
        val hidden = c.move(ToolbarItem.UNDO, c.rows.lastIndex)
        assertFalse(ToolbarItem.UNDO in hidden.shown)
        assertEquals(ToolbarItem.UNDO, hidden.hidden.last())
        val shown = hidden.move(ToolbarItem.LOCK_CANVAS, 0)
        assertEquals(ToolbarItem.LOCK_CANVAS, shown.shown.first())
        // Dropped on the line itself: it's shown (the line moves down).
        val line = c.rows.indexOf(null)
        val atLine = c.move(ToolbarItem.CLEAN_UP, line)
        assertTrue(ToolbarItem.CLEAN_UP in atLine.shown)
    }

    @Test fun unknownAndMissingItemsAreRepaired() {
        val c = ToolbarConfig.decode("v1;shown=UNDO,BOGUS,UNDO;hidden=;colours=9;sizes=0")
        assertEquals(listOf(ToolbarItem.UNDO), c.shown)
        assertEquals(ToolbarItem.entries.size - 1, c.hidden.size)
        assertEquals(ToolbarConfig.MAX_COLOURS, c.colourCount)
        assertEquals(ToolbarConfig.MIN_SIZES, c.sizeCount)
        assertEquals(ToolbarConfig(), ToolbarConfig.decode(null))
        assertEquals(ToolbarConfig(), ToolbarConfig.decode("garbage"))
    }
}

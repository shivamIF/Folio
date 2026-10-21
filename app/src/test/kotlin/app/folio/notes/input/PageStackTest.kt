package app.folio.notes.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageStackTest {
    private val a4 = 1000f to 1414f
    private fun stack(vararg sizes: Pair<Float, Float>) =
        PageStack(sizes.indices.map { "p$it" }, FloatArray(sizes.size) { sizes[it].first }, FloatArray(sizes.size) { sizes[it].second })

    @Test fun pagesStackWithAGapAndNoOuterMargin() {
        val s = stack(a4, a4, a4)
        assertEquals(0f, s.top(0)); assertEquals(1414f + PageStack.GAP, s.top(1))
        assertEquals(3 * 1414f + 2 * PageStack.GAP, s.height)
        assertEquals(1000f, s.width)
        assertEquals(s.top(2), s.lastTop)
        assertEquals(1, s.indexOf("p1")); assertEquals(-1, s.indexOf("nope"))
    }

    @Test fun narrowerPagesAreCentredOnTheWidest() {
        val s = stack(612f to 792f, 1414f to 1000f)
        assertEquals(1414f, s.width)
        assertEquals((1414f - 612f) / 2f, s.left(0)); assertEquals(0f, s.left(1))
    }

    @Test fun thePenWritesOnThePageItIsOnOrTheNearerAcrossAGap() {
        val s = stack(a4, a4)
        assertEquals(0, s.nearest(10f)); assertEquals(0, s.nearest(1414f))
        assertEquals(0, s.nearest(1414f + 5f), "upper part of the gap → page above")
        assertEquals(1, s.nearest(1414f + PageStack.GAP - 5f), "lower part → page below")
        assertEquals(0, s.nearest(-500f)); assertEquals(1, s.nearest(1e6f))
        assertEquals(-1, stack().nearest(0f))
    }

    @Test fun overlappingListsVisiblePagesOnly() {
        val s = stack(a4, a4, a4)
        assertEquals(0..0, s.overlapping(0f, 1000f))
        assertEquals(0..1, s.overlapping(1000f, 1500f))
        assertTrue(s.overlapping(1415f, 1414f + PageStack.GAP - 1f).isEmpty(), "only the gap is visible")
        assertEquals(1..2, s.overlapping(1414f + 1f, 1e6f))
        assertTrue(s.overlapping(-100f, -1f).isEmpty())
        assertEquals(0..2, s.overlapping(-1e6f, 1e6f))
    }

    @Test fun sameLayoutComparesOrderAndSizes() {
        val s = stack(a4, a4)
        assertTrue(s.sameLayout(stack(a4, a4)))
        assertFalse(s.sameLayout(stack(a4, 1000f to 1000f)))
        assertFalse(s.sameLayout(PageStack(listOf("p1", "p0"), floatArrayOf(1000f, 1000f), floatArrayOf(1414f, 1414f))))
        assertFalse(s.sameLayout(null))
    }
}

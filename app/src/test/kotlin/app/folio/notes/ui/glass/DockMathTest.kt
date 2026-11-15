package app.folio.notes.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockMathTest {
    private val t = 44f
    private val large = 75f
    private val f = DockMath.reach(t, 2.5f)

    @Test fun reachIsThreeCellsAtTheDefaultSpread() = assertEquals(3 * t, f, 1e-3f)

    @Test fun cellUnderThePointerGrowsToExactlyLarge() {
        val rest = FloatArray(9) { t }
        val r = DockMath.swing(t, large, f, 1f)
        val e = DockMath.edges(rest, pointer = 4.5f * t, r = r, f = f) // centre of cell 4
        assertEquals(large, e[5] - e[4], 0.01f)
    }

    @Test fun neighboursShrinkBackToRestBeyondTheReach() {
        val rest = FloatArray(15) { t }
        val r = DockMath.swing(t, large, f, 1f)
        val e = DockMath.edges(rest, pointer = 7.5f * t, r = r, f = f)
        assertTrue(e[6] - e[5] in (t + 0.01f)..large)
        assertEquals(t, e[2] - e[1], 0.01f) // 5.5 cells away
    }

    @Test fun pointPointerIsOnStaysPut() {
        val rest = FloatArray(10) { t }
        val r = DockMath.swing(t, large, f, 0.7f)
        val p = 3.3f * t
        assertEquals(0f, DockMath.displacement(0f, r, f), 0f)
        val e = DockMath.edges(rest, p, r, f)
        // The edge at rest x = 3t lands at 3t + D(3t - p), on the pointer's left.
        assertTrue(e[3] < p && e[4] > p)
    }

    @Test fun noStrengthMeansNoWarp() {
        val rest = floatArrayOf(t, 13f, t, t)
        val e = DockMath.edges(rest, 30f, DockMath.swing(t, large, f, 0f), f)
        assertEquals(listOf(0f, t, t + 13f, 2 * t + 13f, 3 * t + 13f), e.toList())
    }

    @Test fun rampMatchesTheDockFormula() = assertEquals(160, DockMath.rampMillis(87f))
}

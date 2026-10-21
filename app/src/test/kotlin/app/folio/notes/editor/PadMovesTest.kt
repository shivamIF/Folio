package app.folio.notes.editor

import app.folio.core.model.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class PadMovesTest {
    private val pw = 1000f
    private val ph = 1414f
    private val m = PadMoves.MARGIN

    @Test fun firstWindowStartsAtTheLeftMarginWithThePadsShape() {
        val r = PadMoves.initial(pw, ph, Rect(0f, 300f, 1000f, 1000f), aspect = 4f)
        assertEquals(m, r.left)
        assertEquals(340f, r.top)
        assertEquals(4f, r.width / r.height, 1e-3f)
    }

    @Test fun nextMovesByItsWidthThenWrapsToTheNextLine() {
        val r = Rect(m, 100f, m + 300f, 175f)
        val n1 = PadMoves.next(r, pw, ph)
        assertEquals(m + 300f, n1.left)
        val n2 = PadMoves.next(n1, pw, ph)
        assertEquals(m + 600f, n2.left)
        val wrapped = PadMoves.next(n2, pw, ph) // 940 + 300 > 960: next line
        assertEquals(m, wrapped.left)
        assertEquals(175f, wrapped.top)
    }

    @Test fun enterStartsTheNextLineAtTheMargin() {
        val e = PadMoves.enter(Rect(500f, 100f, 800f, 175f), pw, ph)
        assertEquals(Rect(m, 175f, m + 300f, 250f), e)
    }

    @Test fun previousFromTheLineStartGoesToTheEndOfTheLineAbove() {
        val r = Rect(m, 175f, m + 300f, 250f)
        val p = PadMoves.previous(r, pw, ph)
        assertEquals(100f, p.top)
        assertEquals(pw - m, p.right)
        // In the middle of a line it just steps back.
        assertEquals(m, PadMoves.previous(Rect(m + 300f, 100f, m + 600f, 175f), pw, ph).left)
    }

    @Test fun writingPastTheEndMarkerAdvancesTheWindow() {
        val r = Rect(m, 100f, m + 300f, 175f)
        assertEquals(r, PadMoves.advance(r, m + 200f, pw, ph)) // before the marker: stays
        val a = PadMoves.advance(r, m + 280f, pw, ph)
        assertEquals(m + 280f - 36f, a.left, 1e-3f) // the writing's end comes back near the left
        val atEnd = PadMoves.advance(Rect(650f, 100f, 950f, 175f), 940f, pw, ph)
        assertEquals(m, atEnd.left) // no room: next line
    }

    @Test fun resizeKeepsTheShape() {
        val r = PadMoves.resize(Rect(m, 100f, m + 300f, 175f), m + 400f, 120f, 4f, pw, ph)
        assertEquals(400f, r.width, 1e-3f)
        assertEquals(100f, r.height, 1e-3f)
    }
}

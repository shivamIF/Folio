package app.folio.notes.editor

import app.folio.core.model.Rect
import app.folio.core.model.StickyNote

/**
 * Where a sticky note's parts are, in page units: shared by the renderer and the canvas's
 * hit-testing, so what you tap is exactly what's drawn.
 */
object StickyGeometry {
    /** Tape across the top edge: centred, tilted by [TAPE_ANGLE] degrees. */
    const val TAPE_ANGLE = -9f
    private const val TAPE_W = 0.3f
    private const val TAPE_H = 0.085f
    /** A minimised note's tab (page units). */
    const val TAB = 44f

    class Tape(val cx: Float, val cy: Float, val w: Float, val h: Float)

    fun tape(n: StickyNote) = Tape(n.x + n.width / 2, n.y + n.height * 0.01f, n.width * TAPE_W, maxOf(n.height * TAPE_H, 18f))

    /** The tape's tap target: its box, a little larger (it's tilted). */
    fun tapeTarget(n: StickyNote, minHeight: Float): Rect {
        val t = tape(n)
        val h = maxOf(t.h * 1.5f, minHeight)
        return Rect(t.cx - t.w * 0.55f, t.cy - h / 2, t.cx + t.w * 0.55f, t.cy + h / 2)
    }

    /** The collapsed tab: where the tape sits (top centre of the note). */
    fun tab(n: StickyNote): Rect {
        val cx = n.x + n.width / 2; val cy = n.y + TAB * 0.3f
        return Rect(cx - TAB / 2, cy - TAB / 2, cx + TAB / 2, cy + TAB / 2)
    }

    /** The Minimise control at the note's top-right corner. */
    fun minimise(n: StickyNote): Rect {
        val s = (n.width * 0.075f).coerceIn(22f, 40f)
        val pad = s * 0.45f
        return Rect(n.x + n.width - pad - s, n.y + pad + n.height * 0.02f, n.x + n.width - pad, n.y + pad + n.height * 0.02f + s)
    }
}

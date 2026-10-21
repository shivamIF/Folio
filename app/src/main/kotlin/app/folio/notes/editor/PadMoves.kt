package app.folio.notes.editor

import app.folio.core.model.Rect

/** The easy writing pad's window on a page (page units). */
data class PadWindow(val pageId: String, val rect: Rect)

/**
 * How the easy writing pad's window moves over the page (teardown §14), as pure functions:
 * Next moves right by the window's width; at the end of a line it goes to the start of the next
 * line. Enter starts the next line at the left margin. Previous moves left, and from the start
 * of a line goes to the end of the line above. Writing past the end marker advances the window
 * so the end of the writing comes back near the left of the pad.
 */
object PadMoves {
    /** Margins kept clear of the window (page units). */
    const val MARGIN = 40f
    /** The end marker sits this far across the pad. */
    const val END_MARKER = 0.86f

    /** The first window on a page: at the left margin, near the top of [visible], [width] wide with [aspect] (w / h). */
    fun initial(pageW: Float, pageH: Float, visible: Rect, aspect: Float, widthShare: Float = 0.4f): Rect {
        val w = ((pageW - 2 * MARGIN) * widthShare).coerceAtLeast(120f)
        val h = (w / aspect.coerceIn(1.2f, 12f)).coerceAtMost(pageH - 2 * MARGIN)
        val top = (visible.top + MARGIN).coerceIn(MARGIN, maxOf(MARGIN, pageH - MARGIN - h))
        return Rect(MARGIN, top, MARGIN + w, top + h)
    }

    fun next(r: Rect, pageW: Float, pageH: Float): Rect =
        if (r.right + r.width <= pageW - MARGIN + 1f) r.shift(r.width, 0f) else enter(r, pageW, pageH)

    fun enter(r: Rect, pageW: Float, pageH: Float): Rect {
        val top = (r.top + r.height).coerceAtMost(maxOf(MARGIN, pageH - MARGIN - r.height))
        return Rect(MARGIN, top, MARGIN + r.width, top + r.height)
    }

    fun previous(r: Rect, pageW: Float, pageH: Float): Rect {
        if (r.left - r.width >= MARGIN - 1f) return r.shift(-r.width, 0f)
        if (r.left > MARGIN + 1f) return Rect(MARGIN, r.top, MARGIN + r.width, r.bottom)
        val top = (r.top - r.height).coerceAtLeast(MARGIN)
        val left = (pageW - MARGIN - r.width).coerceAtLeast(MARGIN)
        return Rect(left, top, left + r.width, top + r.height)
    }

    /** After a stroke reached [inkRight] (page x): advance if it passed the end marker. */
    fun advance(r: Rect, inkRight: Float, pageW: Float, pageH: Float): Rect {
        if (inkRight < r.left + r.width * END_MARKER) return r
        val step = (inkRight - r.left) - r.width * 0.12f
        val moved = r.shift(step, 0f)
        return if (moved.right <= pageW - MARGIN + 1f) moved else enter(r, pageW, pageH)
    }

    /** [r] resized from its top-left to reach ([x], [y]), keeping [aspect] and staying on the page. */
    fun resize(r: Rect, x: Float, y: Float, aspect: Float, pageW: Float, pageH: Float): Rect {
        val w = maxOf(x - r.left, (y - r.top) * aspect).coerceIn(80f, pageW - MARGIN - r.left)
        val h = (w / aspect).coerceAtMost(pageH - r.top)
        return Rect(r.left, r.top, r.left + h * aspect, r.top + h)
    }

    private fun Rect.shift(dx: Float, dy: Float) = Rect(left + dx, top + dy, right + dx, bottom + dy)
}

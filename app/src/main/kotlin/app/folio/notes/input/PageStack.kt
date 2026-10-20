package app.folio.notes.input

/**
 * The notebook's pages in one vertical column, for continuous scrolling (ADR-10).
 *
 * Positions are in "document units": page units, with each page shifted to its place in the
 * column. Pages keep their own sizes, sit [gap] apart in `pageIds` order and are centred on
 * the widest page; there's no margin above the first page or below the last. A page's content
 * only moves by a translation ([left], [top]), so one zoom applies to every page.
 *
 * Immutable, pure layout (no Android), shared by the view model and the canvas.
 */
class PageStack(val ids: List<String>, widths: FloatArray, heights: FloatArray, val gap: Float = GAP) {
    private val w = widths.copyOf()
    private val h = heights.copyOf()
    private val tops = FloatArray(ids.size)
    private val byId = HashMap<String, Int>(ids.size * 2)

    /** Width of the widest page. */
    val width: Float
    /** Total height of the column, gaps included. */
    val height: Float

    init {
        require(w.size == ids.size && h.size == ids.size) { "one size per page" }
        var y = 0f
        var widest = 0f
        for (i in ids.indices) {
            require(w[i] > 0f && h[i] > 0f) { "page sizes must be positive" }
            tops[i] = y
            y += h[i] + gap
            widest = maxOf(widest, w[i])
            byId[ids[i]] = i
        }
        height = if (ids.isEmpty()) 0f else y - gap
        width = widest
    }

    val count: Int get() = ids.size

    fun indexOf(id: String): Int = byId[id] ?: -1
    fun top(i: Int): Float = tops[i]
    fun bottom(i: Int): Float = tops[i] + h[i]
    /** Left edge of page [i]: pages are centred on the widest one. */
    fun left(i: Int): Float = (width - w[i]) / 2f
    fun pageWidth(i: Int): Float = w[i]
    fun pageHeight(i: Int): Float = h[i]

    /** Top of the last page (0 when empty): scrolling may go on until it reaches the top of the view. */
    val lastTop: Float get() = if (ids.isEmpty()) 0f else tops[ids.size - 1]

    /**
     * The page at document height [y]; in a gap the nearer of the two pages, and the first or
     * last page beyond the ends. -1 only when there are no pages. This is the page the pen
     * writes on (beside a narrow page, the page level with the pen).
     */
    fun nearest(y: Float): Int {
        if (ids.isEmpty()) return -1
        val i = lastTopAtOrAbove(y)
        if (i < 0) return 0
        if (y <= bottom(i) || i == ids.size - 1) return i
        return if (y - bottom(i) <= top(i + 1) - y) i else i + 1
    }

    /** Pages overlapping the document band [y0]..[y1]; empty when it holds only a gap or lies outside. */
    fun overlapping(y0: Float, y1: Float): IntRange {
        if (ids.isEmpty() || y1 < 0f || y0 > height || y1 < y0) return IntRange.EMPTY
        val last = lastTopAtOrAbove(y1)
        if (last < 0) return IntRange.EMPTY
        var first = lastTopAtOrAbove(y0).coerceAtLeast(0)
        if (bottom(first) < y0) first++
        return if (first > last) IntRange.EMPTY else first..last
    }

    /** Same pages, in the same order, at the same sizes (a layout change can be skipped). */
    fun sameLayout(other: PageStack?): Boolean =
        other != null && other.ids == ids && other.gap == gap && other.w.contentEquals(w) && other.h.contentEquals(h)

    /** Index of the last page whose top is at or above [y], or -1. */
    private fun lastTopAtOrAbove(y: Float): Int {
        var lo = 0; var hi = ids.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] <= y) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        /** Space between pages, in page units (about 5 mm on an A4 page). */
        const val GAP = 24f

        /**
         * Where the page in view is read from: this fraction of the way down the view. After
         * "go to page N" (page N's top at the view's top) it always lands inside page N.
         */
        const val READING_LINE = 0.3f

        /** A stack of [ids], each sized by [size] (width to height). */
        fun of(ids: List<String>, size: (String) -> Pair<Float, Float>): PageStack {
            val ws = FloatArray(ids.size); val hs = FloatArray(ids.size)
            ids.forEachIndexed { i, id -> val (pw, ph) = size(id); ws[i] = pw; hs[i] = ph }
            return PageStack(ids, ws, hs)
        }
    }
}

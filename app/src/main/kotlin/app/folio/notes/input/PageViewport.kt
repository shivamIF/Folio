package app.folio.notes.input

/**
 * Maps page units to view pixels: `view = page * scale + (tx, ty)`.
 *
 * The page is locked to the screen:
 * - a notebook opens at fit-width (comfortable for writing);
 * - you can zoom out until the whole page is visible, but no further;
 * - along any axis where the page is smaller than the view it stays centred;
 *   along an axis where it's larger, panning stops exactly at the page edges.
 */
class PageViewport(private val maxZoom: Float = 5f) {
    var viewW = 0f; private set
    var viewH = 0f; private set
    var pageW = 1000f; private set
    var pageH = 1414f; private set
    var scale = 1f; private set
    var tx = 0f; private set
    var ty = 0f; private set

    /** Scale at which the page width exactly fills the view. */
    val fitWidthScale: Float get() = if (pageW > 0f && viewW > 0f) viewW / pageW else 1f
    /** Scale at which the whole page is visible. */
    val fitPageScale: Float get() = if (viewH > 0f) minOf(fitWidthScale, viewH / pageH) else fitWidthScale
    /** Smallest scale: half of whole-page, page centred with space around it. */
    val minScale: Float get() = fitPageScale * MIN_OF_FIT_PAGE
    val maxScale: Float get() = fitWidthScale * maxZoom
    val ready: Boolean get() = viewW > 0f && viewH > 0f

    /** Zoom relative to fit-width (1 = fit width, <1 = zoomed out towards whole page). */
    val zoom: Float get() = scale / fitWidthScale

    fun setPage(w: Float, h: Float) {
        if (w == pageW && h == pageH) return
        pageW = w; pageH = h
        fitWidth()
    }

    /**
     * Resizes the view (rotation, split screen) keeping the same zoom level and
     * the same page point at the top-centre, so the user doesn't lose their place.
     */
    fun resize(w: Float, h: Float) {
        if (!ready) {
            viewW = w; viewH = h
            fitWidth()
            return
        }
        val z = zoom
        val anchorX = (viewW / 2f - tx) / scale
        val anchorY = (0f - ty) / scale
        viewW = w; viewH = h
        scale = (fitWidthScale * z).coerceIn(minScale, maxScale)
        tx = viewW / 2f - anchorX * scale
        ty = -anchorY * scale
        clamp()
    }

    fun fitWidth() {
        scale = fitWidthScale
        tx = 0f
        ty = 0f
        clamp()
    }

    fun fitWholePage() {
        scale = fitPageScale
        clamp()
    }

    /** Multiplies the zoom by [factor] around the focal point (fx, fy) in view pixels. */
    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (scale * factor).coerceIn(minScale, maxScale)
        val f = target / scale
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scale = target
        clamp()
    }

    /** Pans by (dx, dy) view pixels. Returns the amount actually applied, so a fling knows when it hits an edge. */
    fun panBy(dx: Float, dy: Float): Pair<Float, Float> {
        val ox = tx; val oy = ty
        tx += dx; ty += dy
        clamp()
        return (tx - ox) to (ty - oy)
    }

    fun clamp() {
        val sw = pageW * scale
        val sh = pageH * scale
        tx = if (sw <= viewW) (viewW - sw) / 2f else tx.coerceIn(viewW - sw, 0f)
        ty = if (sh <= viewH) (viewH - sh) / 2f else ty.coerceIn(viewH - sh, 0f)
    }

    fun toPage(vx: Float, vy: Float): Pair<Float, Float> = (vx - tx) / scale to (vy - ty) / scale
    fun toView(px: Float, py: Float): Pair<Float, Float> = px * scale + tx to py * scale + ty

    /** Scroll range in view pixels, for fling bounds: minX..0 and minY..0. */
    fun minTx(): Float = minOf(0f, viewW - pageW * scale)
    fun minTy(): Float = minOf(0f, viewH - pageH * scale)

    companion object {
        const val MIN_OF_FIT_PAGE = 0.5f
    }
}

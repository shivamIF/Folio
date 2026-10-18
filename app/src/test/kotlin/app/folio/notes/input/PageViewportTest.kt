package app.folio.notes.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageViewportTest {
    private fun landscape() = PageViewport().apply { resize(2000f, 1100f) } // Tab S6 Lite landscape-ish
    private fun portrait() = PageViewport().apply { resize(1200f, 1800f) }

    @Test fun fitsPageWidthToScreen() {
        val v = landscape()
        assertEquals(2.0f, v.scale, 1e-4f)
        val (left, top) = v.toView(0f, 0f)
        val (right, _) = v.toView(1000f, 0f)
        assertEquals(0f, left); assertEquals(0f, top); assertEquals(2000f, right)
    }

    @Test fun zoomOutStopsAtWholePageCentred() {
        val v = landscape()
        repeat(10) { v.zoomBy(0.5f, 300f, 200f) }
        assertEquals(v.minScale, v.scale)
        assertEquals(v.fitPageScale * PageViewport.MIN_OF_FIT_PAGE, v.scale, 1e-4f)
        val (l, t) = v.toView(0f, 0f)
        val (r, b) = v.toView(1000f, 1414f)
        assertEquals(550f, b - t, 0.01f) // half of the whole-page height
        assertEquals(1100f - b, t, 0.01f) // centred vertically
        assertEquals(2000f - r, l, 0.01f) // centred horizontally
        v.panBy(500f, 300f)
        assertEquals(l, v.toView(0f, 0f).first, 0.01f, "fully zoomed-out page can't be dragged off centre")
    }

    @Test fun zoomIsCapped() {
        val v = portrait()
        repeat(20) { v.zoomBy(2f, 600f, 900f) }
        assertEquals(v.maxScale, v.scale, 1e-3f)
    }

    @Test fun horizontalPanIsLockedAtFitWidth() {
        val v = landscape()
        v.panBy(300f, 0f)
        assertEquals(0f, v.tx)
        v.panBy(-300f, 0f)
        assertEquals(0f, v.tx)
    }

    @Test fun verticalScrollStopsAtPageEdges() {
        val v = landscape()
        v.panBy(0f, 500f)
        assertEquals(0f, v.ty, "can't scroll above the page top")
        v.panBy(0f, -100_000f)
        val (_, bottom) = v.toView(0f, 1414f)
        assertEquals(1100f, bottom, 1e-2f) // page bottom meets screen bottom, no gap
    }

    @Test fun zoomKeepsFocalPointUnderFingers() {
        val v = portrait()
        v.panBy(0f, -400f)
        val before = v.toPage(600f, 900f)
        v.zoomBy(2f, 600f, 900f)
        val after = v.toPage(600f, 900f)
        assertEquals(before.first, after.first, 0.01f)
        assertEquals(before.second, after.second, 0.01f)
    }

    @Test fun zoomedInPanStaysInsidePage() {
        val v = portrait()
        v.zoomBy(3f, 0f, 0f)
        v.panBy(-1e6f, -1e6f)
        val (right, bottom) = v.toView(1000f, 1414f)
        assertEquals(1200f, right, 0.01f); assertEquals(1800f, bottom, 0.01f)
        v.panBy(1e6f, 1e6f)
        assertEquals(0f, v.tx); assertEquals(0f, v.ty)
    }

    @Test fun rotationKeepsZoomAndPlace() {
        val v = portrait()
        v.zoomBy(2f, 600f, 0f)
        v.panBy(0f, -1000f)
        val z = v.zoom
        val topCentre = v.toPage(600f, 0f)
        v.resize(2000f, 1100f)
        assertEquals(z, v.zoom, 1e-3f)
        val after = v.toPage(1000f, 0f)
        assertTrue(kotlin.math.abs(after.second - topCentre.second) < 1f, "same page line stays at the top")
    }

    @Test fun pageShorterThanViewIsCentred() {
        val v = PageViewport().apply { resize(3000f, 6000f) }
        v.panBy(0f, 500f)
        val (_, top) = v.toView(0f, 0f); val (_, bottom) = v.toView(0f, 1414f)
        assertEquals(6000f - bottom, top, 0.01f)
    }

    @Test fun fitWholePageShowsEntirePageCentred() {
        val p = portrait()
        p.fitWholePage()
        val (l, t) = p.toView(0f, 0f); val (r, b) = p.toView(1000f, 1414f)
        assertTrue(l >= -0.01f && t >= -0.01f && r <= 1200.01f && b <= 1800.01f, "entire page visible")
        assertEquals(1200f - r, l, 0.01f); assertEquals(1800f - b, t, 0.01f)
    }
}

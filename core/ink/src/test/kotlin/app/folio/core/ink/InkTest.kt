package app.folio.core.ink

import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PenProfileTest {
    @Test fun pressureWidensPressureSensitivePens() {
        val p = PenProfile.of(ToolType.FOUNTAIN)
        assertTrue(p.widthAt(10f, 1f, 0f) > p.widthAt(10f, 0.2f, 0f))
        assertEquals(10f, p.widthAt(10f, 1f, 0f), 1e-4f)
        assertEquals(3.5f, p.widthAt(10f, 0f, 0f), 1e-4f)
    }

    @Test fun highlighterIgnoresPressure() {
        val p = PenProfile.of(ToolType.HIGHLIGHTER)
        assertEquals(p.widthAt(20f, 0.1f, 0f), p.widthAt(20f, 1f, 0f))
    }

    @Test fun tiltWidensPencil() {
        val p = PenProfile.of(ToolType.PENCIL)
        assertTrue(p.widthAt(4f, 0.5f, 1.3f) > p.widthAt(4f, 0.5f, 0f))
    }

    @Test fun naturalWritingTiltDoesNotShade() {
        // 0.64–0.94 rad measured during normal writing on the Tab.
        val p = PenProfile.of(ToolType.PENCIL)
        assertEquals(p.widthAt(4f, 0.5f, 0f), p.widthAt(4f, 0.5f, 0.85f))
    }

    @Test fun outOfRangePressureIsClamped() {
        val p = PenProfile.of(ToolType.BRUSH)
        assertEquals(p.widthAt(5f, 1f, 0f), p.widthAt(5f, 3f, 0f))
    }
}

class StrokeBuilderTest {
    private fun line(stab: Float): Stroke {
        val b = StrokeBuilder(ToolType.BALLPOINT, 0xFF000000, 3f, stabilization = stab)
        for (i in 0..50) b.add(i * 2f, if (i % 2 == 0) 0f else 1.5f, 0.6f, 0f, 0f, i * 4L)
        b.finishAt(100f, 0f, 0.6f, 0f, 0f, 204)
        return b.build()
    }

    private fun jitter(s: Stroke): Float {
        var sum = 0f
        for (i in 1 until s.size) sum += abs(s.y[i] - s.y[i - 1])
        return sum / (s.size - 1)
    }

    @Test fun stabilisationReducesJitter() {
        assertTrue(jitter(line(0.9f)) < jitter(line(0f)))
    }

    @Test fun strokeEndsExactlyAtPenUp() {
        val s = line(1f)
        assertEquals(100f, s.x.last()); assertEquals(0f, s.y.last())
    }

    @Test fun timestampsAreMonotonic() {
        val s = line(0.5f)
        for (i in 1 until s.size) assertTrue(s.t[i] >= s.t[i - 1])
    }

    @Test fun nearDuplicateSamplesAreMerged() {
        val b = StrokeBuilder(ToolType.BALLPOINT, 0, 3f, stabilization = 0f, minDistance = 1f)
        repeat(20) { b.add(10f, 10f, 0.5f, 0f, 0f, it.toLong()) }
        assertEquals(1, b.size)
    }

    @Test fun manyPointsGrowBuffers() {
        val b = StrokeBuilder(ToolType.BALLPOINT, 0, 3f, stabilization = 0f)
        repeat(1000) { b.add(it.toFloat(), 0f, 0.5f, 0f, 0f, it.toLong()) }
        assertEquals(1000, b.build().size)
    }
}

class StrokeGeometryTest {
    private fun stroke(vararg pts: Float, width: Float = 4f): Stroke {
        val n = pts.size / 2
        return Stroke(
            tool = ToolType.BALLPOINT, color = 0, width = width,
            x = FloatArray(n) { pts[2 * it] }, y = FloatArray(n) { pts[2 * it + 1] },
            pressure = FloatArray(n) { 1f }, tilt = FloatArray(n), orientation = FloatArray(n), t = IntArray(n),
        )
    }

    @Test fun outlineCoversStrokeWidth() {
        val s = stroke(0f, 0f, 50f, 0f, 100f, 0f)
        val o = StrokeGeometry.outline(s)
        assertTrue(o.size >= 12 && o.size % 2 == 0)
        val ys = o.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(2f, ys.max(), 0.05f)
        assertEquals(-2f, ys.min(), 0.05f)
        val xs = o.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(xs.min() < 0f && xs.max() > 100f, "round caps extend past the endpoints")
    }

    @Test fun singlePointBecomesDot() {
        assertEquals(32, StrokeGeometry.outlineV1(stroke(5f, 5f)).size)
        // v2: a closed round dot of the stroke's half-width around the point.
        val o = StrokeGeometry.outline(stroke(5f, 5f))
        assertTrue(o.size >= 16)
        for (i in o.indices step 2) assertTrue(kotlin.math.hypot(o[i] - 5f, o[i + 1] - 5f) <= 2.01f)
    }

    @Test fun emptyStrokeHasNoOutline() {
        assertEquals(0, StrokeGeometry.outline(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), 0, 3f, PenProfile.of(ToolType.BALLPOINT)).size)
    }

    @Test fun eraserHitsNearbyStrokeOnly() {
        val s = stroke(0f, 0f, 100f, 0f)
        assertTrue(StrokeGeometry.hits(s, 50f, 5f, 4f))
        assertFalse(StrokeGeometry.hits(s, 50f, 20f, 4f))
        assertFalse(StrokeGeometry.hits(s, 200f, 0f, 4f))
    }

    @Test fun fastEraserSwipeDoesNotSkipStrokes() {
        val vertical = stroke(50f, -20f, 50f, 20f).let { Stroke(id = "v", tool = it.tool, color = 0, width = 2f, x = it.x, y = it.y, pressure = it.pressure, tilt = it.tilt, orientation = it.orientation, t = it.t) }
        val far = stroke(500f, 500f, 510f, 500f)
        val hit = StrokeGeometry.hitAlong(listOf(vertical, far), 0f, 0f, 100f, 0f, 3f)
        assertEquals(setOf("v"), hit)
    }
}

class PressureCurveTest {
    @Test fun typicalHandwritingReachesUsefulWidth() {
        // Measured on Galaxy Tab S6 Lite: median raw pressure about 0.3, peaks about 0.6.
        val c = PressureCurve()
        assertEquals(0.5f, c.map(0.3f), 1e-4f)
        assertEquals(1f, c.map(0.6f))
        val fountain = PenProfile.of(ToolType.FOUNTAIN)
        val before = fountain.widthAt(10f, 0.3f, 0f)
        val after = fountain.widthAt(10f, c.map(0.3f), 0f)
        assertTrue(after >= 6.5f && after > before, "median stroke now ${after / 10 * 100}% of nominal width")
    }

    @Test fun clampsAndKeepsOrder() {
        val c = PressureCurve(ceiling = 0.5f)
        assertEquals(0f, c.map(-1f)); assertEquals(1f, c.map(0.9f))
        assertTrue(c.map(0.2f) < c.map(0.3f))
    }

    @Test fun sliderRoundTrips() {
        for (pos in listOf(0f, 0.25f, 0.43f, 1f)) {
            assertEquals(pos, PressureCurve.sensitivityOf(PressureCurve.fromSensitivity(pos).ceiling), 1e-4f)
        }
        assertEquals(PressureCurve.LIGHTEST, PressureCurve.fromSensitivity(0f).ceiling)
    }

    @Test fun floorFiltersNoise() {
        assertEquals(0f, PressureCurve(ceiling = 0.6f, floor = 0.02f).map(0.01f))
    }

    /** How far (in screen px) the stabilised end trails the pen at constant screen speed. */
    private fun trailPx(stab: Float, pxPerUnit: Float, screenPxPerMs: Float): Float {
        val b = StrokeBuilder(ToolType.BALLPOINT, 0, 3f, stabilization = stab,
            minDistance = StrokeBuilder.minDistanceAt(pxPerUnit), pxPerUnit = pxPerUnit)
        val v = screenPxPerMs / pxPerUnit
        var x = 0f
        for (k in 0..120) { x = v * 4f * k; b.add(x, 0f, 0.5f, 0f, 0f, 4L * k) } // 240 Hz
        val s = b.build()
        return (x - s.x[s.size - 1]) * pxPerUnit
    }

    @Test fun stabilisationTrailIsSmallAndTheSameAtEveryZoom() {
        for (speed in listOf(0.3f, 1f)) { // slow and brisk handwriting, px/ms on a Tab S6 Lite
            val fit = trailPx(0.1f, 1.6f, speed)
            val zoomed = trailPx(0.1f, 8f, speed) // 5× zoom
            println("stabilisation 10%%, %.1f px/ms: trail %.2f px at fit, %.2f px at 5× zoom".format(speed, fit, zoomed))
            assertTrue(zoomed < 4f, "trail at 5x: $zoomed px")
            assertEquals(fit, zoomed, 0.5f + 0.15f * fit)
        }
    }
}

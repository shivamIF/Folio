package app.folio.core.ink

import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrushEngineTest {
    private class Pts(val x: FloatArray, val y: FloatArray, val p: FloatArray, val tilt: FloatArray, val ori: FloatArray) {
        val n get() = x.size
    }

    private fun line(n: Int = 40, len: Float = 200f, angle: Float = 0f, p: (Float) -> Float = { 0.6f }, tilt: Float = 0f, ori: Float = 0f) = Pts(
        FloatArray(n) { 100f + cos(angle) * len * it / (n - 1) }, FloatArray(n) { 100f + sin(angle) * len * it / (n - 1) },
        FloatArray(n) { p(it / (n - 1f)) }, FloatArray(n) { tilt }, FloatArray(n) { ori },
    )

    private fun mesh(s: Pts, tool: ToolType, width: Float = 4f) =
        assertNotNull(BrushEngine.mesh(s.x, s.y, s.p, s.tilt, s.ori, s.n, width, BrushSpec.of(tool)))

    /** Width across the stroke at pair k (distance between its left and right edge points). */
    private fun StrokeMesh.widthAt(k: Int) = hypot(left[2 * k] - right[2 * k], left[2 * k + 1] - right[2 * k + 1])

    private fun maxDistanceFromLine(o: FloatArray, y: Float): Float {
        var m = 0f; var i = 1
        while (i < o.size) { m = maxOf(m, kotlin.math.abs(o[i] - y)); i += 2 }
        return m
    }

    @Test fun everyToolGivesAFiniteClosedOutlineWithinItsReach() {
        val loop = Pts(
            FloatArray(120) { 300f + 60f * cos(it * 0.15f) + it }, FloatArray(120) { 300f + 60f * sin(it * 0.15f) },
            FloatArray(120) { 0.2f + 0.6f * sin(it / 119f * PI.toFloat()) }, FloatArray(120) { 1.3f }, FloatArray(120) { 0.5f },
        )
        for (tool in ToolType.entries) {
            val m = mesh(loop, tool, 6f)
            val o = m.outline()
            assertTrue(o.size >= 8 && o.all { it.isFinite() }, "$tool outline")
            // No edge point lies further from the sampled path than the tool's reach
            // (plus the spline's small bulge between samples).
            for (k in 0 until m.pairs) for (e in listOf(m.left, m.right)) {
                val ex = e[2 * k]; val ey = e[2 * k + 1]
                var best = Float.MAX_VALUE
                for (i in 1 until loop.n) best = minOf(best, StrokeGeometry.distanceToSegment(ex, ey, loop.x[i - 1], loop.y[i - 1], loop.x[i], loop.y[i]))
                assertTrue(best <= 6f * tool.inkReach + 1f, "$tool edge $best beyond reach")
            }
            assertEquals(BrushSpec.of(tool).reach, tool.inkReach, 0.02f, "$tool reach used for bounds")
            val t = m.triangles()
            assertEquals(t.verts.size, 2 * t.count)
            assertTrue(t.shadeOf.all { it in 0f..1f })
        }
    }

    @Test fun lightPressureEndsTaperButCutEndsKeepTheirWidth() {
        // Fountain pen: pressure rising from and falling to light gives thin ends.
        val natural = mesh(line(p = { t -> 0.05f + 0.9f * sin(t * PI.toFloat()) }), ToolType.FOUNTAIN)
        val mid = natural.widthAt(natural.pairs / 2)
        assertTrue(natural.widthAt(0) < 0.5f * mid, "start tapers: ${natural.widthAt(0)} vs $mid")
        assertTrue(natural.widthAt(natural.pairs - 1) < 0.5f * mid, "end tapers")
        // A piece cut out of the middle by the partial eraser has steady pressure: no fake taper.
        val cut = mesh(line(p = { 0.8f }), ToolType.FOUNTAIN)
        val cm = cut.widthAt(cut.pairs / 2)
        assertEquals(cm, cut.widthAt(0), cm * 0.12f)
        assertEquals(cm, cut.widthAt(cut.pairs - 1), cm * 0.12f)
    }

    @Test fun brushRangesFromHairlineToFullWhileFineLinerStaysEven() {
        val ramp = line(n = 60, p = { t -> t })
        val brush = mesh(ramp, ToolType.BRUSH, 8f)
        val fine = mesh(ramp, ToolType.FINELINER, 8f)
        val b0 = brush.widthAt(3); val b1 = brush.widthAt(brush.pairs - 4)
        val f0 = fine.widthAt(3); val f1 = fine.widthAt(fine.pairs - 4)
        assertTrue(b1 > 5f * b0, "brush $b0 → $b1")
        assertTrue(f1 < 1.15f * f0, "fine liner $f0 → $f1")
    }

    @Test fun fountainNibIsThinAlongItsEdgeAndThickAcross() {
        val along = mesh(line(angle = (-PI / 4).toFloat()), ToolType.FOUNTAIN)
        val across = mesh(line(angle = (PI / 4).toFloat()), ToolType.FOUNTAIN)
        assertTrue(across.widthAt(across.pairs / 2) > 1.3f * along.widthAt(along.pairs / 2))
    }

    @Test fun tiltedPencilSpreadsAcrossItsAzimuthOnly() {
        val tilt = 1.35f // pen nearly flat
        // Azimuth pointing up the screen (orientation 0): moving sideways sweeps the long axis.
        val sideways = mesh(line(tilt = tilt, ori = 0f), ToolType.PENCIL)
        val upright = mesh(line(tilt = 0.5f, ori = 0f), ToolType.PENCIL)
        val along = mesh(line(angle = (PI / 2).toFloat(), tilt = tilt, ori = 0f), ToolType.PENCIL)
        val w = sideways.widthAt(sideways.pairs / 2)
        assertTrue(w > 2.5f * upright.widthAt(upright.pairs / 2), "shading is broad")
        assertTrue(along.widthAt(along.pairs / 2) < 0.5f * w, "moving along the azimuth stays narrow")
        // Broad side shading is lighter; pressure darkens.
        assertTrue(sideways.shade[sideways.pairs / 2] < upright.shade[upright.pairs / 2])
        val hard = mesh(line(p = { 1f }), ToolType.PENCIL); val soft = mesh(line(p = { 0.1f }), ToolType.PENCIL)
        assertTrue(hard.shade[5] > soft.shade[5] + 0.3f)
    }

    @Test fun withoutTiltDataThePencilFootprintStaysRound() {
        // Devices that report no tilt give tilt = orientation = 0: nothing is invented.
        val a = mesh(line(tilt = 0f, ori = 0f), ToolType.PENCIL)
        val b = mesh(line(angle = (PI / 2).toFloat(), tilt = 0f, ori = 0f), ToolType.PENCIL)
        assertEquals(a.widthAt(a.pairs / 2), b.widthAt(b.pairs / 2), 0.05f)
    }

    @Test fun dotsAndTwoPointStrokes() {
        val dot = mesh(Pts(floatArrayOf(50f), floatArrayOf(50f), floatArrayOf(0.5f), floatArrayOf(0f), floatArrayOf(0f)), ToolType.BALLPOINT)
        assertTrue(dot.outline().size >= 8)
        val two = mesh(Pts(floatArrayOf(50f, 50.1f), floatArrayOf(50f, 50f), floatArrayOf(0.5f, 0.5f), FloatArray(2), FloatArray(2)), ToolType.FOUNTAIN)
        assertTrue(two.outline().all { it.isFinite() })
        val seg = mesh(Pts(floatArrayOf(0f, 30f), floatArrayOf(0f, 0f), floatArrayOf(0.5f, 0.5f), FloatArray(2), FloatArray(2)), ToolType.MARKER)
        assertTrue(seg.pairs >= 2)
    }

    @Test fun hairpinTurnsKeepTheirFullWidthAtTheTip() {
        // Up and straight back down: the turn gets a round join, not a notch.
        val n = 41
        val xs = FloatArray(n) { 100f + if (it <= 20) 0f else 0.4f }
        val ys = FloatArray(n) { if (it <= 20) 100f - it * 4f else 20f + (it - 20) * 4f }
        val m = mesh(Pts(xs, ys, FloatArray(n) { 0.7f }, FloatArray(n), FloatArray(n)), ToolType.BALLPOINT, 6f)
        val o = m.outline()
        var minY = Float.MAX_VALUE; var i = 1
        while (i < o.size) { minY = minOf(minY, o[i]); i += 2 }
        // The tip at y = 20 is covered by about the stroke's half-width beyond it.
        assertTrue(minY < 20f - 1.8f, "tip reaches $minY")
        assertTrue(o.all { it.isFinite() })
    }

    @Test fun curvesAreSmoothNotFaceted() {
        // A circle sampled at only 12 points: v2 edge points lie close to the true round outline.
        val n = 13
        val r = 30f
        val pts = Pts(FloatArray(n) { 200f + r * cos(it * 2 * PI.toFloat() / 12) }, FloatArray(n) { 200f + r * sin(it * 2 * PI.toFloat() / 12) },
            FloatArray(n) { 0.6f }, FloatArray(n), FloatArray(n))
        val m = mesh(pts, ToolType.FINELINER, 2f)
        var worst = 0f
        // The ends extend the curve straight (no neighbour sample to bend towards): skip them.
        for (k in m.pairs / 10 until m.pairs - m.pairs / 10) {
            val cx = (m.left[2 * k] + m.right[2 * k]) / 2f; val cy = (m.left[2 * k + 1] + m.right[2 * k + 1]) / 2f
            worst = maxOf(worst, kotlin.math.abs(hypot(cx - 200f, cy - 200f) - r))
        }
        // Straight chords between 12 samples would sag r(1 − cos 15°) ≈ 1.0 unit.
        assertTrue(worst < 0.35f, "deviation $worst")
        assertTrue(m.pairs > 40)
    }

    @Test fun buildingIsFastEnoughToRedoEveryFrame() {
        // The live stroke is rebuilt on each input frame: a long 400-sample stroke must stay cheap.
        val s = Pts(
            FloatArray(400) { 100f + it * 2f + 20f * sin(it * 0.3f) }, FloatArray(400) { 100f + 30f * cos(it * 0.21f) },
            FloatArray(400) { 0.5f + 0.3f * sin(it * 0.05f) }, FloatArray(400) { 1.2f }, FloatArray(400) { 0.4f },
        )
        repeat(50) { BrushEngine.mesh(s.x, s.y, s.p, s.tilt, s.ori, s.n, 3f, BrushSpec.of(ToolType.PENCIL))!!.triangles() } // warm up
        val t0 = System.nanoTime()
        repeat(200) { BrushEngine.mesh(s.x, s.y, s.p, s.tilt, s.ori, s.n, 3f, BrushSpec.of(ToolType.PENCIL))!!.triangles() }
        val ms = (System.nanoTime() - t0) / 1e6 / 200
        println("BrushEngine: 400-sample pencil stroke %.3f ms".format(ms))
        assertTrue(ms < 3.0, "took $ms ms")
    }

    @Test fun squareCapsForChiselTools() {
        val m = mesh(line(n = 10, len = 50f), ToolType.HIGHLIGHTER, 18f)
        assertEquals(4, m.endCap.size)
        // Constant width whatever the pressure.
        val p = mesh(line(n = 10, len = 50f, p = { t -> t }), ToolType.HIGHLIGHTER, 18f)
        assertEquals(p.widthAt(1), p.widthAt(p.pairs - 2), 0.01f)
        assertTrue(maxDistanceFromLine(m.outline(), 100f) <= 9.01f)
    }

    // ---- pen styles from the Samsung Notes / GoodNotes survey (docs/research/pen-styles.md)

    @Test fun pigmentToolsDarkenWithPressureAndSolidInkDoesNot() {
        for (tool in listOf(ToolType.PENCIL, ToolType.AIRBRUSH)) {
            val hard = mesh(line(p = { 1f }), tool); val soft = mesh(line(p = { 0.1f }), tool)
            assertTrue(hard.shade[hard.pairs / 2] > soft.shade[soft.pairs / 2] + 0.2f, "$tool pressure darkens")
        }
        for (tool in listOf(ToolType.BALLPOINT, ToolType.FOUNTAIN, ToolType.FINELINER, ToolType.MARKER, ToolType.BRUSH)) {
            assertTrue(mesh(line(p = { 0.1f }), tool).shade.all { it == 1f }, "$tool is solid ink")
        }
        // Graphite keeps its tuning: full press upright is full darkness, no press is 0.28.
        val g = BrushSpec.of(ToolType.PENCIL)
        assertEquals(1f, BrushEngine.shadeOf(1f, 0f, g), 1e-5f)
        assertEquals(0.28f, BrushEngine.shadeOf(0f, 0f, g), 1e-5f)
        // A light touch on the airbrush is a faint mist.
        assertTrue(BrushEngine.shadeOf(0.1f, 0f, BrushSpec.of(ToolType.AIRBRUSH)) < 0.3f)
    }

    @Test fun airbrushWidthStaysEvenAndItsBlurIsInItsReach() {
        val ramp = line(n = 60, p = { t -> t })
        val ab = mesh(ramp, ToolType.AIRBRUSH, 12f)
        assertTrue(ab.widthAt(ab.pairs - 4) < 1.5f * ab.widthAt(3), "spray width is steady")
        // The blur is counted in the reach used for bounds and erasing.
        assertTrue(BrushSpec.of(ToolType.AIRBRUSH).soften > 0f && ToolType.AIRBRUSH.inkReach >= 1f)
    }

    /**
     * Every tool on the shelf must look different from every other: compare a signature made of
     * width response (light vs firm pressure, along vs across a 45° nib, tilt), shading,
     * edge raggedness, caps and renderer edge effects.
     */
    @Test fun everyPenStyleIsDistinct() {
        fun mid(m: StrokeMesh) = m.widthAt(m.pairs / 2)
        fun signature(tool: ToolType): List<Float> {
            val spec = BrushSpec.of(tool)
            val light = mesh(line(p = { 0.15f }), tool, 6f); val firm = mesh(line(p = { 1f }), tool, 6f)
            val a45 = mesh(line(angle = (-PI / 4).toFloat()), tool, 6f); val c45 = mesh(line(angle = (PI / 4).toFloat()), tool, 6f)
            val tilted = mesh(line(tilt = 1.35f, ori = 0f), tool, 6f)
            val rag = mesh(line(n = 80, len = 300f), tool, 6f)
            val rw = (4 until rag.pairs - 4).map { rag.widthAt(it) }
            return listOf(
                mid(light) / mid(firm), mid(a45) / mid(c45), mid(tilted) / mid(firm),
                light.shade[light.pairs / 2], firm.shade[firm.pairs / 2],
                (rw.maxOrNull()!! - rw.minOrNull()!!) / rw.average().toFloat(),
                if (spec.cap == CapStyle.SQUARE) 1f else 0f, spec.soften, if (spec.profile.pressureSensitive) 0f else 1f,
            )
        }
        val sig = ToolType.entries.associateWith(::signature)
        for ((i, a) in ToolType.entries.withIndex()) for (b in ToolType.entries.drop(i + 1)) {
            val d = sig[a]!!.zip(sig[b]!!).maxOf { (x, y) -> kotlin.math.abs(x - y) }
            assertTrue(d > 0.04f, "$a and $b look alike: ${sig[a]} vs ${sig[b]}")
        }
    }
}

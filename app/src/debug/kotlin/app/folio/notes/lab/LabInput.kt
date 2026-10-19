package app.folio.notes.lab

import app.folio.core.ink.StrokeBuilder
import app.folio.core.model.Argb
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Brush Lab input: captured or scripted samples, replayed through every engine.
// Pure Kotlin so it can be unit-tested on the JVM.

/**
 * One input sample in page units, before Folio's stabilisation. [pressure] is
 * writing effort, already mapped through the pressure curve (what the app stores).
 */
data class RawSample(
    val x: Float, val y: Float, val pressure: Float,
    val tilt: Float, val orientation: Float, val timeMs: Long,
)

/** One pen-down to pen-up. The last sample is the pen-up location. */
class RawStroke(val samples: List<RawSample>) {
    init { require(samples.isNotEmpty()) { "a stroke needs at least one sample" } }

    /** Runs the samples through the same builder the editor uses (add per move, finishAt on pen-up). */
    fun toStroke(tool: ToolType, color: Argb, width: Float, stabilization: Float = 0.3f): Stroke {
        val first = samples.first()
        val b = StrokeBuilder(tool, color, width, stabilization, startedAt = first.timeMs)
        for (i in 0 until samples.size - 1) samples[i].let { b.add(it.x, it.y, it.pressure, it.tilt, it.orientation, it.timeMs) }
        samples.last().let { b.finishAt(it.x, it.y, it.pressure, it.tilt, it.orientation, it.timeMs) }
        return b.build()
    }
}

/** Which AndroidX Ink stock brush stands in for a preset. */
enum class InkFamily { PRESSURE_PEN, MARKER, HIGHLIGHTER }

/** A Lab preset: one of the shelf's tools with its default width and a typical colour. */
data class LabPreset(
    val id: String,
    val label: String,
    val tool: ToolType,
    val width: Float,
    val color: Argb,
    val ink: InkFamily,
    val note: String? = null,
)

val LAB_PRESETS = listOf(
    LabPreset("ballpoint", "Ballpoint", ToolType.BALLPOINT, 2.5f, 0xFF1B1B1F, InkFamily.MARKER),
    LabPreset("fountain", "Fountain", ToolType.FOUNTAIN, 3.5f, 0xFF1B1B1F, InkFamily.PRESSURE_PEN),
    LabPreset("fineliner", "Fine liner", ToolType.FINELINER, 1.8f, 0xFF1B1B1F, InkFamily.MARKER),
    LabPreset(
        "pencil", "Pencil", ToolType.PENCIL, 3f, 0xFF3A3A3A, InkFamily.PRESSURE_PEN,
        note = "Ink's pencil brush is experimental and needs a texture store: Ink shows a pressure pen.",
    ),
    LabPreset("brushpen", "Brush pen", ToolType.BRUSH, 8f, 0xFF1B1B1F, InkFamily.PRESSURE_PEN),
    LabPreset("highlighter", "Highlighter", ToolType.HIGHLIGHTER, 18f, 0xFFF9D71C, InkFamily.HIGHLIGHTER),
    LabPreset("marker", "Marker", ToolType.MARKER, 6f, 0xFF1565C0, InkFamily.MARKER),
    LabPreset("airbrush", "Airbrush", ToolType.AIRBRUSH, 14f, 0xFF6A1B9A, InkFamily.MARKER, note = "Ink has no soft brush."),
)

/** Android reports orientation in -π..π; Ink wants 0..2π. */
fun normalizeOrientation(rad: Float): Float {
    val twoPi = (2 * PI).toFloat()
    val r = rad % twoPi
    return if (r < 0f) r + twoPi else r
}

/**
 * Scripted strokes that stress the weak spots listed in BRUSH_ENGINE_PLAN: tight loops,
 * hairpin turns, a dot, a pressure ramp and a fast flick with a pressure tail. Sampled at
 * 240 Hz like the S Pen, inside 1000 × 200 page units (what a landscape Tab pad shows).
 */
object LabSamples {
    private const val DT = 4L

    fun all(): List<RawStroke> = listOf(loops(), hairpins(), spiral(), dot(), pressureRamp(), flick())

    /** Cursive "llll": a trochoid whose loops shrink to a few units across. */
    fun loops(): RawStroke = sampled(0L, 360) { i, n ->
        val t = i.toFloat() / (n - 1)
        val w = t * 4f * 2f * PI.toFloat()
        val x = 60f + 280f * t - 26f * sin(w)
        val y = 105f - 32f * (1f - cos(w))
        RawSample(x, y, envelope(t) * (0.35f + 0.35f * (1f - cos(w)) / 2f), 0.7f, 0.4f, 0)
    }

    /** Zig-zag with near-180° turns. */
    fun hairpins(): RawStroke = sampled(5_000L, 240) { i, n ->
        val t = i.toFloat() / (n - 1)
        val seg = t * 6f
        val k = seg.toInt().coerceAtMost(5)
        val f = seg - k
        val up = k % 2 == 0
        val x = 400f + 40f * seg
        val y = if (up) 110f - 90f * f else 20f + 90f * f
        RawSample(x, y, envelope(t) * 0.6f, 0.8f, 0.4f, 0)
    }

    /** Spiral tightening to a 2-unit radius: shows faceting on small curves. */
    fun spiral(): RawStroke = sampled(10_000L, 300) { i, n ->
        val t = i.toFloat() / (n - 1)
        val r = 43f * (1f - t) + 2f
        val a = t * 5f * 2f * PI.toFloat()
        RawSample(800f + r * cos(a), 65f + r * sin(a), envelope(t) * 0.55f, 0.7f, 0.4f, 0)
    }

    /** A dot: two samples at almost the same place. */
    fun dot(): RawStroke = RawStroke(listOf(
        RawSample(70f, 165f, 0.5f, 0.7f, 0.4f, 15_000L),
        RawSample(70.2f, 165.1f, 0.55f, 0.7f, 0.4f, 15_040L),
    ))

    /** Straight line, pressure 0 → 1: width response. */
    fun pressureRamp(): RawStroke = sampled(20_000L, 200) { i, n ->
        val t = i.toFloat() / (n - 1)
        RawSample(120f + 380f * t, 165f, t, 0.7f, 0.4f, 0)
    }

    /** Fast diagonal flick whose pressure fades out: end taper. */
    fun flick(): RawStroke = sampled(25_000L, 40) { i, n ->
        val t = i.toFloat() / (n - 1)
        RawSample(560f + 340f * t, 190f - 50f * t * t, 0.8f * (1f - t) + 0.05f, 0.9f, 0.4f, 0)
    }

    /**
     * Tilt and overlap set: the same back-and-forth hatch drawn with the pen leaning flat
     * (tilt 1.35 rad, azimuth up the page) and upright (0.5 rad), a hatch moving along the
     * azimuth, light vs firm pressure, and two crossing passes for highlighter overlap.
     */
    fun tiltSet(): List<RawStroke> = listOf(
        hatch(30_000L, 40f, 30f, horizontal = true, tilt = 1.35f, pressure = 0.5f),
        hatch(32_000L, 300f, 30f, horizontal = true, tilt = 0.5f, pressure = 0.5f),
        hatch(34_000L, 560f, 30f, horizontal = false, tilt = 1.35f, pressure = 0.5f),
        line(36_000L, 40f, 150f, 0.15f), line(37_000L, 40f, 185f, 0.95f),
        line(38_000L, 560f, 160f, 0.6f), cross(39_000L),
    )

    /** Zig-zag shading in a 200 × 90 box: rows sideways ([horizontal]) or columns. */
    private fun hatch(start: Long, x0: Float, y0: Float, horizontal: Boolean, tilt: Float, pressure: Float) = sampled(start, 400) { i, n ->
        val t = i.toFloat() / (n - 1)
        val passes = 9f
        val phase = t * passes
        val k = phase.toInt().coerceAtMost(8)
        val f = phase - k
        val sweep = if (k % 2 == 0) f else 1f - f
        val (x, y) = if (horizontal) (x0 + 200f * sweep) to (y0 + 90f * t) else (x0 + 200f * t) to (y0 + 90f * sweep)
        RawSample(x, y, pressure * envelope(t).coerceAtLeast(0.6f), tilt, 0f, 0)
    }

    private fun line(start: Long, x0: Float, y: Float, pressure: Float) = sampled(start, 120) { i, n ->
        val t = i.toFloat() / (n - 1)
        RawSample(x0 + 420f * t, y, pressure, 0.7f, 0.4f, 0)
    }

    /** A stroke crossing back over the previous line: overlap behaviour. */
    private fun cross(start: Long) = sampled(start, 120) { i, n ->
        val t = i.toFloat() / (n - 1)
        RawSample(600f + 340f * t, 120f + 70f * t, 0.6f, 0.7f, 0.4f, 0)
    }

    /** Pressure rises over the first 10% and falls over the last 15%, like a real stroke. */
    private fun envelope(t: Float) = minOf(1f, t / 0.1f, (1f - t) / 0.15f).coerceIn(0.05f, 1f)

    private fun sampled(start: Long, n: Int, f: (Int, Int) -> RawSample) =
        RawStroke(List(n) { i -> f(i, n).copy(timeMs = start + i * DT) })
}

package app.folio.core.ink

import app.folio.core.model.ToolType
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * How a tool turns pressure and tilt into width and opacity. These are original
 * tunings, not taken from any other product.
 */
data class PenProfile(
    /** Width multiplier at zero pressure (0..1). */
    val minWidthFactor: Float,
    /** Exponent applied to pressure; >1 needs a firmer press to widen. */
    val pressureGamma: Float,
    /** How much tilting widens the line (pencil-side shading). */
    val tiltWidthGain: Float,
    /** 0..1 alpha multiplier applied on top of the colour's own alpha. */
    val opacity: Float,
    /** Whether width varies with pressure at all. */
    val pressureSensitive: Boolean = true,
) {
    fun widthAt(nominal: Float, pressure: Float, tilt: Float): Float {
        var f = if (pressureSensitive) {
            val p = pressure.coerceIn(0f, 1f).toDouble().pow(pressureGamma.toDouble()).toFloat()
            minWidthFactor + (1f - minWidthFactor) * p
        } else 1f
        if (tiltWidthGain != 0f) {
            // Normal handwriting already tilts the pen 37–54° (measured on a Tab S6 Lite),
            // so shading only starts when the pen leans further than that.
            val lean = ((tilt - SHADING_START_RAD) / (MAX_TILT_RAD - SHADING_START_RAD)).coerceIn(0f, 1f)
            f *= 1f + tiltWidthGain * sin(lean * PI.toFloat() / 2f)
        }
        return nominal * f
    }

    companion object {
        const val SHADING_START_RAD = 0.9f
        const val MAX_TILT_RAD = 1.4f

        fun of(tool: ToolType): PenProfile = when (tool) {
            ToolType.FOUNTAIN -> PenProfile(minWidthFactor = 0.35f, pressureGamma = 1.0f, tiltWidthGain = 0f, opacity = 1f)
            ToolType.BALLPOINT -> PenProfile(minWidthFactor = 0.75f, pressureGamma = 1f, tiltWidthGain = 0f, opacity = 1f)
            ToolType.PENCIL -> PenProfile(minWidthFactor = 0.5f, pressureGamma = 1f, tiltWidthGain = 1.2f, opacity = 0.75f)
            ToolType.BRUSH -> PenProfile(minWidthFactor = 0.1f, pressureGamma = 1.6f, tiltWidthGain = 0.6f, opacity = 1f)
            ToolType.HIGHLIGHTER -> PenProfile(minWidthFactor = 1f, pressureGamma = 1f, tiltWidthGain = 0f, opacity = 0.35f, pressureSensitive = false)
            // Felt tips: nearly even width; the marker spreads a little when pressed or leaned.
            ToolType.FINELINER -> PenProfile(minWidthFactor = 0.85f, pressureGamma = 1f, tiltWidthGain = 0f, opacity = 1f)
            ToolType.MARKER -> PenProfile(minWidthFactor = 0.7f, pressureGamma = 1.2f, tiltWidthGain = 0.3f, opacity = 1f)
            ToolType.AIRBRUSH -> PenProfile(minWidthFactor = 0.7f, pressureGamma = 1f, tiltWidthGain = 0f, opacity = 0.35f)
        }
    }
}

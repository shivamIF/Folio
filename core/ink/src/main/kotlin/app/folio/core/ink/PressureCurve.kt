package app.folio.core.ink

/**
 * Maps raw stylus pressure (0..1 as reported by the device) to writing effort
 * (0..1) before it is stored. People rarely press near the hardware maximum:
 * on a Galaxy Tab S6 Lite, normal handwriting peaked around 0.6 with a
 * median near 0.3. So [ceiling] is the raw pressure that counts as "full".
 *
 * The result is stored in the stroke, so a note looks the same on every
 * device regardless of its digitiser.
 */
data class PressureCurve(
    /** Raw pressure treated as full effort. Lower = lighter hand. */
    val ceiling: Float = DEFAULT_CEILING,
    /** Raw pressure below which input counts as zero effort (filters hover noise). */
    val floor: Float = 0f,
) {
    init { require(ceiling > floor) { "ceiling must be above floor" } }

    fun map(raw: Float): Float = ((raw - floor) / (ceiling - floor)).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_CEILING = 0.6f
        const val LIGHTEST = 0.3f
        const val FIRMEST = 1.0f

        /** Slider position 0 (light hand) .. 1 (firm hand) to a curve. */
        fun fromSensitivity(position: Float): PressureCurve =
            PressureCurve(ceiling = LIGHTEST + (FIRMEST - LIGHTEST) * position.coerceIn(0f, 1f))

        fun sensitivityOf(ceiling: Float): Float = ((ceiling - LIGHTEST) / (FIRMEST - LIGHTEST)).coerceIn(0f, 1f)
    }
}

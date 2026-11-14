package app.folio.notes.input

/** What kind of contact a pointer is, from MotionEvent.getToolType. */
enum class PointerKind { STYLUS, STYLUS_ERASER, FINGER, MOUSE, UNKNOWN }

/** What a new gesture should do. */
enum class GestureAction { DRAW, ERASE, NAVIGATE, IGNORE }

/**
 * Decides what each new contact does. Free of Android types so it can be unit
 * tested. These are heuristics from real event data, not a global touch block:
 *
 * - A stylus always writes, or erases if its eraser end, its barrel button or
 *   the eraser tool is active.
 * - A finger is treated as a palm (ignored) when its contact patch is large,
 *   or when the pen is touching or hovering nearby. Palms usually land just
 *   before or while the pen hovers.
 * - In pen-only mode (switched on automatically the first time a stylus is
 *   seen), fingers pan and zoom instead of drawing. Two or more fingers always
 *   navigate.
 */
class InputPolicy(
    /** Touch-major (px) above which a finger contact is considered a palm. */
    private val palmTouchMajorPx: Float,
    /** How long after stylus hover/touch a finger is still treated as a palm. */
    private val palmGuardMs: Long = 400,
    private val autoPenOnly: Boolean = true,
    /**
     * The device has a pen digitizer, so pen-only applies from the first touch. Without this a
     * finger scroll made before the pen first came near the screen drew on the page.
     */
    stylusKnown: Boolean = false,
) {
    var penOnly = stylusKnown && autoPenOnly
    var stylusSeen = stylusKnown
        private set
    private var lastStylusActivity = Long.MIN_VALUE / 2

    /** Call on any stylus hover or touch event. */
    fun onStylusActivity(nowMs: Long) {
        lastStylusActivity = nowMs
        if (!stylusSeen) {
            stylusSeen = true
            if (autoPenOnly) penOnly = true
        }
    }

    fun classifyDown(
        kind: PointerKind,
        touchMajorPx: Float,
        barrelButtonPressed: Boolean,
        eraserToolSelected: Boolean,
        pointerCount: Int,
        nowMs: Long,
        readOnly: Boolean = false,
    ): GestureAction = classify(kind, touchMajorPx, barrelButtonPressed, eraserToolSelected, pointerCount, nowMs).let {
        // Reading mode: nothing writes or erases; a pen or finger scrolls the page instead.
        if (readOnly && (it == GestureAction.DRAW || it == GestureAction.ERASE)) GestureAction.NAVIGATE else it
    }

    private fun classify(
        kind: PointerKind,
        touchMajorPx: Float,
        barrelButtonPressed: Boolean,
        eraserToolSelected: Boolean,
        pointerCount: Int,
        nowMs: Long,
    ): GestureAction = when (kind) {
        PointerKind.STYLUS -> {
            onStylusActivity(nowMs)
            if (barrelButtonPressed || eraserToolSelected) GestureAction.ERASE else GestureAction.DRAW
        }
        PointerKind.STYLUS_ERASER -> {
            onStylusActivity(nowMs)
            GestureAction.ERASE
        }
        PointerKind.MOUSE -> if (eraserToolSelected) GestureAction.ERASE else GestureAction.DRAW
        PointerKind.FINGER, PointerKind.UNKNOWN -> when {
            pointerCount > 1 -> GestureAction.NAVIGATE
            touchMajorPx > palmTouchMajorPx -> GestureAction.IGNORE
            nowMs - lastStylusActivity < palmGuardMs -> GestureAction.IGNORE
            penOnly -> GestureAction.NAVIGATE
            eraserToolSelected -> GestureAction.ERASE
            else -> GestureAction.DRAW
        }
    }
}

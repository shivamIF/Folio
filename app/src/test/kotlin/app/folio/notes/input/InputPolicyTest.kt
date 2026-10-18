package app.folio.notes.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputPolicyTest {
    private fun policy() = InputPolicy(palmTouchMajorPx = 60f, palmGuardMs = 400)

    @Test fun fingerDrawsUntilStylusSeenThenNavigates() {
        val p = policy()
        assertEquals(GestureAction.DRAW, p.classifyDown(PointerKind.FINGER, 20f, false, false, 1, 0))
        assertFalse(p.penOnly)
        assertEquals(GestureAction.DRAW, p.classifyDown(PointerKind.STYLUS, 5f, false, false, 1, 1000))
        assertTrue(p.penOnly)
        assertEquals(GestureAction.NAVIGATE, p.classifyDown(PointerKind.FINGER, 20f, false, false, 1, 5000))
    }

    @Test fun barrelButtonAndEraserEndErase() {
        val p = policy()
        assertEquals(GestureAction.ERASE, p.classifyDown(PointerKind.STYLUS, 5f, true, false, 1, 0))
        assertEquals(GestureAction.ERASE, p.classifyDown(PointerKind.STYLUS_ERASER, 5f, false, false, 1, 0))
        assertEquals(GestureAction.ERASE, p.classifyDown(PointerKind.STYLUS, 5f, false, true, 1, 0))
    }

    @Test fun largeContactIsPalm() {
        assertEquals(GestureAction.IGNORE, policy().classifyDown(PointerKind.FINGER, 120f, false, false, 1, 0))
    }

    @Test fun fingerRightAfterPenIsPalmButLaterNavigates() {
        val p = policy()
        p.onStylusActivity(10_000) // e.g. a hover
        assertEquals(GestureAction.IGNORE, p.classifyDown(PointerKind.FINGER, 20f, false, false, 1, 10_200))
        assertEquals(GestureAction.NAVIGATE, p.classifyDown(PointerKind.FINGER, 20f, false, false, 1, 11_000))
    }

    @Test fun multiTouchAlwaysNavigates() {
        assertEquals(GestureAction.NAVIGATE, policy().classifyDown(PointerKind.FINGER, 20f, false, false, 2, 0))
    }

    @Test fun penOnlyCanBeTurnedOff() {
        val p = policy()
        p.onStylusActivity(0)
        p.penOnly = false
        assertEquals(GestureAction.DRAW, p.classifyDown(PointerKind.FINGER, 20f, false, false, 1, 5000))
    }
}

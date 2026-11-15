package app.folio.notes.ui.glass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassMathTest {
    @Test fun curveEndsAtFirstAndLastControlValues() {
        val r = MaterialRecipe.DockLight
        assertEquals(0.3f, r.curve(0f), 1e-5f)
        assertEquals(0.77f, r.curve(1f), 1e-5f)
    }

    @Test fun lightMaterialsKeepBackdropsLight() {
        // A black backdrop under regular light glass still comes out light enough for dark text.
        val out = GlassMath.material(MaterialRecipe.ladder(dark = false, pos = 2f), floatArrayOf(0f, 0f, 0f))
        assertTrue(GlassMath.luma(out[0], out[1], out[2]) > 0.6f)
    }

    @Test fun darkMaterialsKeepBackdropsDark() {
        val out = GlassMath.material(MaterialRecipe.ladder(dark = true, pos = 2f), floatArrayOf(1f, 1f, 1f))
        assertTrue(GlassMath.luma(out[0], out[1], out[2]) < 0.45f)
    }

    @Test fun vibrantTextMeetsContrastTargets() {
        for (dark in listOf(false, true)) for (pos in listOf(0f, 1f, 2f, 3f)) for (hc in listOf(false, true)) {
            val r = MaterialRecipe.ladder(dark, pos)
            val bg = GlassMath.argb(GlassMath.material(r, if (dark) floatArrayOf(0.1f, 0.1f, 0.12f) else floatArrayOf(0.93f, 0.93f, 0.94f)))
            val v = GlassMath.vibrant(bg, dark, increaseContrast = hc)
            assertTrue(GlassMath.contrast(v.primary, bg) >= (if (hc) 7f else 4.5f) - 0.05f, "primary dark=$dark pos=$pos hc=$hc")
            assertTrue(GlassMath.contrast(v.secondary, bg) >= (if (hc) 4.5f else 3f) - 0.05f, "secondary dark=$dark pos=$pos hc=$hc")
        }
    }

    @Test fun lightPrimaryIsDarkerThanSecondary() {
        val bg = GlassMath.argb(floatArrayOf(0.9f, 0.9f, 0.92f))
        val v = GlassMath.vibrant(bg, dark = false, increaseContrast = false)
        assertTrue(GlassMath.relativeLuminance(v.primary) < GlassMath.relativeLuminance(v.secondary))
    }

    @Test fun settingsClampOutOfRangeValues() {
        val s = GlassSettings(frost = 3f, magnification = 9f, dockIconSize = 5f).clamped()
        assertEquals(1f, s.frost)
        assertEquals(GlassSettings.MAX_MAGNIFICATION, s.magnification)
        assertEquals(GlassSettings.MIN_ICON, s.dockIconSize)
    }
}

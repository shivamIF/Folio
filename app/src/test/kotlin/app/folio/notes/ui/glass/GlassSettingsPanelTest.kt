package app.folio.notes.ui.glass

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlassSettingsPanelTest {
    private val d = GlassSettings()
    private val sc = GlassPanelScales

    @Test fun defaultsLandExactlyOnSliderSteps() {
        // A default that falls between steps would show a different number than it really holds.
        fun check(name: String, scale: GlassPanelScale, v: Float) = assertEquals(v, scale.value(scale.step(v)), 1e-6f, name)
        check("frost", sc.percent, d.frost)
        check("transparency", sc.percent, d.transparency)
        check("saturation", sc.boost, d.saturation)
        check("lensing", sc.percent, d.lensing)
        check("highlight", sc.percent, d.highlight)
        check("dockIconSize", sc.iconSize, d.dockIconSize)
        check("magnification", sc.magnification, d.magnification)
        check("magnifySpread", sc.spread, d.magnifySpread)
        check("autoHideDelay", sc.hideDelay, d.autoHideDelay)
        check("springiness", sc.percent, d.springiness)
    }

    @Test fun scalesCoverExactlyWhatSettingsAllow() {
        assertEquals(0f, sc.percent.min); assertEquals(1f, sc.percent.max)
        assertEquals(1f, sc.boost.min, 1e-6f); assertEquals(2.2f, sc.boost.max, 1e-6f)
        assertEquals(GlassSettings.MIN_ICON, sc.iconSize.min); assertEquals(GlassSettings.MAX_ICON, sc.iconSize.max)
        assertEquals(1f, sc.magnification.min, 1e-6f); assertEquals(GlassSettings.MAX_MAGNIFICATION, sc.magnification.max, 1e-6f)
        assertEquals(1f, sc.spread.min); assertEquals(5f, sc.spread.max)
        assertEquals(0.5f, sc.hideDelay.min); assertEquals(10f, sc.hideDelay.max)
    }

    @Test fun everySliderStepSurvivesClamping() {
        // Whatever a slider produces must be stored as-is, not nudged by GlassSettings.clamped().
        fun each(scale: GlassPanelScale, apply: (GlassSettings, Float) -> GlassSettings, read: (GlassSettings) -> Float) {
            for (step in scale.range) {
                val v = scale.value(step)
                assertEquals(v, read(apply(d, v).clamped()), 0f, "step $step")
                assertEquals(step, scale.step(v), "round trip of step $step")
            }
        }
        each(sc.percent, { s, v -> s.copy(frost = v) }, { it.frost })
        each(sc.percent, { s, v -> s.copy(springiness = v) }, { it.springiness })
        each(sc.boost, { s, v -> s.copy(saturation = v) }, { it.saturation })
        each(sc.iconSize, { s, v -> s.copy(dockIconSize = v) }, { it.dockIconSize })
        each(sc.magnification, { s, v -> s.copy(magnification = v) }, { it.magnification })
        each(sc.spread, { s, v -> s.copy(magnifySpread = v) }, { it.magnifySpread })
        each(sc.hideDelay, { s, v -> s.copy(autoHideDelay = v) }, { it.autoHideDelay })
    }

    @Test fun outOfRangeStoredValuesStayOnTheSlider() {
        assertEquals(sc.boost.range.last, sc.boost.step(9f))
        assertEquals(sc.boost.range.first, sc.boost.step(-1f))
        assertEquals(1f, sc.percent.value(250))
    }

    @Test fun valueTextIsClear() {
        val t = GlassPanelText
        assertEquals("55%", t.percent(55))
        assertEquals("1.7×", t.times(sc.magnification.step(1.7f)))
        assertEquals("1.0×", t.times(sc.boost.step(1f)))
        assertEquals("2.2×", t.times(sc.boost.step(2.2f)))
        assertEquals("44 dp", t.dp(sc.iconSize.step(44f)))
        assertEquals("2.5 s", t.seconds(sc.hideDelay.step(2.5f)))
        assertEquals("0.5 s", t.seconds(sc.hideDelay.step(0.5f)))
        assertEquals("10 s", t.seconds(sc.hideDelay.step(10f)))
        assertEquals("1 icon", t.icons(sc.spread.step(1f)))
        assertEquals("2.5 icons", t.icons(sc.spread.step(2.5f)))
        assertEquals("5 icons", t.icons(sc.spread.step(5f)))
        assertEquals("Off", t.amount(0))
        assertEquals("65%", t.amount(65))
    }

    @Test fun thumbTextFitsA28DpThumb() {
        val t = GlassPanelText
        // The widest thumb labels the panel produces: three or four characters at most.
        val labels = listOf(t.bare(100), t.times(sc.boost.range.last), t.times(sc.magnification.range.last), t.halves(sc.spread.range.last - 1), t.secondsShort(sc.hideDelay.range.last))
        labels.forEach { assertTrue(it.length <= 4, it) }
    }

    @Test fun springinessIsNamedCalmToLively() {
        val t = GlassPanelText
        assertEquals("Calm", t.springiness(0f))
        assertEquals("Balanced", t.springiness(d.springiness))
        assertEquals("Lively", t.springiness(1f))
        val names = (0..100).map { t.springiness(it / 100f) }
        assertEquals(listOf("Calm", "Gentle", "Balanced", "Springy", "Lively"), names.distinct())
    }

    @Test fun glassOffDisablesEverythingElse() {
        val off = panelEnabled(d.copy(enabled = false, magnify = true, autoHide = true))
        assertFalse(off.glass); assertFalse(off.magnify); assertFalse(off.autoHide)
    }

    @Test fun magnifyRowsFollowMagnify() {
        assertTrue(panelEnabled(d.copy(magnify = true)).magnify)
        assertFalse(panelEnabled(d.copy(magnify = false)).magnify)
        assertTrue(panelEnabled(d.copy(magnify = false)).glass)
    }

    @Test fun autoHideRowsFollowAutoHide() {
        assertTrue(panelEnabled(d.copy(autoHide = true)).autoHide)
        assertFalse(panelEnabled(d.copy(autoHide = false)).autoHide)
        assertTrue(panelEnabled(d.copy(autoHide = false)).glass)
    }

    @Test fun previewInkIsStableAndInsideTheBand() {
        val lines = PreviewInk.lines
        assertTrue(lines.size >= 4)
        for (l in lines) {
            assertTrue(l.xy.size >= 6 && l.xy.size % 2 == 0)
            assertTrue(l.xy.all { it in 0f..1f })
            assertTrue(l.widthDp > 0f)
        }
        // Same picture every time the panel opens.
        assertContentEquals(lines.first().xy, PreviewInk.lines.first().xy)
    }
}

package app.folio.notes.lab

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabInputTest {
    @Test fun scriptedSamplesAreValidInput() {
        for (s in LabSamples.all()) {
            var lastT = Long.MIN_VALUE
            for (p in s.samples) {
                assertTrue(p.x.isFinite() && p.y.isFinite(), "finite position")
                assertTrue(p.x in 0f..1000f && p.y in 0f..200f, "inside the pad: $p")
                assertTrue(p.pressure in 0f..1f, "effort in 0..1: $p")
                assertTrue(p.timeMs >= lastT, "time never goes back")
                lastT = p.timeMs
            }
        }
    }

    @Test fun replayKeepsEndpointsAndPresetTool() {
        val raw = LabSamples.pressureRamp()
        val preset = LAB_PRESETS.first { it.id == "brushpen" }
        val s = raw.toStroke(preset.tool, preset.color, preset.width)
        assertEquals(preset.tool, s.tool)
        assertTrue(s.size > 10)
        // finishAt snaps to the pen-up sample, so stabilisation never shortens the stroke.
        assertEquals(raw.samples.last().x, s.x[s.size - 1], 1e-3f)
        assertEquals(raw.samples.first().x, s.x[0], 1e-3f)
    }

    @Test fun dotReplaysAsATinyStroke() {
        val s = LabSamples.dot().toStroke(LAB_PRESETS.first().tool, 0xFF000000, 2.5f)
        assertTrue(s.size in 1..2)
    }

    @Test fun orientationMapsIntoInkRange() {
        val twoPi = (2 * PI).toFloat()
        for (r in listOf(-PI.toFloat(), -1f, 0f, 1f, PI.toFloat(), 7f)) {
            val n = normalizeOrientation(r)
            assertTrue(n >= 0f && n < twoPi, "$r -> $n")
        }
        assertEquals(twoPi - 1f, normalizeOrientation(-1f), 1e-5f)
    }

    @Test fun presetIdsAreUnique() {
        assertEquals(LAB_PRESETS.size, LAB_PRESETS.map { it.id }.toSet().size)
    }
}

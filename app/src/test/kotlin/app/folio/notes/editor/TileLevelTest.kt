package app.folio.notes.editor

import kotlin.test.Test
import kotlin.test.assertTrue

class TileLevelTest {
    /** Tiles are never noticeably coarser than the screen (≤ 11 % upscale) and never wastefully fine (< 2×). */
    @Test fun levelIsAlwaysSharpEnoughButNotWasteful() {
        var z = 0.3f
        while (z < 8f) {
            val s = TileCache.levelScale(TileCache.levelFor(z))
            assertTrue(s >= z / 1.11f, "zoom $z rendered at $s is blurry")
            assertTrue(s < z * 2f, "zoom $z rendered at $s wastes memory")
            z *= 1.013f
        }
    }

    @Test fun levelsGrowWithZoom() {
        var last = Int.MIN_VALUE
        var z = 0.3f
        while (z < 8f) {
            val k = TileCache.levelFor(z)
            assertTrue(k >= last)
            last = k
            z *= 1.05f
        }
    }
}

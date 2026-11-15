package app.folio.notes.editor.oneui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {
    @Test fun hexParsesShortLongAndAlphaForms() {
        assertEquals(0xFFFF1010, ColorMath.parseHex("#FF1010"))
        assertEquals(0xFFFF1010, ColorMath.parseHex("ff1010"))
        assertEquals(0xFFAABBCC, ColorMath.parseHex("#abc"))
        assertEquals(0x80123456, ColorMath.parseHex("#80123456"))
        assertEquals(0xFF0F87FF, ColorMath.parseHex("  #0F 87FF "))
    }

    @Test fun badHexIsRejected() {
        listOf("", "#", "#12", "#12345", "#GGGGGG", "1234567", "red").forEach { assertNull(it, ColorMath.parseHex(it)) }
    }

    @Test fun hexRoundTrips() {
        listOf(0xFF000000, 0xFFFFFFFF, 0xFF249150, 0xFFEB7317).forEach { assertEquals(it, ColorMath.parseHex(ColorMath.toHex(it))) }
        assertEquals("#0F87FF", ColorMath.toHex(0xFF0F87FF))
    }

    @Test fun hsvRoundTripsWithinOneStep() {
        for (c in listOf(0xFFFF1010, 0xFF249150, 0xFF0F87FF, 0xFF808080, 0xFF000000, 0xFFFFFFFF, 0xFFEB7317, 0xFF6A1B9A)) {
            val hsv = ColorMath.toHsv(c)
            assertClose(c, ColorMath.fromHsv(hsv[0], hsv[1], hsv[2]))
        }
    }

    @Test fun hslRoundTripsWithinOneStep() {
        for (c in listOf(0xFFFF1010, 0xFF249150, 0xFF0F87FF, 0xFF808080, 0xFF000000, 0xFFFFFFFF, 0xFFEB7317, 0xFF6A1B9A)) {
            val hsl = ColorMath.toHsl(c)
            assertClose(c, ColorMath.fromHsl(hsl[0], hsl[1], hsl[2]))
        }
    }

    @Test fun primaryHues() {
        assertEquals(0f, ColorMath.toHsv(0xFFFF0000)[0], 0.01f)
        assertEquals(120f, ColorMath.toHsv(0xFF00FF00)[0], 0.01f)
        assertEquals(240f, ColorMath.toHsv(0xFF0000FF)[0], 0.01f)
        assertEquals(0xFFFF0000, ColorMath.fromHsv(360f, 1f, 1f))
    }

    /** The samples measured on the reference device (teardown §2). */
    @Test fun darkModeMirrorsDarkColoursAndKeepsLightOnes() {
        assertEquals(0xFFFFFFFF, ColorMath.adaptForDark(0xFF000000))
        assertEquals(0xFF6EDB9A, ColorMath.adaptForDark(0xFF249150))
        assertEquals(0xFFFF1010, ColorMath.adaptForDark(0xFFFF1010))
        assertEquals(0xFFEB7317, ColorMath.adaptForDark(0xFFEB7317))
        assertEquals(0xFF0F87FF, ColorMath.adaptForDark(0xFF0F87FF))
    }

    @Test fun darkModeKeepsAlpha() {
        assertEquals(0x80, ColorMath.alpha(ColorMath.adaptForDark(0x80000000)))
    }

    @Test fun swatchGridIsThirteenByThirteenLightToDark() {
        val g = ColorMath.swatchGrid()
        assertEquals(13, g.size)
        g.forEach { assertEquals(13, it.size) }
        assertEquals(0xFFFFFFFF, g.first()[0])
        assertEquals(0xFF000000, g.last()[0])
        for (col in 0 until 13) for (r in 1 until 13) {
            assertTrue("column $col gets darker", ColorMath.luminance(g[r][col]) < ColorMath.luminance(g[r - 1][col]) + 1e-4f)
        }
        assertEquals(169, g.flatten().toSet().size)
    }

    @Test fun contentContrast() {
        assertTrue(ColorMath.prefersDarkContent(0xFFFFFFFF))
        assertTrue(!ColorMath.prefersDarkContent(0xFF000000))
        assertTrue(!ColorMath.prefersDarkContent(0xFF0F87FF))
    }

    private fun assertClose(a: Long, b: Long) {
        for (f in listOf(ColorMath::red, ColorMath::green, ColorMath::blue)) {
            assertTrue("${ColorMath.toHex(a)} vs ${ColorMath.toHex(b)}", abs(f(a) - f(b)) <= 1)
        }
    }
}

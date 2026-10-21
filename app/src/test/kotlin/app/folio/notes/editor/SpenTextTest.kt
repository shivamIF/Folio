package app.folio.notes.editor

import app.folio.core.model.Rect
import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpenTextTest {
    private fun stroke(tool: ToolType) = Stroke(
        tool = tool, color = 0xFF000000, width = 3f, x = floatArrayOf(0f, 10f), y = floatArrayOf(0f, 10f),
        pressure = floatArrayOf(.5f, .5f), tilt = FloatArray(2), orientation = FloatArray(2), t = intArrayOf(0, 10),
    )

    @Test fun onlyPensBecomeText() {
        assertTrue(SpenText.recognisable(stroke(ToolType.FOUNTAIN)))
        assertTrue(SpenText.recognisable(stroke(ToolType.PENCIL)))
        assertFalse(SpenText.recognisable(stroke(ToolType.HIGHLIGHTER)))
        assertFalse(SpenText.recognisable(stroke(ToolType.TAPE)))
    }

    @Test fun theBoxSitsWhereTheWordsWereAndMatchesTheirSize() {
        val ink = Rect(100f, 200f, 400f, 256f) // 56 units tall
        val b = SpenText.boxFor("hello", ink, 1000f, 0xFF112233)
        assertEquals(100f, b.x)
        assertEquals(40f, b.fontSize, 0.01f)
        assertEquals((200f + 256f) / 2f, b.y + b.height / 2f, 0.01f)
        assertTrue(b.width >= ink.width)
        assertEquals("hello", b.text)
        assertEquals(0xFF112233, b.color)
    }

    @Test fun tinyOrHugeWritingIsClamped() {
        assertEquals(SpenText.MIN_FONT, SpenText.boxFor("a", Rect(0f, 0f, 10f, 4f), 1000f, 0).fontSize)
        assertEquals(SpenText.MAX_FONT, SpenText.boxFor("a", Rect(0f, 0f, 300f, 500f), 1000f, 0).fontSize)
    }

    @Test fun writingOnTheSameLineRightAfterTheWordsCarriesOn() {
        val last = Rect(100f, 200f, 160f, 240f) // an "n", 40 tall
        val font = 28f
        assertTrue(SpenText.continues(last, Rect(166f, 202f, 200f, 240f), font)) // the "o" right after it
        assertTrue(SpenText.continues(last, Rect(200f, 170f, 260f, 240f), font)) // a taller letter, same line
        assertTrue(SpenText.continues(last, Rect(150f, 200f, 190f, 240f), font)) // overlaps a little
        assertFalse(SpenText.continues(last, Rect(100f, 260f, 160f, 300f), font)) // the next line
        assertFalse(SpenText.continues(last, Rect(300f, 200f, 340f, 240f), font)) // too far along
        assertFalse(SpenText.continues(last, Rect(20f, 200f, 60f, 240f), font)) // before it
    }

    @Test fun aWordGapBecomesASpace() {
        val last = Rect(100f, 200f, 160f, 240f)
        assertEquals("", SpenText.joiner(last, Rect(166f, 200f, 200f, 240f), 28f))
        assertEquals(" ", SpenText.joiner(last, Rect(190f, 200f, 230f, 240f), 28f))
    }

    @Test fun appendingWidensTheBoxButKeepsItOnThePage() {
        val prev = SpenText.boxFor("n", Rect(100f, 200f, 160f, 240f), 1000f, 0)
        val grown = SpenText.appended(prev, "o", Rect(166f, 200f, 400f, 240f), 1000f)
        assertEquals("no", grown.text)
        assertEquals(prev.id, grown.id)
        assertEquals(prev.x, grown.x)
        assertTrue(grown.x + grown.width >= 400f)
        val edge = SpenText.appended(prev, "o", Rect(166f, 200f, 1200f, 240f), 1000f)
        assertTrue(edge.x + edge.width <= 1000f)
    }

    @Test fun theBoxStaysOnThePage() {
        val b = SpenText.boxFor("word", Rect(900f, 0f, 990f, 56f), 1000f, 0)
        assertTrue(b.x + b.width <= 1000f)
    }
}

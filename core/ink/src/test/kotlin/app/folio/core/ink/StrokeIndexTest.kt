package app.folio.core.ink

import app.folio.core.model.Stroke
import app.folio.core.model.ToolType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StrokeIndexTest {
    private fun stroke(id: String, xs: FloatArray, ys: FloatArray, w: Float = 2f) = Stroke(
        id, ToolType.FOUNTAIN, 0xFF000000, w, xs, ys, FloatArray(xs.size) { 0.5f },
        FloatArray(xs.size), FloatArray(xs.size), IntArray(xs.size),
    )

    private fun brute(strokes: List<Stroke>, l: Float, t: Float, r: Float, b: Float) =
        strokes.indices.filter { val bb = strokes[it].bounds(); !(bb.right < l || bb.left > r || bb.bottom < t || bb.top > b) }.toIntArray()

    @Test fun queriesMatchBruteForceInPageOrder() {
        val rnd = Random(3)
        val strokes = List(3000) { k ->
            val x = rnd.nextFloat() * 1000f; val y = rnd.nextFloat() * 1414f
            // Mostly word-sized strokes, some long lines, a few page-spanning ones.
            val len = when (k % 50) { 0 -> 900f; in 1..5 -> 300f; else -> 40f }
            val n = 6
            stroke("s$k", FloatArray(n) { x + it * len / n }, FloatArray(n) { y + rnd.nextFloat() * 20f - 10f })
        }
        val idx = StrokeIndex(strokes)
        repeat(400) {
            val l = rnd.nextFloat() * 1100f - 50f; val t = rnd.nextFloat() * 1500f - 50f
            val r = l + rnd.nextFloat() * 400f; val b = t + rnd.nextFloat() * 400f
            assertContentEquals(brute(strokes, l, t, r, b), idx.query(l, t, r, b))
        }
        // Whole page and a point query.
        assertContentEquals(strokes.indices.toList().toIntArray(), idx.query(-1e6f, -1e6f, 1e6f, 1e6f))
        val p = strokes[123].bounds()
        assertContentEquals(brute(strokes, p.left, p.top, p.left, p.top), idx.query(p.left, p.top, p.left, p.top))
    }

    @Test fun appendingReusesTheGridAndMatchesAFreshIndex() {
        val rnd = Random(5)
        fun word(k: Int): Stroke {
            val x = rnd.nextFloat() * 1000f; val y = rnd.nextFloat() * 1414f
            return stroke("a$k", FloatArray(6) { x + it * 7f }, FloatArray(6) { y + rnd.nextFloat() * 10f })
        }
        var strokes = List(2000) { word(it) }
        var idx = StrokeIndex(strokes)
        // Pen strokes arrive one at a time; each new list keeps the same objects in front.
        repeat(40) { k ->
            strokes = strokes + word(2000 + k)
            idx = idx.appending(strokes)
            assertEquals(strokes.size - 1, idx.indexOf(strokes.last().id))
        }
        val fresh = StrokeIndex(strokes)
        repeat(200) {
            val l = rnd.nextFloat() * 1000f; val t = rnd.nextFloat() * 1400f
            assertContentEquals(fresh.query(l, t, l + 150f, t + 150f), idx.query(l, t, l + 150f, t + 150f))
        }
        assertEquals(17, idx.indexOf("a17"))
        assertEquals(-1, idx.indexOf("missing"))
        // A changed prefix (erase, undo) or a long tail builds a fresh index.
        val erased = strokes.drop(1)
        assertContentEquals(StrokeIndex(erased).query(0f, 0f, 1000f, 1414f), idx.appending(erased).query(0f, 0f, 1000f, 1414f))
        val many = strokes + List(StrokeIndex.MAX_TAIL + 1) { word(5000 + it) }
        assertContentEquals(StrokeIndex(many).query(0f, 0f, 500f, 700f), idx.appending(many).query(0f, 0f, 500f, 700f))
    }

    @Test fun idsEmptyPagesAndStrokesOffThePage() {
        assertEquals(0, StrokeIndex(emptyList()).query(0f, 0f, 1000f, 1000f).size)
        val strokes = listOf(
            stroke("a", floatArrayOf(-50f, -40f), floatArrayOf(-20f, -10f)),
            stroke("b", floatArrayOf(500f, 520f), floatArrayOf(700f, 710f)),
            stroke("c", floatArrayOf(5000f, 5010f), floatArrayOf(9000f, 9010f)),
        )
        val idx = StrokeIndex(strokes)
        assertEquals(1, idx.indexOf("b")); assertEquals(-1, idx.indexOf("zz"))
        assertContentEquals(intArrayOf(0), idx.query(-60f, -30f, -30f, 0f))
        assertContentEquals(intArrayOf(2), idx.query(4990f, 8990f, 5020f, 9020f))
        assertContentEquals(intArrayOf(0, 1, 2), idx.query(-1e5f, -1e5f, 1e5f, 1e5f))
    }
}

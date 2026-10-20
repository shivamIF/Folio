package app.folio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PageMergeTest {
    private fun s(id: String, dx: Float = 0f) = testStroke(id = id, dx = dx)
    private fun ids(p: Page) = p.strokes.map { it.id }
    private val base = Page(id = "p", strokes = listOf(s("a"), s("b"), s("c")))

    @Test fun bothSidesAddingKeepsEverything() {
        val mine = base.copy(strokes = base.strokes + s("m"))
        val theirs = base.copy(strokes = base.strokes + s("t"))
        assertEquals(listOf("a", "b", "c", "t", "m"), ids(PageMerge.merge(base, mine, theirs)))
    }

    @Test fun deletesOnEitherSideApply() {
        val mine = base.copy(strokes = listOf(s("a"), s("c"))) // I erased b
        val theirs = base.copy(strokes = listOf(s("a"), s("b"))) // they erased c
        assertEquals(listOf("a"), ids(PageMerge.merge(base, mine, theirs)))
    }

    @Test fun anEditBeatsADelete() {
        val mine = base.copy(strokes = listOf(s("a"), s("c"))) // I erased b
        val theirs = base.copy(strokes = listOf(s("a"), s("b", dx = 50f), s("c"))) // they moved b
        assertEquals(50f, PageMerge.merge(base, mine, theirs).strokes.first { it.id == "b" }.x[0])
        val mine2 = base.copy(strokes = listOf(s("a"), s("b"), s("c", dx = 9f))) // I moved c
        val theirs2 = base.copy(strokes = listOf(s("a"), s("b"))) // they erased c
        assertEquals(listOf("a", "b", "c"), ids(PageMerge.merge(base, mine2, theirs2)))
    }

    @Test fun whenBothChangeAnElementMineWins() {
        val mine = base.copy(strokes = listOf(s("a", dx = 1f), s("b"), s("c")))
        val theirs = base.copy(strokes = listOf(s("a", dx = 2f), s("b"), s("c")))
        assertEquals(1f, PageMerge.merge(base, mine, theirs).strokes[0].x[0])
    }

    @Test fun noLocalChangesGivesTheirsExactly() {
        val theirs = base.copy(strokes = listOf(s("c"), s("x")), texts = listOf(TextBox("t", 0f, 0f, 10f, 10f, "hi")), paper = PaperTemplate.GRID)
        assertEquals(theirs, PageMerge.merge(base, base, theirs))
    }

    @Test fun textAndPageSettingsMergeToo() {
        val t0 = TextBox("t", 0f, 0f, 10f, 10f, "hello")
        val b = base.copy(texts = listOf(t0))
        val mine = b.copy(texts = listOf(t0.copy(text = "hello world")))
        val theirs = b.copy(paper = PaperTemplate.GRID, texts = listOf(t0, TextBox("u", 5f, 5f, 10f, 10f, "new")))
        val m = PageMerge.merge(b, mine, theirs)
        assertEquals(listOf("hello world", "new"), m.texts.map { it.text })
        assertEquals(PaperTemplate.GRID, m.paper)
    }
}

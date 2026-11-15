package app.folio.desktop

import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.folio.core.model.Cover
import app.folio.core.model.PaperTemplate
import app.folio.core.storage.FileNotebookStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Writing on the Mac, end to end without a window: pointer input → stroke → saved page → undo. */
class MacEditingTest {
    private fun waitFor(what: String, ok: () -> Boolean) {
        repeat(100) { if (ok()) return; Thread.sleep(50) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun draggingWithThePenWritesAStrokeThatIsSavedAndCanBeUndone() {
        val dir = Files.createTempDirectory("folio-mac-edit").toFile()
        val store = FileNotebookStore(dir)
        val nb = store.createNotebook("Mac notes", null, Cover(), PaperTemplate.RULED)
        val pageId = nb.pageIds.first()
        var saves = 0
        val edit = EditState(store, nb.id, onSaved = { saves++ })
        edit.tool = MacTool.PEN
        val pdfs = PdfPages()
        val scene = ImageComposeScene(800, 1200, Density(1f)) { PageView(store, pdfs, nb, pageId, Modifier.width(800.dp), edit) }
        waitFor("the page to load") { scene.render(); pageId in edit.pages }
        scene.render()

        scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 300f)); scene.render()
        for (i in 1..20) { scene.sendPointerEvent(PointerEventType.Move, Offset(200f + i * 15f, 300f + i * 4f)); scene.render() }
        scene.sendPointerEvent(PointerEventType.Release, Offset(500f, 380f)); scene.render()

        waitFor("the stroke to be saved") { saves >= 1 }
        // Read back with a fresh store, as another launch (or sync) would.
        val saved = FileNotebookStore(dir).loadPage(nb.id, pageId)
        assertEquals(1, saved.strokes.size)
        val s = saved.strokes.single()
        assertTrue(s.size >= 5, "the stroke keeps its samples")
        // Screen 200..500 px on an 800 px page of 1000 units: x 250..625.
        assertEquals(250f, s.x.first(), 3f); assertEquals(625f, s.x.last(), 3f)

        File(System.getenv("FOLIO_RENDER_OUT")?.replace(".png", "-written.png") ?: "build/written.png").let { it.parentFile?.mkdirs(); it.writeBytes(scene.render().encodeToData()!!.bytes) }

        assertTrue(edit.canUndo)
        edit.undo()
        waitFor("the undo to be saved") { saves >= 2 }
        assertEquals(0, FileNotebookStore(dir).loadPage(nb.id, pageId).strokes.size)
        edit.redo()
        waitFor("the redo to be saved") { saves >= 3 }
        assertEquals(1, FileNotebookStore(dir).loadPage(nb.id, pageId).strokes.size)

        // The eraser takes it away again.
        edit.tool = MacTool.ERASER; scene.render(); scene.render()
        scene.sendPointerEvent(PointerEventType.Press, Offset(350f, 300f)); scene.render()
        for (y in 300..380 step 8) { scene.sendPointerEvent(PointerEventType.Move, Offset(350f, y.toFloat())); scene.render() }
        scene.sendPointerEvent(PointerEventType.Release, Offset(350f, 380f)); scene.render()
        waitFor("the erase to be saved") { saves >= 4 }
        assertEquals(0, FileNotebookStore(dir).loadPage(nb.id, pageId).strokes.size)
        scene.close(); pdfs.close()
    }

    @Test fun penTypeLineStyleAndHighlighterSettingsAreStoredOnTheStroke() {
        val dir = Files.createTempDirectory("folio-mac-pens").toFile()
        val store = FileNotebookStore(dir)
        val nb = store.createNotebook("Pens", null, Cover(), PaperTemplate.BLANK)
        val pageId = nb.pageIds.first()
        var saves = 0
        val edit = EditState(store, nb.id, onSaved = { saves++ })
        val pdfs = PdfPages()
        val scene = ImageComposeScene(800, 1200, Density(1f)) { PageView(store, pdfs, nb, pageId, Modifier.width(800.dp), edit) }
        waitFor("the page to load") { scene.render(); pageId in edit.pages }
        fun drag(y: Float) {
            scene.render(); scene.render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, y)); scene.render()
            for (i in 1..20) { scene.sendPointerEvent(PointerEventType.Move, Offset(100f + i * 25f, y + (i % 4) * 6f)); scene.render() }
            scene.sendPointerEvent(PointerEventType.Release, Offset(600f, y)); scene.render()
        }
        // Every pen type, each on its own line, at its own width.
        edit.tool = MacTool.PEN
        EditState.PENS.forEachIndexed { i, t -> edit.pen = t; drag(80f + i * 60f) }
        // A dashed brush line, then a round-tip highlighter at 70 %.
        edit.pen = app.folio.core.model.ToolType.BALLPOINT; edit.line = MacLine.DASHED; edit.inkWidth = 5f; drag(560f)
        edit.tool = MacTool.HIGHLIGHTER; edit.highlighterOpacity = 70; edit.highlighterRound = true; edit.color = 0xFF23864F; drag(640f)
        waitFor("all strokes to be saved") { saves >= EditState.PENS.size + 2 }

        val strokes = FileNotebookStore(dir).loadPage(nb.id, pageId).strokes
        assertEquals(EditState.PENS + listOf(app.folio.core.model.ToolType.BALLPOINT, app.folio.core.model.ToolType.HIGHLIGHTER), strokes.map { it.tool })
        EditState.PENS.forEachIndexed { i, t -> assertEquals(EditState.DEFAULT_WIDTHS.getValue(t), strokes[i].width) }
        val dashed = strokes[EditState.PENS.size]
        assertEquals(app.folio.core.model.LineStyle.DASHED, dashed.look.line); assertTrue(dashed.look.fixedWidth); assertEquals(5f, dashed.width)
        val hi = strokes.last()
        assertTrue(hi.look.roundTip)
        assertEquals(0x23864FL, hi.color and 0xFFFFFF)
        assertEquals(179L, (hi.color ushr 24) and 0xFF) // 70 % of 255, rounded
        File(System.getenv("FOLIO_RENDER_OUT")?.replace(".png", "-pens.png") ?: "build/pens.png").let { it.parentFile?.mkdirs(); it.writeBytes(scene.render().encodeToData()!!.bytes) }
        scene.close(); pdfs.close()
    }

    @Test fun toolbarAndPenPopupRender() {
        val store = FileNotebookStore(Files.createTempDirectory("folio-mac-bar").toFile())
        val nb = store.createNotebook("Bar", null, Cover(), PaperTemplate.BLANK)
        val edit = EditState(store, nb.id, onSaved = {})
        edit.tool = MacTool.PEN; edit.popup = MacTool.PEN
        val scene = ImageComposeScene(760, 420, Density(1f)) {
            androidx.compose.foundation.layout.Box(Modifier.width(760.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) { MacToolbar(edit) }
        }
        repeat(5) { scene.render() }
        val png = scene.render().encodeToData()!!.bytes
        File(System.getenv("FOLIO_RENDER_OUT")?.replace(".png", "-toolbar.png") ?: "build/toolbar.png").let { it.parentFile?.mkdirs(); it.writeBytes(png) }
        scene.close()
        assertTrue(png.size > 3_000)
    }
}

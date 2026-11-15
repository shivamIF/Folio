package app.folio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.folio.core.storage.FileNotebookStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the inkiest page of a real library to a PNG, without a window, so page drawing on
 * the Mac can be checked by eye. Runs only when FOLIO_LIBRARY points at a library folder.
 */
class RenderPageCheck {
    @Test fun rendersTheInkiestPage() {
        val dir = System.getenv("FOLIO_LIBRARY")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val store = FileNotebookStore(dir)
        val live = store.library().notebooks.filter { it.deletedAt == null }
        var best: Triple<app.folio.core.model.NotebookMeta, String, Int>? = null
        for (nb in live) for (id in nb.pageIds.take(12)) {
            val n = runCatching { store.loadPage(nb.id, id).strokes.size }.getOrDefault(0)
            if (best == null || n > best!!.third) best = Triple(nb, id, n)
        }
        val (nb, pageId, strokes) = best ?: return
        val pdfs = PdfPages()
        val scene = ImageComposeScene(900, 1300, Density(1f)) { PageView(store, pdfs, nb, pageId, Modifier.width(900.dp)) }
        repeat(40) { scene.render(); Thread.sleep(100) } // let the page and its PDF load
        val png = scene.render().encodeToData()!!.bytes
        val out = File(System.getenv("FOLIO_RENDER_OUT") ?: "build/page.png")
        out.parentFile?.mkdirs(); out.writeBytes(png)
        println("rendered '${nb.title}' page with $strokes strokes -> $out")
        scene.close(); pdfs.close()
        assertTrue(png.size > 10_000)
    }

    @Test fun rendersTheLibrary() {
        val dir = System.getenv("FOLIO_LIBRARY")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val store = FileNotebookStore(dir); val pdfs = PdfPages()
        val scene = ImageComposeScene(1180, 860, Density(1f)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Wallpaper)) { Library(store, pdfs, null) {} }
        }
        repeat(60) { scene.render(); Thread.sleep(100) }
        val out = File((System.getenv("FOLIO_RENDER_OUT") ?: "build/page.png").replace(".png", "-library.png"))
        out.parentFile?.mkdirs(); out.writeBytes(scene.render().encodeToData()!!.bytes)
        scene.close(); pdfs.close()
        assertTrue(out.length() > 10_000)
    }
}

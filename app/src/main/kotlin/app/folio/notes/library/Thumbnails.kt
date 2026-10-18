package app.folio.notes.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import app.folio.core.model.NotebookMeta
import app.folio.core.storage.DocumentRepository
import app.folio.notes.editor.PageRenderer
import app.folio.notes.pdf.PdfFile
import java.io.File

/**
 * First-page previews for the library. Rendered off the main thread with the
 * same [PageRenderer] as the editor, kept in memory and as PNGs in the cache
 * directory. The cache key includes modifiedAt, so editing a notebook
 * refreshes its preview. Everything here can be regenerated: losing it is harmless.
 */
class Thumbnails(context: Context, private val repo: DocumentRepository) {
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(meta: NotebookMeta) = "${meta.id}_${meta.modifiedAt}"

    fun cached(meta: NotebookMeta): Bitmap? = memory.get(key(meta))

    /** Blocking: call from a background dispatcher. Returns null for an empty or unreadable notebook. */
    fun load(meta: NotebookMeta): Bitmap? {
        val k = key(meta)
        memory.get(k)?.let { return it }
        val file = File(dir, "$k.png")
        val bmp = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null) ?: render(meta)?.also { save(meta, file, it) }
        bmp?.let { memory.put(k, it) }
        return bmp
    }

    private fun render(meta: NotebookMeta): Bitmap? = runCatching {
        val pageId = meta.pageIds.firstOrNull() ?: return null
        val page = repo.loadPage(meta.id, pageId)
        val scale = WIDTH_PX / page.width
        val bmp = Bitmap.createBitmap(WIDTH_PX.toInt(), (page.height * scale).toInt().coerceIn(1, 4 * WIDTH_PX.toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        val r = PageRenderer()
        r.drawPaper(c, page.paper, page.width, page.height, hasPdf = page.pdf != null)
        page.pdf?.let { bg ->
            PdfFile(repo.attachmentFile(meta.id, bg.attachmentId)).use { pdf ->
                val bg2 = pdf.render(bg.pageIndex, WIDTH_PX.toInt())
                r.drawPdfBackground(c, bg2, page.width, page.height)
                bg2.recycle()
            }
        }
        for (s in page.strokes) r.drawStroke(c, s)
        bmp
    }.getOrNull()

    private fun save(meta: NotebookMeta, file: File, bmp: Bitmap) {
        runCatching {
            // Drop older previews of this notebook, then write the new one.
            dir.listFiles { f -> f.name.startsWith(meta.id + "_") }?.forEach { it.delete() }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        const val WIDTH_PX = 360f
    }
}

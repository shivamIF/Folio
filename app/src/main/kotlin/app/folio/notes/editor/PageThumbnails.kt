package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import app.folio.core.model.Page
import app.folio.core.model.PageOps
import app.folio.core.model.PaperTemplate
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Page thumbnails of one notebook, for the page sidebar.
 *
 * - Drawn with the editor's own [PageRenderer] and [PageAssets] (paper, PDF background,
 *   pictures, ink, text), so a thumbnail shows what the page shows. Rendering happens on
 *   one low-priority thread, never on the pen's path.
 * - In memory by page id and content version. The view model bumps a page's version
 *   only when its content changes (debounced while writing), so scrolling the sidebar or
 *   writing never re-renders other pages.
 * - On disk as PNGs in the cache directory, keyed by the page file's stamp, so reopening
 *   a notebook doesn't re-render unchanged pages. Everything here can be regenerated.
 */
internal class PageThumbnails(cacheRoot: File, notebookId: String, private val assets: PageAssets) {

    /** A rendered page and what was learned while rendering it (for the page menu). */
    class Thumb(
        val bitmap: Bitmap, val version: Long,
        val pageWidth: Float, val pageHeight: Float, val paper: PaperTemplate, val hasContent: Boolean,
    )

    /** How to get one page: its saved-file stamp (null while it has unsaved edits) and its content. */
    interface Source {
        fun stamp(): String?
        fun page(): Page
    }

    private val dir = File(File(cacheRoot, "page-thumbs"), notebookId)
    private val memory = object : LruCache<String, Thumb>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Thumb) = value.bitmap.allocationByteCount
    }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "folio-thumbs").apply { priority = Thread.MIN_PRIORITY } }
    private val thread = executor.asCoroutineDispatcher()
    /** Holds Paint objects, so it's only touched on [thread]. */
    private val renderer by lazy { PageRenderer() }

    /** The cached thumbnail at exactly [version], or null. */
    fun peek(pageId: String, version: Long): Thumb? = memory.get(pageId)?.takeIf { it.version == version }

    /** Any cached thumbnail of the page (possibly older than its content): shown while a fresh one renders. */
    fun peekAny(pageId: String): Thumb? = memory.get(pageId)

    /**
     * The thumbnail of [pageId] at [version], [widthPx] wide. Suspends while it loads or
     * renders; a caller that goes away (item scrolled off) cancels it before it starts.
     */
    suspend fun get(pageId: String, version: Long, widthPx: Int, source: Source): Thumb? {
        peek(pageId, version)?.takeIf { it.bitmap.width == widthPx }?.let { return it }
        return withContext(thread) {
            peek(pageId, version)?.takeIf { it.bitmap.width == widthPx }?.let { return@withContext it }
            runCatching {
                val stamp = source.stamp()
                val thumb = stamp?.let { fromDisk(pageId, it, widthPx, version) } ?: render(source.page(), widthPx, version).also { t ->
                    if (stamp != null) save(pageId, stamp, t)
                }
                memory.put(pageId, thumb)
                thumb
            }.onFailure { android.util.Log.w("FolioThumbs", "page $pageId: ${it.message}") }.getOrNull()
        }
    }

    private fun render(page: Page, widthPx: Int, version: Long): Thumb {
        val scale = widthPx / page.width
        val heightPx = (page.height * scale).roundToInt().coerceIn(1, widthPx * 4)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        renderer.drawPaper(c, page.paper, page.width, page.height, hasPdf = page.pdf != null)
        // The PDF page is rendered straight into the bitmap at thumbnail size (over the paper).
        page.pdf?.let { assets.renderPdf(bmp, it, page.width, page.height, scale, 0f, 0f, null) }
        renderer.drawContent(c, page) { id -> assets.image(id, decode = true) }
        return Thumb(bmp, version, page.width, page.height, page.paper, PageOps.hasContent(page))
    }

    // ---- disk: <pageId>_<stamp>_<width>_<w>x<h>_<paper>_<content>.png ----

    private fun prefix(pageId: String, stamp: String, widthPx: Int) = "${pageId}_${stamp}_${widthPx}_"

    private fun fromDisk(pageId: String, stamp: String, widthPx: Int, version: Long): Thumb? {
        val p = prefix(pageId, stamp, widthPx)
        val f = dir.listFiles { f -> f.name.startsWith(p) && f.name.endsWith(".png") }?.firstOrNull() ?: return null
        val parts = f.name.removePrefix(p).removeSuffix(".png").split('_')
        if (parts.size != 3) return null
        val size = parts[0].split('x').mapNotNull { it.toFloatOrNull() }
        val paper = runCatching { PaperTemplate.valueOf(parts[1]) }.getOrNull() ?: return null
        if (size.size != 2) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        return Thumb(bmp, version, size[0], size[1], paper, parts[2] == "1")
    }

    private fun save(pageId: String, stamp: String, t: Thumb) {
        runCatching {
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith(pageId + "_") }?.forEach { it.delete() }
            val name = prefix(pageId, stamp, t.bitmap.width) + "${t.pageWidth}x${t.pageHeight}_${t.paper.name}_${if (t.hasContent) 1 else 0}.png"
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { t.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(File(dir, name))) tmp.delete()
        }
    }

    /** Stops the render thread; a render in progress finishes first. */
    fun release() {
        executor.shutdown()
        memory.evictAll()
    }

    companion object {
        /** About 45 sidebar thumbnails at 1.5–2× density. */
        const val MEMORY_BYTES = 24 * 1024 * 1024
    }
}

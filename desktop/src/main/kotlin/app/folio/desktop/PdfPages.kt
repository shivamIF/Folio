package app.folio.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File

/** Renders PDF page backgrounds with PDFBox. One open document per file; a small cache of rendered pages. */
class PdfPages {
    private val docs = HashMap<String, PDDocument>()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>) = size > 12
    }

    /** The page as a bitmap about [widthPx] wide; null if the file can't be read. Call off the main thread. */
    @Synchronized
    fun render(file: File, pageIndex: Int, widthPx: Int): ImageBitmap? {
        val key = "${file.path}#$pageIndex@$widthPx"
        cache[key]?.let { return it }
        return try {
            val doc = docs.getOrPut(file.path) { Loader.loadPDF(file) }
            if (pageIndex !in 0 until doc.numberOfPages) return null
            val pageWidthPt = doc.getPage(pageIndex).cropBox.width.coerceAtLeast(1f)
            val scale = (widthPx / pageWidthPt).coerceIn(0.2f, 4f)
            PDFRenderer(doc).renderImage(pageIndex, scale).toComposeImageBitmap().also { cache[key] = it }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun close() { docs.values.forEach { runCatching { it.close() } }; docs.clear(); cache.clear() }
}

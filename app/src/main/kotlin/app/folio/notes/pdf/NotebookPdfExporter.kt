package app.folio.notes.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.folio.core.model.NotebookMeta
import app.folio.core.storage.DocumentRepository
import app.folio.notes.editor.PageRenderer
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Writes a notebook as a PDF. Page sizes are physical: A4 width (1000 page
 * units) = 210 mm = 595.28 pt. Paper ruling and handwriting are vector paths;
 * imported PDF backgrounds are embedded as high-resolution images.
 */
object NotebookPdfExporter {
    const val POINTS_PER_UNIT = 595.2756f / 1000f
    /** Background image resolution relative to PDF points (2.5 × 72 = 180 dpi). */
    private const val BACKGROUND_OVERSAMPLE = 2.5f
    /** Pictures are embedded at most this many pixels on the long side. */
    private const val EXPORT_IMAGE_PX = 2400

    fun write(repo: DocumentRepository, meta: NotebookMeta, out: OutputStream) {
        val doc = PdfDocument()
        val renderer = PageRenderer(forPdf = true)
        val sources = HashMap<String, PdfFile>()
        val pictures = HashMap<String, android.graphics.Bitmap?>()
        try {
            for ((i, pageId) in meta.pageIds.withIndex()) {
                val page = repo.loadPage(meta.id, pageId)
                val wPt = (page.width * POINTS_PER_UNIT).roundToInt().coerceAtLeast(1)
                val hPt = (page.height * POINTS_PER_UNIT).roundToInt().coerceAtLeast(1)
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create())
                val c = pdfPage.canvas
                c.scale(POINTS_PER_UNIT, POINTS_PER_UNIT)
                renderer.drawPaper(c, page.paper, page.width, page.height, hasPdf = page.pdf != null)
                page.pdf?.let { bg ->
                    val src = sources.getOrPut(bg.attachmentId) { PdfFile(repo.attachmentFile(meta.id, bg.attachmentId)) }
                    val bmp = src.render(bg.pageIndex, (wPt * BACKGROUND_OVERSAMPLE).roundToInt())
                    renderer.drawPdfBackground(c, bmp, page.width, page.height)
                    bmp.recycle()
                }
                renderer.drawContent(c, page) { id ->
                    pictures.getOrPut(id) { app.folio.notes.editor.PageAssets.decode(repo.imageFile(meta.id, id), EXPORT_IMAGE_PX) }
                }
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
            sources.values.forEach { runCatching { it.close() } }
        }
    }

    /** File-name-safe version of a notebook title. */
    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Notebook" }.take(80) + ".pdf"

    /**
     * Saves to Downloads/Folio through MediaStore (no storage permission needed).
     * The entry stays hidden (IS_PENDING) until the write completes, and is
     * removed if writing fails, so a half-written PDF never appears.
     */
    fun exportToDownloads(context: Context, repo: DocumentRepository, meta: NotebookMeta): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName(meta.title))
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Folio")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { write(repo, meta, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

package app.folio.notes.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import app.folio.core.model.NotebookMeta
import app.folio.core.model.Page
import app.folio.core.storage.DocumentRepository
import app.folio.notes.editor.PageAssets
import app.folio.notes.editor.PageRenderer
import app.folio.notes.pdf.NotebookPdfExporter
import app.folio.notes.pdf.PdfFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Exports one page, separately from whole-notebook export. It never writes inside the
 * notebook: output goes only to a file the user picked, or to a private share file.
 *
 * - PDF: the notebook exporter, limited to this page (vector ink and ruling, physical size).
 * - PNG/JPEG: the editor's [PageRenderer] at the chosen dpi (paper, PDF background,
 *   pictures, ink, text), with the page's aspect ratio.
 *
 * Pages are read from the store, so save the open notebook first.
 */
object PageExporter {

    fun write(repo: DocumentRepository, meta: NotebookMeta, pageId: String, spec: PageExportSpec, out: OutputStream) {
        when (spec.format) {
            PageExportFormat.PDF -> NotebookPdfExporter.write(repo, meta, out, listOf(pageId))
            PageExportFormat.PNG, PageExportFormat.JPEG -> {
                val bmp = render(repo, meta, repo.loadPage(meta.id, pageId), spec.dpi)
                try {
                    val (format, quality) = if (spec.format == PageExportFormat.PNG) Bitmap.CompressFormat.PNG to 100
                    else Bitmap.CompressFormat.JPEG to PageExportSpec.JPEG_QUALITY
                    if (!bmp.compress(format, quality, out)) throw IOException("The image couldn't be encoded")
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** The page as a bitmap at [dpi] (see [PageExportSpec.rasterSize]). */
    fun render(repo: DocumentRepository, meta: NotebookMeta, page: Page, dpi: Int): Bitmap {
        val notebookId = meta.id
        val (w, h) = PageExportSpec.rasterSize(page.width, page.height, dpi)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pictures = HashMap<String, Bitmap?>()
        try {
            val c = Canvas(bmp)
            c.scale(w / page.width, h / page.height)
            val r = PageRenderer().apply { pageColor = if (page.pdf != null) null else meta.pageColor; density = meta.density }
            r.drawPaper(c, page.paper, page.width, page.height, hasPdf = page.pdf != null)
            page.pdf?.let { bg ->
                PdfFile(repo.attachmentFile(notebookId, bg.attachmentId)).use { pdf ->
                    val b = pdf.render(bg.pageIndex, w)
                    r.drawPdfBackground(c, b, page.width, page.height)
                    b.recycle()
                }
            }
            r.drawContent(c, page) { id -> pictures.getOrPut(id) { PageAssets.decode(repo.imageFile(notebookId, id), maxOf(w, h)) } }
        } catch (e: Throwable) {
            bmp.recycle()
            throw e
        } finally {
            pictures.values.forEach { it?.recycle() }
        }
        return bmp
    }

    /**
     * Writes to a document the user created with the system file picker. If writing fails,
     * the (new, incomplete) document is removed, so a half-written file never stays behind.
     */
    fun saveTo(context: Context, uri: Uri, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        try {
            val out = try {
                resolver.openOutputStream(uri, "wt")
            } catch (_: FileNotFoundException) {
                resolver.openOutputStream(uri, "w") // providers without truncate mode
            } catch (_: IllegalArgumentException) {
                resolver.openOutputStream(uri, "w")
            } ?: throw IOException("The chosen file can't be written")
            out.use(write)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
    }

    /**
     * Writes a file to share through the system share sheet and returns its content URI.
     * Each export gets its own folder, so equal names never collide; old ones are cleared.
     */
    fun shareFile(context: Context, fileName: String, write: (OutputStream) -> Unit): Uri {
        val root = File(context.cacheDir, SHARE_DIR)
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { now - it.lastModified() > SHARE_KEEP_MS }?.forEach { it.deleteRecursively() }
        val dir = File(root, app.folio.core.model.newId()).apply { mkdirs() }
        val file = File(dir, fileName)
        try {
            file.outputStream().use { out -> write(out); out.fd.sync() }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    /** Matches the provider in AndroidManifest.xml and res/xml/export_paths.xml. */
    const val AUTHORITY_SUFFIX = ".exports"
    private const val SHARE_DIR = "exports"
    /** A share target has long finished reading a file by then. */
    private const val SHARE_KEEP_MS = 60 * 60 * 1000L
}

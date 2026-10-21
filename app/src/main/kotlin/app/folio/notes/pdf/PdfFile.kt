package app.folio.notes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Read-only access to a PDF via the platform renderer. PdfRenderer allows only
 * one open page at a time, so every call is synchronised.
 *
 * Throws [SecurityException] for password-protected files and IOException for
 * damaged ones (both from the platform), which callers turn into user messages.
 */
class PdfFile(file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }

    val pageCount: Int get() = synchronized(this) { renderer.pageCount }

    /** Page size in PDF points (1/72 inch). */
    fun pageSize(index: Int): Pair<Int, Int> = synchronized(this) {
        renderer.openPage(index).use { it.width to it.height }
    }

    /** Renders a page [widthPx] wide (height keeps the aspect ratio) on white. */
    fun render(index: Int, widthPx: Int): Bitmap = synchronized(this) {
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceIn(1, MAX_RENDER_PX)
            val h = (w.toLong() * page.height / page.width).toInt().coerceIn(1, MAX_RENDER_PX)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() = synchronized(this) {
        renderer.close()
        fd.close()
    }

    companion object {
        const val MAX_PAGES = 2000
        const val MAX_RENDER_PX = 4096
    }
}

package app.folio.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.folio.core.model.PdfBackground
import java.io.File

/**
 * Pictures and PDF pages of one notebook, for the tile renderer (any thread).
 *
 * - Pictures are decoded once, downsampled to at most [MAX_IMAGE_PX] on the long side,
 *   and kept in a memory-bounded cache.
 * - PDF pages are rendered straight into tiles at the tile's own scale (sharp at any
 *   zoom), clipped to the tile. PdfRenderer isn't thread-safe and opens one page at a
 *   time, so each thread gets its own renderer per file.
 */
internal class PageAssets(private val imageFile: (String) -> File, private val pdfFile: (String) -> File) {

    private val images = object : LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** The decoded picture, or null if it isn't cached and [decode] is false (or the file is unreadable). */
    fun image(attachmentId: String, decode: Boolean): Bitmap? {
        images.get(attachmentId)?.let { return it }
        if (!decode || attachmentId in failed) return null
        val bmp = decode(imageFile(attachmentId), MAX_IMAGE_PX) ?: run { failed += attachmentId; return null }
        bmp.prepareToDraw()
        images.put(attachmentId, bmp)
        return bmp
    }

    private class OpenPdf(val file: File, val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        var closed = false
    }
    private val perThread = ThreadLocal<OpenPdf?>()
    private val opened = ArrayList<OpenPdf>()
    @Volatile private var released = false

    private fun rendererFor(file: File): OpenPdf? {
        perThread.get()?.let { if (it.file == file && !it.closed) return it }
        if (released) return null
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); return null }
        val o = OpenPdf(file, fd, r)
        synchronized(opened) { opened += o }
        perThread.set(o)
        return o
    }

    /**
     * Renders the PDF page into [bitmap] for a tile at [scale] (pixels per page unit) whose
     * top-left page point is ([left], [top]); only [clip] (bitmap pixels) is touched.
     * Draws over what's there (the caller paints the paper first). False if it can't.
     */
    fun renderPdf(
        bitmap: Bitmap, pdf: PdfBackground, pageW: Float, pageH: Float,
        scale: Float, left: Float, top: Float, clip: android.graphics.Rect?,
    ): Boolean {
        val o = rendererFor(pdfFile(pdf.attachmentId)) ?: return false
        synchronized(o) {
            if (o.closed || pdf.pageIndex !in 0 until o.renderer.pageCount) return false
            o.renderer.openPage(pdf.pageIndex).use { page ->
                val m = Matrix()
                m.setScale(scale * pageW / page.width, scale * pageH / page.height)
                m.postTranslate(-left * scale, -top * scale)
                page.render(bitmap, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        return true
    }

    /** Closes every renderer (a render in progress finishes first). */
    fun release() {
        released = true
        val all = synchronized(opened) { ArrayList(opened).also { opened.clear() } }
        for (o in all) synchronized(o) {
            if (!o.closed) { o.closed = true; runCatching { o.renderer.close() }; runCatching { o.fd.close() } }
        }
        images.evictAll()
    }

    companion object {
        const val MAX_IMAGE_PX = 2048

        /** Decodes a picture file downsampled so its long side is at most [maxPx]; null if unreadable. */
        fun decode(f: File, maxPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        private const val IMAGE_CACHE_BYTES = 64 * 1024 * 1024
    }
}

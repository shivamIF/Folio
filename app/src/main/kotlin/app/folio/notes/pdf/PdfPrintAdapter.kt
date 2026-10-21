package app.folio.notes.pdf

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Hands an already written PDF ([NotebookPdfExporter]) to the system print dialog. The pages
 * keep their own size; the print service fits them to the paper.
 */
class PdfPrintAdapter(private val file: File, private val title: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(NotebookPdfExporter.fileName(title))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }

    override fun onFinish() {
        file.delete()
    }
}

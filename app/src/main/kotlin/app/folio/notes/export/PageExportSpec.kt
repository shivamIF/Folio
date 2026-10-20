package app.folio.notes.export

import app.folio.core.model.PageFormat
import kotlin.math.roundToInt

/** File types a single page can be exported as. */
enum class PageExportFormat(val label: String, val mime: String, val extension: String, val raster: Boolean) {
    PDF("PDF", "application/pdf", "pdf", raster = false),
    PNG("PNG image", "image/png", "png", raster = true),
    JPEG("JPEG image", "image/jpeg", "jpg", raster = true),
}

/** What to export: one page, as [format]; raster formats at [dpi] of the page's physical size. */
data class PageExportSpec(val format: PageExportFormat, val dpi: Int = DEFAULT_DPI) {
    companion object {
        /** Screen, standard and print quality. */
        val DPI_CHOICES = listOf(72, 150, 300)
        const val DEFAULT_DPI = 150
        /** Longest side of an exported image (a 300 dpi A4 page is 2480 × 3508). */
        const val MAX_RASTER_PX = 4096
        const val JPEG_QUALITY = 92

        /**
         * Pixel size of a page [pageW] × [pageH] (page units, 1000 = A4 width = 210 mm) at
         * [dpi], keeping the aspect ratio. The long side is capped at [MAX_RASTER_PX].
         */
        fun rasterSize(pageW: Float, pageH: Float, dpi: Int): Pair<Int, Int> {
            require(pageW > 0f && pageH > 0f && dpi > 0)
            val pxPerUnit = dpi / (PageFormat.UNITS_PER_MM * MM_PER_INCH)
            val k = minOf(1f, MAX_RASTER_PX / (maxOf(pageW, pageH) * pxPerUnit))
            return (pageW * pxPerUnit * k).roundToInt().coerceAtLeast(1) to (pageH * pxPerUnit * k).roundToInt().coerceAtLeast(1)
        }

        /**
         * "<Notebook> – page <n>.<ext>", safe on every file system (FAT and Windows included).
         * [pageNumber] is 1-based.
         */
        fun fileName(notebookTitle: String, pageNumber: Int, format: PageExportFormat): String {
            val title = notebookTitle.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').ifEmpty { "Notebook" }.take(80)
            return "$title – page $pageNumber.${format.extension}"
        }

        private const val MM_PER_INCH = 25.4f
    }
}

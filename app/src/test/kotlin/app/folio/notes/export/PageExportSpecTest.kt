package app.folio.notes.export

import app.folio.core.model.PageFormat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageExportSpecTest {
    @Test fun a4AtCommonResolutionsMatchesPrintSizes() {
        val (w, h) = PageFormat.A4.size(landscape = false)
        // A4 is 8.27 × 11.69 in.
        assertEquals(1240 to 1754, PageExportSpec.rasterSize(w, h, 150))
        assertEquals(2480 to 3508, PageExportSpec.rasterSize(w, h, 300))
        assertEquals(595 to 842, PageExportSpec.rasterSize(w, h, 72))
    }

    @Test fun aspectRatioIsKeptForEveryFormatAndOrientation() {
        for (f in PageFormat.entries) for (land in listOf(false, true)) for (dpi in PageExportSpec.DPI_CHOICES) {
            val (pw, ph) = f.size(land)
            val (w, h) = PageExportSpec.rasterSize(pw, ph, dpi)
            // Each side is rounded to whole pixels, so the shape is right to within a pixel.
            assertTrue(abs(w - h * pw / ph) <= 1.5f, "$f land=$land $dpi dpi: $w×$h")
        }
    }

    @Test fun hugePagesAreCappedButKeepTheirShape() {
        // A poster-sized imported PDF page: 4 × A4 width.
        val (w, h) = PageExportSpec.rasterSize(4000f, 5656f, 300)
        assertEquals(PageExportSpec.MAX_RASTER_PX, maxOf(w, h))
        assertTrue(abs(w - h * 4000f / 5656f) <= 1.5f)
    }

    @Test fun fileNameNamesThePageAndIsSafeOnAnyFileSystem() {
        assertEquals("Physics – page 3.pdf", PageExportSpec.fileName("Physics", 3, PageExportFormat.PDF))
        assertEquals("Notebook – page 1.jpg", PageExportSpec.fileName("   ", 1, PageExportFormat.JPEG))
        val tricky = PageExportSpec.fileName("a/b\\c:d*e?f\"g<h>i|j.", 12, PageExportFormat.PNG)
        assertFalse(tricky.substringBeforeLast('.').any { it in "/\\:*?\"<>|" }, tricky)
        assertTrue(tricky.endsWith(" – page 12.png"))
        val long = PageExportSpec.fileName("x".repeat(500), 1, PageExportFormat.PDF)
        assertTrue(long.length < 120)
    }

    @Test fun onlyImagesAreRaster() {
        assertFalse(PageExportFormat.PDF.raster)
        assertTrue(PageExportFormat.PNG.raster && PageExportFormat.JPEG.raster)
        assertEquals(listOf("application/pdf", "image/png", "image/jpeg"), PageExportFormat.entries.map { it.mime })
    }
}

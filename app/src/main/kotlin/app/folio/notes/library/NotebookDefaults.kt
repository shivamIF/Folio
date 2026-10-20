package app.folio.notes.library

import android.content.Context
import app.folio.core.model.NotebookMeta
import app.folio.core.model.PageFormat
import app.folio.core.model.PaperTemplate
import kotlin.math.max
import kotlin.math.min

/** Page size choice for new notebooks: a standard format, or AUTO = shaped like this device's screen. */
enum class PageSizeChoice(val label: String, val format: PageFormat?) {
    AUTO("Auto (fits this screen)", null),
    A4("A4", PageFormat.A4),
    A5("A5", PageFormat.A5),
    LETTER("Letter", PageFormat.LETTER),
    LEGAL("Legal", PageFormat.LEGAL),
}

/** What the library shows for each notebook. */
enum class LibraryPreview(val label: String) {
    PDF_PAGES("Covers; first page for imported PDFs"),
    ALL_PAGES("First page for every notebook"),
    COVERS("Covers only"),
}

data class NotebookDefaults(
    val size: PageSizeChoice = PageSizeChoice.A4,
    val landscape: Boolean = false,
    val paper: PaperTemplate = PaperTemplate.RULED,
    val libraryPreview: LibraryPreview = LibraryPreview.PDF_PAGES,
) {
    fun showsPreview(meta: NotebookMeta) = when (libraryPreview) {
        LibraryPreview.PDF_PAGES -> meta.fromPdf
        LibraryPreview.ALL_PAGES -> true
        LibraryPreview.COVERS -> false
    }

    /**
     * Page size in page units. AUTO keeps the A4 width (so pen widths stay
     * consistent) and takes its height from the screen's aspect ratio, so one
     * page fills the screen in the chosen orientation.
     */
    fun pageSize(screenWidthPx: Int, screenHeightPx: Int): Pair<Float, Float> {
        size.format?.let { return it.size(landscape) }
        val short = min(screenWidthPx, screenHeightPx).toFloat().coerceAtLeast(1f)
        val long = max(screenWidthPx, screenHeightPx).toFloat()
        val ratio = long / short
        return if (landscape) NotebookMeta.PAGE_WIDTH * ratio to NotebookMeta.PAGE_WIDTH
        else NotebookMeta.PAGE_WIDTH to NotebookMeta.PAGE_WIDTH * ratio
    }

    fun describe(): String = size.label.substringBefore(" (") + if (landscape) ", landscape" else ", portrait"
}

/** Persists the defaults chosen in Settings on the home screen. */
class NotebookDefaultsStore(context: Context) {
    private val sp = context.getSharedPreferences("notebook_defaults", Context.MODE_PRIVATE)

    fun load(): NotebookDefaults {
        val d = NotebookDefaults()
        return NotebookDefaults(
            size = runCatching { PageSizeChoice.valueOf(sp.getString("size", d.size.name)!!) }.getOrDefault(d.size),
            landscape = sp.getBoolean("landscape", d.landscape),
            paper = runCatching { PaperTemplate.valueOf(sp.getString("paper", d.paper.name)!!) }.getOrDefault(d.paper),
            libraryPreview = runCatching { LibraryPreview.valueOf(sp.getString("libraryPreview", d.libraryPreview.name)!!) }.getOrDefault(d.libraryPreview),
        )
    }

    fun save(d: NotebookDefaults) {
        sp.edit().putString("size", d.size.name).putBoolean("landscape", d.landscape).putString("paper", d.paper.name)
            .putString("libraryPreview", d.libraryPreview.name).apply()
    }
}

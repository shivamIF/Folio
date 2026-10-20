package app.folio.notes.editor

import android.graphics.Bitmap
import app.folio.core.ink.StrokeIndex
import app.folio.core.model.PageImage
import app.folio.core.model.PaperTemplate
import app.folio.core.model.PdfBackground
import app.folio.core.model.Stroke
import app.folio.core.model.TextBox

/**
 * One page of the notebook's column as it's shown and edited (the view model's copy).
 * A new instance on every change, so the canvas can tell what changed by identity.
 */
data class LoadedPage(
    val id: String,
    val width: Float,
    val height: Float,
    val paper: PaperTemplate,
    val pdf: PdfBackground?,
    val strokes: List<Stroke>,
    val texts: List<TextBox>,
    val images: List<PageImage>,
    /** Rendered PDF page under the ink (low resolution, for fallbacks), when there's one. */
    val background: Bitmap? = null,
    /** Index of [strokes] built off the main thread when the page was loaded, or null. */
    val index: StrokeIndex? = null,
)

/**
 * A page as [InkCanvasView] holds it: where it sits in the column, its content once loaded,
 * its tiles, and edits drawn here that the view model hasn't echoed back yet. Main thread only.
 *
 * A slot exists for each page near the screen, and for any page the pen touched before its
 * content arrived (ink written there is drawn from [pendingCommits] until the page loads).
 */
internal class PageSlot(val pageId: String, budget: TileBudget, onTilesChanged: () -> Unit) {
    /** Layout in document units (see PageStack); [pos] is -1 once the page left the notebook. */
    var pos = -1
    var left = 0f
    var top = 0f
    var width = 1000f
    var height = 1414f
    var paper = PaperTemplate.RULED

    /** Content from the view model; null while the page is loading. */
    var source: LoadedPage? = null
    val loaded: Boolean get() = source != null

    var strokes: List<Stroke> = emptyList()
    var texts: List<TextBox> = emptyList()
    var images: List<PageImage> = emptyList()
    var pdf: PdfBackground? = null
    var pdfBitmap: Bitmap? = null
    /** Spatial index of [strokes], rebuilt whenever the list changes. */
    var strokeIndex = StrokeIndex(emptyList())
    /** Index built in the background for the stroke list an edit is about to produce. */
    var prebuiltIndex: StrokeIndex? = null

    /** Finished strokes not yet echoed back by the view model; drawn so pen-up never blinks. */
    val pendingCommits = ArrayList<Stroke>()
    val pendingSince = HashMap<String, Long>()
    /** Whole strokes being erased (hidden from the tiles until the edit comes back). */
    val erasedPending = LinkedHashSet<String>()
    /** Partial eraser: original stroke id → what remains of it so far. */
    val partialPieces = LinkedHashMap<String, List<Stroke>>()
    var partialCommitted = false
    /** A wipe was committed; tiles already show it, so edits wait for the page's echo. */
    var wipeAwaitingEcho = false
    /** Strokes hidden from the tiles last frame (same instance while unchanged). */
    var lastExcluded: Set<String> = emptySet()

    val tiles = TileCache(budget, onTilesChanged)

    /** Something drawn here still waits for the view model; the slot must stay. */
    val busy: Boolean get() = pendingCommits.isNotEmpty() || erasedPending.isNotEmpty() || partialPieces.isNotEmpty() || wipeAwaitingEcho

    fun release() = tiles.release()
}

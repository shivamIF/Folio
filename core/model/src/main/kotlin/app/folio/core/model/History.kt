package app.folio.core.model

/** A reversible edit to a page's stroke list. */
/** The editable content of a page; edits mutate it in place. */
class PageContent(
    val strokes: MutableList<Stroke>,
    val texts: MutableList<TextBox> = ArrayList(),
    val images: MutableList<PageImage> = ArrayList(),
    val stickies: MutableList<StickyNote> = ArrayList(),
    val tables: MutableList<PageTable> = ArrayList(),
    val audio: MutableList<PageAudio> = ArrayList(),
)

sealed interface PageEdit {
    /** Stroke edits implement these two; element edits override the [PageContent] versions instead. */
    fun apply(strokes: MutableList<Stroke>) {}
    fun revert(strokes: MutableList<Stroke>) {}
    fun apply(content: PageContent) = apply(content.strokes)
    fun revert(content: PageContent) = revert(content.strokes)

    /** Several edits as one undo step (e.g. moving strokes and a text box together). */
    class Composite(val edits: List<PageEdit>) : PageEdit {
        override fun apply(content: PageContent) { for (e in edits) e.apply(content) }
        override fun revert(content: PageContent) { for (e in edits.asReversed()) e.revert(content) }
    }

    /**
     * Adds ([before] null), replaces, or removes ([after] null) one text box, by id.
     * [index] is where it sat (or goes), so undo restores the order.
     */
    class PutText(val before: TextBox?, val after: TextBox?, val index: Int = Int.MAX_VALUE) : PageEdit {
        init { require(before != null || after != null) }
        override fun apply(content: PageContent) = put(content.texts, before, after, index, TextBox::id)
        override fun revert(content: PageContent) = put(content.texts, after, before, index, TextBox::id)
    }

    /** Same as [PutText], for images. */
    class PutImage(val before: PageImage?, val after: PageImage?, val index: Int = Int.MAX_VALUE) : PageEdit {
        init { require(before != null || after != null) }
        override fun apply(content: PageContent) = put(content.images, before, after, index, PageImage::id)
        override fun revert(content: PageContent) = put(content.images, after, before, index, PageImage::id)
    }

    /** Same as [PutText], for sticky notes (their ink included: writing on a note replaces it). */
    class PutSticky(val before: StickyNote?, val after: StickyNote?, val index: Int = Int.MAX_VALUE) : PageEdit {
        init { require(before != null || after != null) }
        override fun apply(content: PageContent) = put(content.stickies, before, after, index, StickyNote::id)
        override fun revert(content: PageContent) = put(content.stickies, after, before, index, StickyNote::id)
    }

    /** Same as [PutText], for tables. */
    class PutTable(val before: PageTable?, val after: PageTable?, val index: Int = Int.MAX_VALUE) : PageEdit {
        init { require(before != null || after != null) }
        override fun apply(content: PageContent) = put(content.tables, before, after, index, PageTable::id)
        override fun revert(content: PageContent) = put(content.tables, after, before, index, PageTable::id)
    }

    /** Same as [PutText], for recordings and audio files. */
    class PutAudio(val before: PageAudio?, val after: PageAudio?, val index: Int = Int.MAX_VALUE) : PageEdit {
        init { require(before != null || after != null) }
        override fun apply(content: PageContent) = put(content.audio, before, after, index, PageAudio::id)
        override fun revert(content: PageContent) = put(content.audio, after, before, index, PageAudio::id)
    }

    /**
     * Moves strokes in the z-order (Move forwards / backwards): [order] is the page's full stroke
     * id order after the move; undo restores [previous].
     */
    class Reorder(private val previous: List<String>, private val order: List<String>) : PageEdit {
        override fun apply(strokes: MutableList<Stroke>) = arrange(strokes, order)
        override fun revert(strokes: MutableList<Stroke>) = arrange(strokes, previous)
        private fun arrange(strokes: MutableList<Stroke>, ids: List<String>) {
            val byId = strokes.associateBy { it.id }
            val out = ArrayList<Stroke>(strokes.size)
            for (id in ids) byId[id]?.let { out += it }
            if (out.size != strokes.size) { // ids that aren't in [ids] keep their place on top
                val placed = out.mapTo(HashSet()) { it.id }
                strokes.filterTo(out) { it.id !in placed }
            }
            strokes.clear(); strokes.addAll(out)
        }

        companion object {
            /** Brings [ids] one step forwards ([forward]) or backwards past the nearest other stroke they overlap. */
            fun step(current: List<Stroke>, ids: Set<String>, forward: Boolean): Reorder? {
                val before = current.map { it.id }
                val sel = current.filter { it.id in ids }
                if (sel.isEmpty()) return null
                val bounds = sel.map { it.bounds() }.reduce(Rect::union)
                val first = current.indexOfFirst { it.id in ids }
                val last = current.indexOfLast { it.id in ids }
                // The next other stroke in the selection's area above (or below) it: the selection moves past it.
                val pivotPos = if (forward) (last + 1 until current.size).firstOrNull { current[it].id !in ids && current[it].bounds().intersects(bounds) }
                else (first - 1 downTo 0).firstOrNull { current[it].id !in ids && current[it].bounds().intersects(bounds) }
                val rest = ArrayList<Stroke>(current.size)
                var pivotInRest = -1
                for (i in current.indices) {
                    if (current[i].id in ids) continue
                    if (i == pivotPos) pivotInRest = rest.size
                    rest += current[i]
                }
                val at = when {
                    pivotInRest < 0 && forward -> rest.size
                    pivotInRest < 0 -> 0
                    forward -> pivotInRest + 1
                    else -> pivotInRest
                }
                val out = rest
                out.addAll(at, sel)
                val after = out.map { it.id }
                return if (after == before) null else Reorder(before, after)
            }
        }
    }

    class AddStroke(val stroke: Stroke) : PageEdit {
        override fun apply(strokes: MutableList<Stroke>) { strokes.add(stroke) }
        override fun revert(strokes: MutableList<Stroke>) { strokes.removeAll { it.id == stroke.id } }
    }

    /** Adds several strokes on top in one undoable step (duplicate, paste). */
    class AddStrokes(val added: List<Stroke>) : PageEdit {
        override fun apply(strokes: MutableList<Stroke>) { strokes.addAll(added) }
        override fun revert(strokes: MutableList<Stroke>) {
            val ids = added.mapTo(HashSet()) { it.id }
            strokes.removeAll { it.id in ids }
        }
    }

    /**
     * Replaces strokes with pieces of themselves (partial eraser), each set of
     * pieces taking its original's place in the z-order. Pieces may be empty.
     */
    class SplitStrokes(private val splits: List<Triple<Int, Stroke, List<Stroke>>>) : PageEdit {
        // One pass over the page (a split of hundreds of strokes on a 7,000-stroke page
        // took ~200 ms when each stroke was searched for and spliced in separately).
        override fun apply(strokes: MutableList<Stroke>) {
            val byId = HashMap<String, List<Stroke>>(splits.size * 2)
            for ((_, original, pieces) in splits) byId[original.id] = pieces
            val out = ArrayList<Stroke>(strokes.size + splits.sumOf { it.third.size })
            for (s in strokes) {
                val pieces = byId.remove(s.id)
                if (pieces == null) out += s else out.addAll(pieces)
            }
            strokes.clear(); strokes.addAll(out)
        }

        override fun revert(strokes: MutableList<Stroke>) {
            val pieceIds = splits.flatMap { it.third }.mapTo(HashSet()) { it.id }
            strokes.removeAll { it.id in pieceIds }
            insertAtOriginalPositions(strokes, splits.map { it.first to it.second })
        }

        companion object {
            /** [pieces] maps an original stroke id to what remains of it. */
            fun of(current: List<Stroke>, pieces: Map<String, List<Stroke>>): SplitStrokes {
                val splits = ArrayList<Triple<Int, Stroke, List<Stroke>>>(pieces.size)
                for (i in current.indices) {
                    val s = current[i]
                    val p = pieces[s.id] ?: continue
                    splits += Triple(i, s, p)
                }
                return SplitStrokes(splits)
            }
        }
    }

    /** Removes strokes and remembers their z-order so undo restores the exact stacking. */
    class RemoveStrokes(private val removed: List<Pair<Int, Stroke>>) : PageEdit {
        override fun apply(strokes: MutableList<Stroke>) {
            val ids = HashSet<String>(removed.size * 2)
            for (r in removed) ids += r.second.id
            strokes.removeAll { it.id in ids }
        }

        override fun revert(strokes: MutableList<Stroke>) = insertAtOriginalPositions(strokes, removed)

        companion object {
            fun of(current: List<Stroke>, ids: Set<String>): RemoveStrokes {
                val removed = ArrayList<Pair<Int, Stroke>>(ids.size)
                for (i in current.indices) if (current[i].id in ids) removed += i to current[i]
                return RemoveStrokes(removed)
            }
        }
    }

    /** Replaces strokes in place (move, recolour); before and after must share ids. */
    class ReplaceStrokes(private val before: List<Stroke>, private val after: List<Stroke>) : PageEdit {
        override fun apply(strokes: MutableList<Stroke>) = swap(strokes, after)
        override fun revert(strokes: MutableList<Stroke>) = swap(strokes, before)
        private fun swap(strokes: MutableList<Stroke>, with: List<Stroke>) {
            val byId = with.associateBy { it.id }
            for (i in strokes.indices) byId[strokes[i].id]?.let { strokes[i] = it }
        }
    }
}

/**
 * Mutable editing state for one page plus its undo/redo stacks. Not thread-safe:
 * the editor owns it on the main thread and hands immutable snapshots to storage.
 */
/**
 * Puts strokes back at their recorded positions in one pass. Same result as inserting them
 * one by one in ascending position order (`add(min(index, size), stroke)`), without shifting
 * the whole list for every stroke.
 */
private fun insertAtOriginalPositions(strokes: MutableList<Stroke>, items: List<Pair<Int, Stroke>>) {
    if (items.isEmpty()) return
    val sorted = items.sortedBy { it.first }
    val out = ArrayList<Stroke>(strokes.size + sorted.size)
    var k = 0
    for (s in strokes) {
        while (k < sorted.size && sorted[k].first <= out.size) out += sorted[k++].second
        out += s
    }
    while (k < sorted.size) out += sorted[k++].second
    strokes.clear(); strokes.addAll(out)
}

private fun <T> put(list: MutableList<T>, old: T?, new: T?, index: Int, id: (T) -> String) {
    when {
        old == null -> list.add(index.coerceIn(0, list.size), new!!)
        new == null -> { val at = list.indexOfFirst { id(it) == id(old) }; if (at >= 0) list.removeAt(at) }
        else -> { val at = list.indexOfFirst { id(it) == id(old) }; if (at >= 0) list[at] = new else list.add(new) }
    }
}

class PageEditor(page: Page, private val maxHistory: Int = 500) {
    private val strokes = page.strokes.toMutableList()
    private val content = PageContent(
        strokes, page.texts.toMutableList(), page.images.toMutableList(),
        page.stickies.toMutableList(), page.tables.toMutableList(), page.audio.toMutableList(),
    )
    private val undoStack = ArrayDeque<PageEdit>()
    private val redoStack = ArrayDeque<PageEdit>()
    private var base = page

    /** Increments on every change; lets callers detect unsaved edits cheaply. */
    var revision: Long = 0
        private set

    val currentStrokes: List<Stroke> get() = strokes
    val currentTexts: List<TextBox> get() = content.texts
    val currentImages: List<PageImage> get() = content.images
    val currentStickies: List<StickyNote> get() = content.stickies
    val currentTables: List<PageTable> get() = content.tables
    val currentAudio: List<PageAudio> get() = content.audio
    /** The page's paper. Changed by Note style for the whole notebook: a setting, not an undoable edit. */
    val paper: PaperTemplate get() = base.paper
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    /** The edit [undo] would revert next, or null. */
    val lastEdit: PageEdit? get() = undoStack.lastOrNull()
    /** The edit [redo] would apply next, or null. */
    val nextRedo: PageEdit? get() = redoStack.lastOrNull()

    fun perform(edit: PageEdit) {
        edit.apply(content)
        undoStack.addLast(edit)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        revision++
    }

    fun undo(): Boolean {
        val edit = undoStack.removeLastOrNull() ?: return false
        edit.revert(content)
        redoStack.addLast(edit)
        revision++
        return true
    }

    fun redo(): Boolean {
        val edit = redoStack.removeLastOrNull() ?: return false
        edit.apply(content)
        undoStack.addLast(edit)
        revision++
        return true
    }

    fun setPaper(paper: PaperTemplate) {
        if (base.paper == paper) return
        base = base.copy(paper = paper)
        revision++
    }

    fun snapshot(): Page = base.copy(
        strokes = strokes.toList(), texts = content.texts.toList(), images = content.images.toList(),
        stickies = content.stickies.toList(), tables = content.tables.toList(), audio = content.audio.toList(),
    )
}

/**
 * The order of content edits across a notebook's pages, so one Undo/Redo button reverts the
 * notebook's latest edit on whichever page it was made (continuous scrolling, ADR-10).
 *
 * Each page's [PageEditor] still owns its edits and its bounded undo/redo stacks; this only
 * remembers (page, edit) pairs in the order they happened. An entry is used only while it is
 * still that page's next edit to undo ([PageEditor.lastEdit]) or redo ([PageEditor.nextRedo]).
 * Otherwise (the page was reloaded from another device, dropped from memory, or its own
 * history trimmed) it is skipped. Page structure changes (add, delete, move) aren't recorded.
 */
class NotebookHistory(private val maxEntries: Int = 2_000) {
    private class Entry(val pageId: String, val edit: PageEdit)
    private val undos = ArrayDeque<Entry>()
    private val redos = ArrayDeque<Entry>()

    /**
     * [edit] was made on [pageId] (performed now, or queued until the page loads). A new edit
     * clears Redo everywhere, as in a single history.
     */
    fun record(pageId: String, edit: PageEdit) {
        undos.addLast(Entry(pageId, edit))
        if (undos.size > maxEntries) undos.removeFirst()
        redos.clear()
    }

    /**
     * Reverts the latest edit still valid; returns its page id, or null if there was none.
     * Stops without dropping anything while the latest edit's page is [waiting] (still loading).
     */
    fun undo(editorOf: (String) -> PageEditor?, waiting: (String) -> Boolean = { false }): String? {
        val e = topUndo(editorOf, waiting) ?: return null
        if (waiting(e.pageId)) return null
        undos.removeLast()
        editorOf(e.pageId)!!.undo()
        redos.addLast(e)
        return e.pageId
    }

    /** Re-applies the latest undone edit still valid; returns its page id, or null. */
    fun redo(editorOf: (String) -> PageEditor?): String? {
        while (true) {
            val e = redos.removeLastOrNull() ?: return null
            val ed = editorOf(e.pageId)
            if (ed != null && ed.nextRedo === e.edit && ed.redo()) {
                undos.addLast(e)
                return e.pageId
            }
        }
    }

    fun canUndo(editorOf: (String) -> PageEditor?, waiting: (String) -> Boolean = { false }) = topUndo(editorOf, waiting) != null

    fun canRedo(editorOf: (String) -> PageEditor?): Boolean {
        while (true) {
            val e = redos.lastOrNull() ?: return false
            if (editorOf(e.pageId)?.nextRedo === e.edit) return true
            redos.removeLast()
        }
    }

    /**
     * Undoes [edit] on [pageId] if it is still that page's latest edit (a snackbar's Undo).
     * When it's also the notebook's latest edit this is a normal [undo]; otherwise it's
     * reverted on its page and forgotten, without a Redo.
     */
    fun undoEdit(pageId: String, edit: PageEdit, editorOf: (String) -> PageEditor?): Boolean {
        val ed = editorOf(pageId) ?: return false
        if (ed.lastEdit !== edit) return false
        val top = topUndo(editorOf) { false }
        if (top != null && top.edit === edit) return undo(editorOf) == pageId
        if (!ed.undo()) return false
        val i = undos.indexOfLast { it.edit === edit }
        if (i >= 0) undos.removeAt(i)
        return true
    }

    /** Drops stale entries from the top; the latest usable (or waiting) entry, or null. */
    private fun topUndo(editorOf: (String) -> PageEditor?, waiting: (String) -> Boolean): Entry? {
        while (true) {
            val e = undos.lastOrNull() ?: return null
            if (waiting(e.pageId) || editorOf(e.pageId)?.lastEdit === e.edit) return e
            undos.removeLast()
        }
    }
}

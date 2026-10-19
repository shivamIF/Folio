package app.folio.core.model

/** A reversible edit to a page's stroke list. */
sealed interface PageEdit {
    fun apply(strokes: MutableList<Stroke>)
    fun revert(strokes: MutableList<Stroke>)

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

class PageEditor(page: Page, private val maxHistory: Int = 500) {
    private val strokes = page.strokes.toMutableList()
    private val undoStack = ArrayDeque<PageEdit>()
    private val redoStack = ArrayDeque<PageEdit>()
    private var base = page

    /** Increments on every change; lets callers detect unsaved edits cheaply. */
    var revision: Long = 0
        private set

    val currentStrokes: List<Stroke> get() = strokes
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun perform(edit: PageEdit) {
        edit.apply(strokes)
        undoStack.addLast(edit)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        revision++
    }

    fun undo(): Boolean {
        val edit = undoStack.removeLastOrNull() ?: return false
        edit.revert(strokes)
        redoStack.addLast(edit)
        revision++
        return true
    }

    fun redo(): Boolean {
        val edit = redoStack.removeLastOrNull() ?: return false
        edit.apply(strokes)
        undoStack.addLast(edit)
        revision++
        return true
    }

    fun snapshot(): Page = base.copy(strokes = strokes.toList())
}

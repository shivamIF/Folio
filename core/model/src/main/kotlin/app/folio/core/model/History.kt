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

    /** Removes strokes and remembers their z-order so undo restores the exact stacking. */
    class RemoveStrokes(private val removed: List<Pair<Int, Stroke>>) : PageEdit {
        override fun apply(strokes: MutableList<Stroke>) {
            val ids = removed.map { it.second.id }.toSet()
            strokes.removeAll { it.id in ids }
        }

        override fun revert(strokes: MutableList<Stroke>) {
            for ((index, stroke) in removed.sortedBy { it.first }) {
                strokes.add(index.coerceAtMost(strokes.size), stroke)
            }
        }

        companion object {
            fun of(current: List<Stroke>, ids: Set<String>) = RemoveStrokes(
                current.withIndex().filter { it.value.id in ids }.map { it.index to it.value },
            )
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

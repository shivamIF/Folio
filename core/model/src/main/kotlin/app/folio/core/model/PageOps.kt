package app.folio.core.model

/**
 * Page-level operations of the page navigator (add before/after, duplicate, erase, move).
 * Pure functions: the store applies the results to the notebook and the editor applies
 * [erase] through its [PageEditor], so both keep their own rules (atomic writes, undo).
 */
object PageOps {
    /** A new empty page shaped like [ref]: same size and paper, without its PDF background. */
    fun blankLike(ref: Page): Page = Page(width = ref.width, height = ref.height, paper = ref.paper)

    /**
     * An independent copy of [page]: a new page id and new ids for every stroke, text box
     * and picture (ids are per element, so no edit on one page can touch the other).
     * Attachments (PDF background, picture files) are shared, as both pages live in the same notebook.
     */
    fun duplicate(page: Page): Page = page.copy(
        schemaVersion = page.requiredSchema(),
        id = newId(),
        strokes = page.strokes.map { it.transformed(1f, 0f, 0f, 0f, 0f, newId = true) },
        texts = page.texts.map { it.copy(id = newId()) },
        images = page.images.map { it.copy(id = newId()) },
        stickies = page.stickies.map { it.transformed(1f, 0f, 0f, 0f, 0f, newId = true) },
        tables = page.tables.map { it.copy(id = newId()) },
        audio = page.audio.map { it.copy(id = newId()) },
    )

    fun hasContent(strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>, others: Int = 0): Boolean =
        strokes.isNotEmpty() || texts.isNotEmpty() || images.isNotEmpty() || others > 0

    fun hasContent(page: Page): Boolean =
        hasContent(page.strokes, page.texts, page.images, page.stickies.size + page.tables.size + page.audio.size)

    /**
     * One undoable edit that removes every stroke, text box and picture (and sticky note, table
     * and recording); the paper and PDF background stay. Null when there is nothing to erase.
     */
    fun erase(
        strokes: List<Stroke>, texts: List<TextBox>, images: List<PageImage>,
        stickies: List<StickyNote> = emptyList(), tables: List<PageTable> = emptyList(), audio: List<PageAudio> = emptyList(),
    ): PageEdit? {
        val edits = ArrayList<PageEdit>()
        if (strokes.isNotEmpty()) edits += PageEdit.RemoveStrokes.of(strokes, strokes.mapTo(HashSet()) { it.id })
        // Highest index first, so undo (which runs in reverse) puts each back in its place.
        for (i in texts.indices.reversed()) edits += PageEdit.PutText(texts[i], null, i)
        for (i in images.indices.reversed()) edits += PageEdit.PutImage(images[i], null, i)
        for (i in stickies.indices.reversed()) edits += PageEdit.PutSticky(stickies[i], null, i)
        for (i in tables.indices.reversed()) edits += PageEdit.PutTable(tables[i], null, i)
        for (i in audio.indices.reversed()) edits += PageEdit.PutAudio(audio[i], null, i)
        return when (edits.size) {
            0 -> null
            1 -> edits[0]
            else -> PageEdit.Composite(edits)
        }
    }

    /** [list] with the item at [from] moved so that it ends up at index [to] (both clamped). */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (target == from) return list
        return list.toMutableList().apply { add(target, removeAt(from)) }
    }
}

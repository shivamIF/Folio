package app.folio.core.model

/**
 * Three-way merge of one page edited in two places (this device's editor and another
 * device), element by element (strokes, text boxes, pictures, matched by id).
 *
 * - [base]: the page both started from; [mine]: this device's version; [theirs]: the other one.
 * - Elements either side added are kept; an element deleted on one side and untouched on the
 *   other is deleted; an edit beats a delete; if both changed the same element, mine wins.
 * - Order follows [theirs], with my new elements on top (drawn last).
 * - Page settings (paper, size, PDF) take my change if I made one, otherwise theirs.
 */
object PageMerge {
    fun merge(base: Page, mine: Page, theirs: Page): Page = theirs.copy(
        strokes = mergeList(base.strokes, mine.strokes, theirs.strokes) { it.id },
        texts = mergeList(base.texts, mine.texts, theirs.texts) { it.id },
        images = mergeList(base.images, mine.images, theirs.images) { it.id },
        stickies = mergeList(base.stickies, mine.stickies, theirs.stickies) { it.id },
        tables = mergeList(base.tables, mine.tables, theirs.tables) { it.id },
        audio = mergeList(base.audio, mine.audio, theirs.audio) { it.id },
        width = pick(base.width, mine.width, theirs.width),
        height = pick(base.height, mine.height, theirs.height),
        paper = pick(base.paper, mine.paper, theirs.paper),
        pdf = pick(base.pdf, mine.pdf, theirs.pdf),
    )

    private fun <T> pick(base: T, mine: T, theirs: T): T = if (mine != base) mine else theirs

    internal fun <T> mergeList(base: List<T>, mine: List<T>, theirs: List<T>, id: (T) -> String): List<T> {
        val b = base.associateBy(id); val m = mine.associateBy(id); val t = theirs.associateBy(id)
        val out = ArrayList<T>(theirs.size + mine.size)
        for (e in theirs) {
            val k = id(e)
            val mv = m[k]; val bv = b[k]
            when {
                mv == null && bv != null && e == bv -> Unit // I deleted it, they didn't touch it
                mv == null -> out += e // they added it, or edited what I deleted (edit beats delete)
                bv != null && mv != bv -> out += mv // I changed it: mine wins
                else -> out += e
            }
        }
        for (e in mine) {
            val k = id(e)
            if (k in t) continue
            val bv = b[k]
            // In mine but not theirs: I added it, or they deleted it (keep it only if I edited it).
            if (bv == null || e != bv) out += e
        }
        return out
    }
}

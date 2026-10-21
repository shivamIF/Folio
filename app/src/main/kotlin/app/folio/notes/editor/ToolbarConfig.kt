package app.folio.notes.editor

import app.folio.notes.editor.oneui.FIcon

/**
 * Items the user can arrange on the editor toolbar (Customise toolbar, teardown §17). Text mode,
 * Pen, Highlighter, Eraser and Selection are fixed at the start and aren't listed. Items above
 * the line are shown; items below it are hidden. Persisted by name: an unknown name (from a
 * newer build) is dropped, and items new to this build are added hidden.
 */
enum class ToolbarItem(val title: String, val icon: FIcon) {
    PEN_COLOURS("Pen colour", FIcon.PALETTE_GRID),
    PEN_SIZES("Pen thickness", FIcon.MINUS),
    UNDO("Undo", FIcon.UNDO),
    REDO("Redo", FIcon.REDO),
    SPEN_TO_TEXT("S Pen to text", FIcon.SPEN_TO_TEXT),
    CHANGE_STYLE("Change style", FIcon.CHANGE_STYLE),
    TAPE("Tape", FIcon.TAPE),
    EASY_PAD("Easy writing pad", FIcon.EASY_PAD),
    LASER("Laser pointer", FIcon.LASER),
    AUTO_FIX_SHAPES("Auto-fix shapes", FIcon.AUTO_FIX_SHAPES),
    LOCK_CANVAS("Lock canvas", FIcon.LOCK_CANVAS),
    CLEAN_UP("Clean up handwriting", FIcon.CLEAN_UP),
    // Folio extras that Samsung Notes keeps elsewhere.
    INSERT_PICTURE("Insert picture", FIcon.IMAGE),
    ADD_PAGE("Add page", FIcon.ADD_PAGE),
    PAGE_NAVIGATION("Pages panel", FIcon.PAGE_NAV),
    EXPORT_PDF("Export as PDF", FIcon.EXPORT_PDF),
    ;
}

data class ToolbarConfig(
    val shown: List<ToolbarItem> = DEFAULT_SHOWN,
    val hidden: List<ToolbarItem> = DEFAULT_HIDDEN,
    /** Quick colours on the toolbar, 3–5. */
    val colourCount: Int = MAX_COLOURS,
    /** Quick thicknesses on the toolbar, 1–3. */
    val sizeCount: Int = MAX_SIZES,
) {
    /** Every item exactly once (shown first), counts in range. */
    fun normalized(): ToolbarConfig {
        val s = shown.distinct()
        val h = (hidden.distinct() - s.toSet()) + (ToolbarItem.entries - s.toSet() - hidden.toSet())
        return ToolbarConfig(s, h, colourCount.coerceIn(MIN_COLOURS, MAX_COLOURS), sizeCount.coerceIn(MIN_SIZES, MAX_SIZES))
    }

    /** The whole list as the customise screen shows it: shown items, the line, hidden items. */
    val rows: List<ToolbarItem?> get() = shown + listOf(null) + hidden

    /**
     * Moves [item] to row [toRow] of [rows] (the line is a row too: dropping on or above it
     * shows the item, below it hides it).
     */
    fun move(item: ToolbarItem, toRow: Int): ToolbarConfig {
        val list = rows.toMutableList()
        val from = list.indexOf(item)
        if (from < 0) return this
        list.removeAt(from)
        list.add(toRow.coerceIn(0, list.size), item)
        val line = list.indexOf(null)
        return copy(shown = list.subList(0, line).filterNotNull(), hidden = list.subList(line + 1, list.size).filterNotNull())
    }

    fun encode(): String = "v1;shown=${shown.joinToString(",") { it.name }};hidden=${hidden.joinToString(",") { it.name }};colours=$colourCount;sizes=$sizeCount"

    companion object {
        const val MIN_COLOURS = 3
        const val MAX_COLOURS = 5
        const val MIN_SIZES = 1
        const val MAX_SIZES = 3

        val DEFAULT_SHOWN = listOf(
            ToolbarItem.PEN_COLOURS, ToolbarItem.PEN_SIZES, ToolbarItem.UNDO, ToolbarItem.REDO, ToolbarItem.SPEN_TO_TEXT,
            ToolbarItem.CHANGE_STYLE, ToolbarItem.TAPE, ToolbarItem.EASY_PAD, ToolbarItem.LASER,
        )
        val DEFAULT_HIDDEN = ToolbarItem.entries - DEFAULT_SHOWN.toSet()

        /** Reads [encode]'s form; anything unreadable gives the defaults. */
        fun decode(text: String?): ToolbarConfig {
            if (text.isNullOrBlank() || !text.startsWith("v1;")) return ToolbarConfig()
            val fields = text.removePrefix("v1;").split(';').mapNotNull { f -> f.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            fun items(key: String) = fields[key].orEmpty().split(',').mapNotNull { n -> ToolbarItem.entries.firstOrNull { it.name == n } }
            return ToolbarConfig(
                shown = items("shown"), hidden = items("hidden"),
                colourCount = fields["colours"]?.toIntOrNull() ?: MAX_COLOURS,
                sizeCount = fields["sizes"]?.toIntOrNull() ?: MAX_SIZES,
            ).normalized()
        }
    }
}

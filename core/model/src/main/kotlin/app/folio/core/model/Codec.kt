package app.folio.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class UnsupportedSchemaException(found: Int) :
    Exception("document schema $found is newer than supported $SCHEMA_VERSION; update the app")

class CorruptDocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A page's size and paper, known without decoding its strokes (see [DocumentCodec.pageHeaderOf]). */
data class PageHeader(val width: Float, val height: Float, val paper: PaperTemplate)

/**
 * JSON codec for the native format. Unknown keys are ignored, so an older
 * reader can open files written by a newer minor revision; a newer major
 * schema is rejected instead of being half-read.
 */
object DocumentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Always write the current version: a page loaded from an older schema may now hold
    // content (text boxes, new tools) that an older app must refuse rather than misread.
    fun encodePage(page: Page): String = json.encodeToString(Page.serializer(), page.copy(schemaVersion = SCHEMA_VERSION))
    fun encodeLibrary(index: LibraryIndex): String =
        json.encodeToString(LibraryIndex.serializer(), index.copy(schemaVersion = SCHEMA_VERSION))

    fun decodePage(text: String): Page = decode(text) { json.decodeFromString(Page.serializer(), it) }
    fun decodeLibrary(text: String): LibraryIndex = decode(text) { json.decodeFromString(LibraryIndex.serializer(), it) }

    private inline fun <T> decode(text: String, block: (String) -> T): T {
        // Don't build a JSON tree to read one field: an imported page can hold millions of
        // numbers, and the tree alone exhausted the 256 MB heap on a Galaxy Tab S6 Lite.
        val version = schemaVersionOf(text)
        if (version > SCHEMA_VERSION) throw UnsupportedSchemaException(version)
        return try {
            block(text)
        } catch (e: SerializationException) {
            throw CorruptDocumentException("document fields are invalid", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptDocumentException("document is inconsistent", e)
        }
    }

    /**
     * A page's size and paper from the start of its file ([prefix]: the first kilobyte or so).
     * The encoder writes `width`, `height` and `paper` before `strokes`, so laying out pages
     * (continuous scrolling) doesn't need the 4 s decode of a dense page. Only keys found before
     * `strokes` count (a stroke has a `width` of its own). Null when they aren't there or the
     * page is from a newer schema: the caller then uses the notebook's default size.
     */
    fun pageHeaderOf(prefix: String): PageHeader? {
        val end = prefix.indexOf("\"strokes\"")
        if (end < 0) return null
        val head = prefix.substring(0, end)
        if (runCatching { schemaVersionOf(head) }.getOrNull()?.let { it > SCHEMA_VERSION } != false) return null
        val w = numberAfter(head, "width") ?: return null
        val h = numberAfter(head, "height") ?: return null
        if (!(w > 0f && h > 0f && w.isFinite() && h.isFinite())) return null
        val paper = stringAfter(head, "paper")?.let { p -> PaperTemplate.entries.firstOrNull { it.name == p } } ?: return null
        return PageHeader(w, h, paper)
    }

    /** Index just after `"key":` (whitespace skipped), or -1. */
    private fun valueStart(text: String, key: String): Int {
        val k = text.indexOf("\"$key\"")
        if (k < 0) return -1
        var i = k + key.length + 2
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != ':') return -1
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun numberAfter(text: String, key: String): Float? {
        val start = valueStart(text, key).takeIf { it >= 0 } ?: return null
        var i = start
        while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
        return text.substring(start, i).toFloatOrNull()
    }

    private fun stringAfter(text: String, key: String): String? {
        val start = valueStart(text, key).takeIf { it >= 0 } ?: return null
        if (start >= text.length || text[start] != '"') return null
        val close = text.indexOf('"', start + 1).takeIf { it > start } ?: return null
        return text.substring(start + 1, close)
    }

    /**
     * The document's `schemaVersion`, found by scanning for the key (no other type in the
     * format uses that name). Missing means the current version; the full decode that
     * follows still rejects malformed JSON.
     */
    internal fun schemaVersionOf(text: String): Int {
        val key = text.indexOf("\"schemaVersion\"")
        if (key < 0) return SCHEMA_VERSION
        var i = key + "\"schemaVersion\"".length
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != ':') throw CorruptDocumentException("not a valid document")
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        val start = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == start) throw CorruptDocumentException("schemaVersion is not a number")
        // Absurdly long numbers are "newer than us", not corrupt.
        return if (i - start > 9) Int.MAX_VALUE else text.substring(start, i).toInt()
    }
}

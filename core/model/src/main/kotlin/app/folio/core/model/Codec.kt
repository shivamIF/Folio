package app.folio.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class UnsupportedSchemaException(found: Int) :
    Exception("document schema $found is newer than supported $SCHEMA_VERSION; update the app")

class CorruptDocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    fun encodePage(page: Page): String = json.encodeToString(Page.serializer(), page)
    fun encodeLibrary(index: LibraryIndex): String = json.encodeToString(LibraryIndex.serializer(), index)

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

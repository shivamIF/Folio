package app.folio.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val version = try {
            json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int ?: SCHEMA_VERSION
        } catch (e: Exception) {
            throw CorruptDocumentException("not a valid document", e)
        }
        if (version > SCHEMA_VERSION) throw UnsupportedSchemaException(version)
        return try {
            block(text)
        } catch (e: SerializationException) {
            throw CorruptDocumentException("document fields are invalid", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptDocumentException("document is inconsistent", e)
        }
    }
}

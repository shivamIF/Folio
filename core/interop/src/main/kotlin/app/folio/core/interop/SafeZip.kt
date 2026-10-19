package app.folio.core.interop

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Defensive zip access for untrusted imports: caps the entry count and the
 * bytes actually read (headers can lie), and only opens entries by exact
 * name, so nothing is ever extracted to a path taken from the archive.
 */
class SafeZip(file: File, private val maxEntries: Int = 50_000) : AutoCloseable {
    private val zip = try { ZipFile(file) } catch (e: Exception) { throw FormatException("not a valid archive") }
    val names: Set<String>

    init {
        val list = ArrayList<String>()
        val e = zip.entries()
        while (e.hasMoreElements()) {
            list += e.nextElement().name
            if (list.size > maxEntries) { zip.close(); throw FormatException("archive has too many entries") }
        }
        names = list.toHashSet()
    }

    fun has(name: String) = name in names

    /** Reads a whole entry, refusing more than [limit] bytes of decompressed data. */
    fun read(name: String, limit: Long = 64L * 1024 * 1024): ByteArray {
        val entry = zip.getEntry(name) ?: throw FormatException("missing $name")
        zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > limit) throw FormatException("$name is too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** Streams an entry (for large attachments); the caller enforces its own size cap. */
    fun open(name: String): InputStream = zip.getInputStream(zip.getEntry(name) ?: throw FormatException("missing $name"))

    override fun close() = zip.close()
}

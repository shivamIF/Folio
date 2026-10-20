package app.folio.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file replacement: write to a temp file in the same directory,
 * fsync it, keep the previous good copy as `.bak`, then atomically rename.
 * A reader that finds the main file missing or unreadable falls back to `.bak`.
 */
internal class AtomicFile(val file: File) {
    private val backup = File(file.path + ".bak")
    private val temp = File(file.path + ".tmp")

    @Throws(IOException::class)
    fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Main file bytes, or null when it doesn't exist. */
    fun readMain(): ByteArray? = file.takeIf { it.exists() }?.readBytes()
    fun readBackup(): ByteArray? = backup.takeIf { it.exists() }?.readBytes()

    /** Up to [max] bytes from the start of the main file (or the backup when it's missing); null if neither exists. */
    fun readPrefix(max: Int): ByteArray? {
        val f = file.takeIf { it.exists() } ?: backup.takeIf { it.exists() } ?: return null
        return f.inputStream().use { input ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) { val r = input.read(buf, n, max - n); if (r < 0) break; n += r }
            buf.copyOf(n)
        }
    }

    fun exists() = file.exists() || backup.exists()

    fun delete() {
        file.delete(); backup.delete(); temp.delete()
    }
}

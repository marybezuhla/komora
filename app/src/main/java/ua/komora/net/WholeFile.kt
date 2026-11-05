package ua.komora.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Files that are written whole or not at all, so a sudden power cut or a killed app never leaves half of one. */
object WholeFile {
    private const val SUFFIX = ".tmp"

    /** A write cut off longer ago than this is only a leftover. */
    private const val LEFTOVER_MS = 10L * 60 * 1000

    /** Writes [bytes] to a new file beside [target], onto the disk, then swaps it in. */
    fun write(target: File, bytes: ByteArray) {
        val tmp = File.createTempFile("komora", SUFFIX, target.parentFile)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Could not save ${target.name}")
        } finally {
            tmp.delete()
        }
    }

    /** Removes what writes in [dir] left when they were cut off (never one still going on). */
    fun dropLeftovers(dir: File, now: Long = System.currentTimeMillis()) {
        dir.listFiles { f -> f.name.endsWith(SUFFIX) && now - f.lastModified() > LEFTOVER_MS }?.forEach { it.delete() }
    }
}

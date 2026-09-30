package io.github.kdroidfilter.seforimapp.framework.io

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [target] through a sibling temp file that is synced and then moved over [target].
 *
 * A crash, kill or full disk mid-write leaves the previous [target] (or nothing) in place,
 * never a truncated file. If [write] throws, the temp file is deleted and [target] is untouched.
 */
internal fun <T> File.writeAtomically(write: (FileOutputStream) -> T): T {
    val dir = absoluteFile.parentFile ?: error("No parent directory for $this")
    dir.mkdirs()
    val tmp = File(dir, "$name.tmp")
    try {
        // A link left at the temp name (a drive someone else prepared) is removed, never written
        // through; the move below then replaces a link at the target instead of following it.
        Files.deleteIfExists(tmp.toPath())
        val result =
            FileOutputStream(tmp).use { out ->
                write(out).also {
                    out.flush()
                    out.fd.sync()
                }
            }
        tmp.moveOver(this)
        return result
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}

/** Moves this file over [target], atomically where the file system supports it. */
internal fun File.moveOver(target: File) {
    try {
        Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Pushes a long write to the device each [chunkBytes], so progress reported after each chunk
 * follows a slow drive rather than the page cache, and the one sync at the end of [writeAtomically]
 * does not stall for minutes after a multi-GB file. Only worth it on a removable drive: on the
 * computer's own disk that final sync is enough.
 */
internal class ChunkedSync(
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val sync: () -> Unit,
) {
    private var unsynced = 0L

    /** Counts [count] bytes just written, and syncs once a whole chunk is waiting. */
    fun wrote(count: Int) {
        unsynced += count
        if (unsynced >= chunkBytes) {
            sync()
            unsynced = 0
        }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 8L * 1024 * 1024
    }
}

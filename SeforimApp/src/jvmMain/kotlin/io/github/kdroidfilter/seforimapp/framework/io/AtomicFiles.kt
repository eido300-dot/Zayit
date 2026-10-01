package io.github.kdroidfilter.seforimapp.framework.io

import io.github.kdroidfilter.seforimapp.framework.platform.PlatformInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes this file through a sibling temp file that is synced and then moved over it.
 *
 * A crash, kill or full disk mid-write leaves the previous file (or nothing) in place, never a
 * truncated one. If [write] throws, the temp file is deleted and the file is untouched.
 * The temp file gets a fresh unique name ("<name>.<random>.tmp"), so two writers never share it
 * and a link planted at a predictable name is never written through.
 *
 * With [sync] off the data is not forced to disk before the move: a killed app still never
 * leaves a truncated file, only a power loss can. Use it, with [attempts] = [UI_MOVE_ATTEMPTS],
 * for small writes on the UI thread. [attempts] bounds the move, see [moveOver].
 */
internal fun <T> File.writeAtomically(
    sync: Boolean = true,
    attempts: Int = MOVE_ATTEMPTS,
    write: (FileOutputStream) -> T,
): T {
    val dir = absoluteFile.parentFile ?: error("No parent directory for $this")
    dir.mkdirs()
    val tmp = Files.createTempFile(dir.toPath(), "$name.", ".tmp").toFile()
    var moved = false
    try {
        val result =
            FileOutputStream(tmp).use { out ->
                write(out).also {
                    out.flush()
                    if (sync) out.fd.sync()
                }
            }
        tmp.moveOver(this, attempts)
        moved = true
        return result
    } finally {
        if (!moved) tmp.delete()
    }
}

/**
 * Moves this file over [target], atomically where the file system supports it.
 *
 * On Windows a file just closed can still be held for a moment by an antivirus, the search
 * indexer or a backup agent, which fails the rename; those failures are retried up to [attempts]
 * times in all.
 */
internal fun File.moveOver(
    target: File,
    attempts: Int = MOVE_ATTEMPTS,
) {
    var attempt = 0
    while (true) {
        try {
            try {
                Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return
        } catch (e: FileSystemException) {
            // Elsewhere a rename is never blocked by another process, so a failure there is final.
            if (!PlatformInfo.isWindows || !e.isTransient() || ++attempt >= attempts) throw e
            Thread.sleep(minOf(MOVE_RETRY_BASE_MS shl (attempt - 1), MOVE_RETRY_MAX_MS))
        }
    }
}

/**
 * AccessDeniedException or a plain FileSystemException (sharing violation) may pass; anything
 * more specific (no such file, not a directory...) will not.
 */
private fun FileSystemException.isTransient(): Boolean = this is AccessDeniedException || javaClass == FileSystemException::class.java

private const val MOVE_ATTEMPTS = 8

/** One short retry: a write on the UI thread must not stall the window for seconds. */
internal const val UI_MOVE_ATTEMPTS = 2
private const val MOVE_RETRY_BASE_MS = 50L
private const val MOVE_RETRY_MAX_MS = 1_000L

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

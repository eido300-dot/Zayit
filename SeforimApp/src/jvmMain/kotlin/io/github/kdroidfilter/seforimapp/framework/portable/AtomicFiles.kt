package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicBoolean

private val forceFallbackLogged = AtomicBoolean(false)

/**
 * Writes [bytes] to [path] as a new regular file and flushes them to the device before returning.
 * Whatever is at [path] is removed first and never written through: on a drive someone else
 * prepared, a link planted there would otherwise redirect the write to any file on the computer.
 */
@Throws(IOException::class)
internal fun writeDurably(
    path: Path,
    bytes: ByteArray,
) {
    Files.deleteIfExists(path)
    // Written through the channel that created the file, so a link swapped in by name cannot receive the bytes.
    Files.newByteChannel(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }
    RandomAccessFile(path.toFile(), "rw").use { raf ->
        // Opened again only to flush ("rw": Windows flushes only a handle open for writing).
        // RandomAccessFile follows links: refuse if the file was swapped for one.
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw FileSystemException(path.toString(), null, "not a regular file")
        forceToDisk(raf)
    }
}

/**
 * Flushes [raf] to the device. On macOS `FileChannel.force` issues `F_FULLFSYNC`, which some file
 * systems reject; a plain `fsync` through the file descriptor is used as the fallback.
 */
@Throws(IOException::class)
internal fun forceToDisk(
    raf: RandomAccessFile,
    force: (FileChannel) -> Unit = { it.force(true) },
) {
    try {
        force(raf.channel)
    } catch (e: IOException) {
        if (forceFallbackLogged.compareAndSet(false, true)) {
            warnln(e) { "[portable] full flush not supported here, falling back to fsync" }
        }
        raf.fd.sync()
    }
}

/**
 * Best-effort flush of a directory entry after a rename, so the rename itself survives a power
 * loss on Linux and macOS. Windows cannot open a directory as a channel; that failure is expected
 * and ignored there, which avoids an explicit operating-system check.
 */
internal fun syncDirectory(dir: Path) {
    try {
        FileChannel.open(dir, READ).use { it.force(true) }
    } catch (_: IOException) {
        // Not supported on this platform or file system; the rename is still atomic.
    }
}

/**
 * [Files.move] that retries transient failures. On Windows, antivirus scanners and the search
 * indexer briefly hold newly written files open, which makes a rename fail with an access-denied
 * or generic file-system error. Errors that retrying cannot fix (target exists, source missing,
 * atomic move unsupported) are rethrown immediately.
 */
@Throws(IOException::class)
internal fun moveWithRetry(
    source: Path,
    target: Path,
    vararg options: CopyOption,
    policy: RetryPolicy = RetryPolicy.SETTINGS,
    sleeper: (Long) -> Unit = Thread::sleep,
    move: (Path, Path) -> Unit = { from, to -> Files.move(from, to, *options) },
) {
    var waited = 0L
    var delay = policy.initialDelayMillis
    while (true) {
        try {
            move(source, target)
            return
        } catch (e: FileSystemException) {
            if (!isTransient(e) || waited + delay > policy.maxTotalMillis) throw e
            sleeper(delay)
            waited += delay
            delay = minOf(delay * 2, policy.maxDelayMillis)
        }
    }
}

/** Only an access-denied error or the bare base type (a Windows sharing violation) is worth retrying. */
private fun isTransient(e: FileSystemException): Boolean = e is AccessDeniedException || e.javaClass == FileSystemException::class.java

internal fun siblingWithSuffix(
    path: Path,
    suffix: String,
): Path {
    val name = requireNotNull(path.fileName) { "a file path is required, got $path" }
    return path.resolveSibling("$name$suffix")
}

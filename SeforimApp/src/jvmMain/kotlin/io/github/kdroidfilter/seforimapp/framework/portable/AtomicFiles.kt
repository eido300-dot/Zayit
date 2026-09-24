package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.atomic.AtomicBoolean

private val forceFallbackLogged = AtomicBoolean(false)

/**
 * Replaces [target] with [bytes] so that a crash or an unplugged drive leaves either the old or the
 * new content, never a mix: the bytes go to a sibling `.tmp` file, are flushed to the device, and
 * the `.tmp` is then renamed over [target]. A missing parent folder is an error, not created here.
 */
@Throws(IOException::class)
fun writeAtomically(
    target: Path,
    bytes: ByteArray,
) {
    val tmp = siblingWithSuffix(target, ".tmp")
    try {
        writeDurably(tmp, bytes)
        moveWithRetry(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (e: IOException) {
        deleteQuietly(tmp, e)
        throw e
    }
    target.parent?.let(::syncDirectory)
}

/** Writes [bytes] to [path] (truncating it) and flushes them to the device before returning. */
@Throws(IOException::class)
internal fun writeDurably(
    path: Path,
    bytes: ByteArray,
) {
    RandomAccessFile(path.toFile(), "rw").use { raf ->
        raf.setLength(0)
        raf.write(bytes)
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
fun moveWithRetry(
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

private fun deleteQuietly(
    path: Path,
    cause: IOException,
) {
    try {
        Files.deleteIfExists(path)
    } catch (cleanup: IOException) {
        cause.addSuppressed(cleanup)
    }
}

package io.github.kdroidfilter.seforimapp.framework.database

import com.sun.nio.file.ExtendedOpenOption
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

private const val CHUNK_BYTES = 1L shl 20

/**
 * Reads [file] twice, a chunk at a time: once around the operating system's cache (direct I/O) and
 * once through it, and compares the two. A counterfeit drive silently drops writes beyond its real
 * size, but the cache still holds the bytes as written, so a check that only reads through the
 * cache passes. Direct reads come from the drive itself.
 *
 * Returns false only when the bytes differ, twice in a row (a file changing while it is read
 * differs once). Returns null when this cannot be told here: direct I/O is not supported by the
 * platform or the file system, or a read fails or comes back short. Those cases are left to the
 * other checks. Calls [checkCancelled] between chunks.
 */
internal fun readsBackFromDrive(
    file: Path,
    checkCancelled: () -> Unit = {},
): Boolean? =
    try {
        val blockSize = Files.getFileStore(file).blockSize
        // Direct I/O aligns its buffer to a power-of-two block size; anything else cannot be compared here.
        if (blockSize <= 0 || blockSize and (blockSize - 1) != 0L) {
            null
        } else {
            val size = Files.size(file)
            FileChannel.open(file, READ, ExtendedOpenOption.DIRECT).use { direct ->
                FileChannel.open(file, READ).use { cached ->
                    ChunkComparer(direct, cached, blockSize, chunkBytesFor(size, blockSize)).compareAll(size, checkCancelled)
                }
            }
        }
    } catch (e: UnsupportedOperationException) {
        warnln(e) { "[LibraryVerify] reading around the cache is not supported here" }
        null
    } catch (e: IOException) {
        warnln(e) { "[LibraryVerify] cannot read ${file.fileName} around the cache" }
        null
    }

/**
 * The buffer size for a file: [CHUNK_BYTES], but no more than the file needs, rounded up to whole
 * blocks. A library has many small index files; giving each a full megabyte of direct memory, which
 * is only freed when the garbage collector runs, cost about a millisecond per file.
 */
internal fun chunkBytesFor(
    fileSize: Long,
    blockSize: Long,
): Long = (minOf(CHUNK_BYTES, maxOf(fileSize, 1L)) + blockSize - 1) / blockSize * blockSize

private class ChunkComparer(
    private val direct: FileChannel,
    private val cached: FileChannel,
    blockSize: Long,
    private val chunk: Long,
) {
    // Direct I/O needs a buffer aligned to the file system's block size.
    private val directBuffer = ByteBuffer.allocateDirect((chunk + blockSize).toInt()).alignedSlice(blockSize.toInt())
    private val cachedBuffer = ByteBuffer.allocate(chunk.toInt())

    fun compareAll(
        size: Long,
        checkCancelled: () -> Unit,
    ): Boolean? {
        var position = 0L
        while (position < size) {
            checkCancelled()
            val length = minOf(chunk, size - position).toInt()
            val same = chunkMatches(position, length) ?: return null
            if (!same && chunkMatches(position, length) != true) return false
            position += length
        }
        return true
    }

    /** Null when a read came back short, which direct I/O allows only at the end of the file. */
    private fun chunkMatches(
        position: Long,
        length: Int,
    ): Boolean? {
        // A direct read must cover whole blocks; at the end of the file it returns the bytes there are.
        directBuffer.clear().limit(directBuffer.capacity().coerceAtMost(chunk.toInt()))
        if (direct.read(directBuffer, position) < length) return null
        cachedBuffer.clear().limit(length)
        while (cachedBuffer.hasRemaining()) {
            if (cached.read(cachedBuffer, position + cachedBuffer.position()) < 0) return null
        }
        directBuffer.flip().limit(length)
        cachedBuffer.flip()
        return directBuffer.mismatch(cachedBuffer) == -1
    }
}

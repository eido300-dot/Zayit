package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** More than two copy chunks, so a copy reports and can stop inside the file. */
private const val LARGE_FILE_BYTES = 20 * 1024 * 1024

class ProgramCopyTest {
    private val root: Path = createTempDirectory("zayit-program-copy")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a large file is copied in chunks, each reported, so a copy can stop inside it`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES) { it.toByte() })
        val to = root.resolve("large-copy.bin")
        val chunks = mutableListOf<Long>()

        copyFileDurably(from, to) { chunks += it }

        assertTrue(chunks.size >= 3, chunks.toString())
        assertEquals(LARGE_FILE_BYTES.toLong(), chunks.sum())
        assertTrue(Files.readAllBytes(from).contentEquals(Files.readAllBytes(to)))
    }

    @Test
    fun `each chunk is flushed to the drive before it is reported`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        val events = mutableListOf<String>()

        val force = { channel: FileChannel, metadata: Boolean ->
            channel.force(metadata)
            events += "flush($metadata)"
        }

        copyFileDurably(from, root.resolve("large-copy.bin"), force) { events += "report" }

        // 20 MB: two full chunks and a partial one, then the file's metadata.
        val chunk = listOf("flush(false)", "report")
        assertEquals(chunk + chunk + chunk + "flush(true)", events)
    }

    @Test
    fun `a failed flush fails the copy, as the drive's write error it is`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        var reports = 0
        val failing = { _: FileChannel, _: Boolean -> throw IOException("No space left on device") }

        val failure = assertFailsWith<IOException> { copyFileDurably(from, root.resolve("large-copy.bin"), failing) { reports++ } }

        assertEquals("No space left on device", failure.message)
        assertEquals(0, reports)
    }

    @Test
    fun `a full flush refused from the start falls back to fsync where that may happen`() {
        val file = Files.write(root.resolve("flushed.bin"), byteArrayOf(1))
        var calls = 0
        val refusing = { _: FileChannel, _: Boolean ->
            calls++
            throw IOException("refused")
        }
        FileChannel.open(file, WRITE).use { channel ->
            val flush = DeviceFlush(channel, file, refusing, fullFlushMayBeRefused = true)

            flush.flush(metadata = false)
            flush.flush(metadata = true)
        }

        assertEquals(1, calls)
    }

    @Test
    fun `after a flush went through, a failed one is an error even where a full flush may be refused`() {
        val file = Files.write(root.resolve("flushed.bin"), byteArrayOf(1))
        var calls = 0
        val failingAfterFirst = { _: FileChannel, _: Boolean ->
            if (++calls > 1) throw IOException("I/O error")
        }
        FileChannel.open(file, WRITE).use { channel ->
            val flush = DeviceFlush(channel, file, failingAfterFirst, fullFlushMayBeRefused = true)
            flush.flush(metadata = false)

            assertFailsWith<IOException> { flush.flush(metadata = true) }
        }
    }

    @Test
    fun `a copy stops at the first chunk whose report throws`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        var reports = 0

        assertFailsWith<CancellationException> {
            copyFileDurably(from, root.resolve("large-copy.bin")) {
                reports++
                throw CancellationException("cancelled")
            }
        }

        assertEquals(1, reports)
    }

    @Test
    fun `a read-only file is copied and keeps its permission bits`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val from = root.resolve("readonly.bin")
        from.writeText("r")
        Files.setPosixFilePermissions(from, PosixFilePermissions.fromString("r--r--r--"))
        val to = root.resolve("readonly-copy.bin")

        copyFileDurably(from, to) { }

        assertEquals("r", to.readText())
        assertEquals(PosixFilePermissions.fromString("r--r--r--"), Files.getPosixFilePermissions(to))
    }
}

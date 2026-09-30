package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
    fun `each chunk is flushed to the drive before it is reported, the last one with the metadata`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        val events = mutableListOf<String>()
        val recording =
            DeviceFlush { channel, metadata ->
                channel.force(metadata)
                events += "flush($metadata)"
            }

        copyFileDurably(from, root.resolve("large-copy.bin"), recording) { events += "report" }

        // 20 MB: two full chunks and a partial one.
        assertEquals(listOf("flush(false)", "report", "flush(false)", "report", "flush(true)", "report"), events)
    }

    @Test
    fun `an empty file is flushed too`() {
        val from = Files.write(root.resolve("empty.bin"), ByteArray(0))
        val events = mutableListOf<String>()

        val recording = DeviceFlush { _, metadata -> events += "flush($metadata)" }

        copyFileDurably(from, root.resolve("empty-copy.bin"), recording) { events += "report" }

        assertEquals(listOf("flush(true)"), events)
    }

    @Test
    fun `a failed flush fails the copy, as the drive's write error it is`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        var reports = 0
        val failing = DeviceFlush { _, _ -> throw IOException("No space left on device") }

        val failure = assertFailsWith<IOException> { copyFileDurably(from, root.resolve("large-copy.bin"), failing) { reports++ } }

        assertEquals("No space left on device", failure.message)
        assertEquals(0, reports)
    }

    @Test
    fun `a drive that refuses a full flush gets fsync instead, for a read-only file too`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(from, PosixFilePermissions.fromString("r--r--r--"))
        }
        val refusing = DeviceFlush(fullFlushRefused = true) { _, _ -> error("the full flush is not asked for") }
        var copied = 0L

        copyFileDurably(from, root.resolve("large-copy.bin"), refusing) { copied += it }

        assertEquals(LARGE_FILE_BYTES.toLong(), copied)
    }

    @Test
    fun `fsync is never done through a link put in the copied file's place`() {
        val file = root.resolve("copied.bin")
        FileChannel.open(file, setOf(StandardOpenOption.CREATE_NEW, WRITE)).use { channel ->
            Files.delete(file)
            Files.createSymbolicLink(file, Files.write(root.resolve("someone-else.bin"), byteArrayOf(1)))

            assertFailsWith<IOException> { DeviceFlush(fullFlushRefused = true).open(channel, file) }
        }
    }

    @Test
    fun `finding how to flush a drive leaves nothing behind`() {
        val flush = DeviceFlush.forDrive(root)

        FileChannel.open(Files.write(root.resolve("flushed.bin"), byteArrayOf(1)), WRITE).use { channel ->
            flush.open(channel, root.resolve("flushed.bin")).use { it.flush(metadata = true) }
        }
        Files.list(root).use { entries -> assertEquals(listOf("flushed.bin"), entries.map { it.fileName.toString() }.toList()) }
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

package io.github.kdroidfilter.seforimapp.framework.portable

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFilesTest {
    private val root: Path = createTempDirectory("zayit-atomic-files")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `writeAtomically creates the file`() {
        val target = root.resolve("a.bin")

        writeAtomically(target, byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
    }

    @Test
    fun `writeAtomically replaces existing content completely`() {
        val target = root.resolve("a.bin")
        Files.write(target, ByteArray(100) { 9 })

        writeAtomically(target, byteArrayOf(7))

        assertContentEquals(byteArrayOf(7), Files.readAllBytes(target))
    }

    @Test
    fun `writeAtomically leaves no temporary file behind`() {
        val target = root.resolve("a.bin")

        writeAtomically(target, byteArrayOf(1))

        assertEquals(listOf("a.bin"), Files.list(root).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `writeAtomically fails and leaves nothing when the parent folder is missing`() {
        val target = root.resolve("missing/a.bin")

        assertFailsWith<IOException> { writeAtomically(target, byteArrayOf(1)) }

        assertFalse(Files.exists(root.resolve("missing")))
    }

    @Test
    fun `forceToDisk falls back to fd sync when channel force fails`() {
        val file = root.resolve("f.bin")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.write(byteArrayOf(5))
            // Must not throw: the fallback path syncs the file descriptor instead.
            forceToDisk(raf) { throw IOException("F_FULLFSYNC not supported") }
        }
        assertContentEquals(byteArrayOf(5), Files.readAllBytes(file))
    }

    @Test
    fun `moveWithRetry succeeds after transient sharing violations`() {
        var attempts = 0
        val delays = mutableListOf<Long>()

        moveWithRetry(
            source = root.resolve("s"),
            target = root.resolve("t"),
            policy = RetryPolicy.SETTINGS,
            sleeper = { delays += it },
            move = { _, _ ->
                attempts++
                if (attempts < 3) throw FileSystemException("in use by another process")
            },
        )

        assertEquals(3, attempts)
        assertEquals(2, delays.size)
    }

    @Test
    fun `moveWithRetry retries access denied then gives up within the policy budget`() {
        var attempts = 0
        val delays = mutableListOf<Long>()

        assertFailsWith<AccessDeniedException> {
            moveWithRetry(
                source = root.resolve("s"),
                target = root.resolve("t"),
                policy = RetryPolicy.SETTINGS,
                sleeper = { delays += it },
                move = { _, _ ->
                    attempts++
                    throw AccessDeniedException("t")
                },
            )
        }

        assertTrue(attempts > 1, "expected retries, got $attempts attempt(s)")
        assertTrue(delays.sum() <= RetryPolicy.SETTINGS.maxTotalMillis, "waited ${delays.sum()} ms")
    }

    @Test
    fun `moveWithRetry does not retry non transient errors`() {
        listOf(
            FileAlreadyExistsException("t"),
            NoSuchFileException("s"),
        ).forEach { failure ->
            var attempts = 0
            assertFailsWith<FileSystemException> {
                moveWithRetry(
                    source = root.resolve("s"),
                    target = root.resolve("t"),
                    policy = RetryPolicy.SETTINGS,
                    sleeper = { },
                    move = { _, _ ->
                        attempts++
                        throw failure
                    },
                )
            }
            assertEquals(1, attempts, "should not retry ${failure::class.simpleName}")
        }
    }

    @Test
    fun `moveWithRetry performs a real atomic replace`() {
        val source = root.resolve("s")
        val target = root.resolve("t")
        Files.write(source, byteArrayOf(1))
        Files.write(target, byteArrayOf(2))

        moveWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        assertFalse(Files.exists(source))
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(target))
    }
}

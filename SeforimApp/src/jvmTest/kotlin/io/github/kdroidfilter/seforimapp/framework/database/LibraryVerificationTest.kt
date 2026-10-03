package io.github.kdroidfilter.seforimapp.framework.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.NIOFSDirectory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryVerificationTest {
    private val root: Path = createTempDirectory("zayit-library-verify")
    private val files = libraryFilesFor(root.resolve("seforim.db"))
    private val executor = Executors.newSingleThreadExecutor()
    private val verifier = LibraryVerifier(ioDispatcher = executor.asCoroutineDispatcher())

    @AfterTest
    fun cleanUp() {
        executor.shutdownNow()
        root.toFile().deleteRecursively()
    }

    private fun createDatabase() {
        DriverManager.getConnection("jdbc:sqlite:${files.database}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT)")
                statement.execute("CREATE INDEX idx_title ON book(title)")
                repeat(300) { statement.execute("INSERT INTO book (title) VALUES ('${"x".repeat(1_000)}$it')") }
            }
        }
    }

    private fun createIndex(directory: Path) {
        NIOFSDirectory(directory).use { lucene ->
            IndexWriter(lucene, IndexWriterConfig()).use { writer ->
                repeat(50) { writer.addDocument(Document().apply { add(StringField("id", "$it", Field.Store.YES)) }) }
                writer.commit()
            }
        }
    }

    /** Overwrites [length] bytes at [offset], as a counterfeit drive does when a write wraps around. */
    private fun scribble(
        file: Path,
        offset: Long,
        length: Int,
    ) {
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.seek(offset)
            raf.write(ByteArray(length) { 0x5A })
        }
    }

    private fun verifyWith(probe: LibraryIntegrityProbe): List<DamagedPart> =
        runBlocking { LibraryVerifier(probe, executor.asCoroutineDispatcher()).verify(files) }

    private fun largestFile(directory: Path): Path =
        Files.list(directory).use { entries ->
            entries.filter { !it.fileName.toString().endsWith(".lock") }.max(compareBy(Files::size)).get()
        }

    @Test
    fun `intact library reads back without damage`() {
        createDatabase()
        createIndex(files.textIndex)
        createIndex(files.lookupIndex)

        assertEquals(emptyList(), runBlocking { verifier.verify(files) })
    }

    @Test
    fun `overwritten database pages are found`() {
        createDatabase()
        scribble(files.database, offset = Files.size(files.database) / 2, length = 8_192)

        assertEquals(listOf(DamagedPart.Database), runBlocking { verifier.verify(files) })
    }

    @Test
    fun `index file with lost bytes fails its checksum`() {
        createDatabase()
        createIndex(files.textIndex)
        val segment = largestFile(files.textIndex)
        scribble(segment, offset = Files.size(segment) / 2, length = 16)

        assertEquals(listOf(DamagedPart.TextIndex), runBlocking { verifier.verify(files) })
    }

    @Test
    fun `missing parts are left to the startup check`() {
        assertEquals(emptyList(), runBlocking { verifier.verify(files) })
    }

    @Test
    fun `cancelling stops the running database check`() {
        createDatabase()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val blocking =
            object : LibraryIntegrityProbe {
                override fun databaseIntact(
                    database: Path,
                    onStart: (cancel: () -> Unit) -> Unit,
                ): Boolean {
                    onStart { stopped.countDown() }
                    started.countDown()
                    stopped.await(5, TimeUnit.SECONDS)
                    return false
                }

                override fun indexIntact(
                    directory: Path,
                    checkCancelled: () -> Unit,
                ) = true
            }
        val cancellable = LibraryVerifier(blocking, executor.asCoroutineDispatcher())

        runBlocking {
            withTimeout(5_000) {
                // On another thread: the test thread blocks on the latch below.
                val run = async(Dispatchers.Default) { cancellable.verify(files) }
                started.await(5, TimeUnit.SECONDS)
                run.cancel()
                assertFailsWith<CancellationException> { run.await() }
            }
        }
        assertEquals(0L, stopped.count, "the running query must be told to stop")
    }

    @Test
    fun `a file that reads back the same around the cache is not reported`() {
        createDatabase()

        // Null where the temp folder's file system has no direct I/O (tmpfs on older kernels).
        assertTrue(readsBackFromDrive(files.database) != false)
    }

    @Test
    fun `a file that differs on the drive from the cache is damaged`() {
        createDatabase()
        createIndex(files.textIndex)
        val driveLostData =
            object : LibraryIntegrityProbe by RealLibraryIntegrityProbe {
                override fun matchesDrive(
                    file: Path,
                    checkCancelled: () -> Unit,
                ): Boolean? = file != files.database
            }

        assertEquals(listOf(DamagedPart.Database), verifyWith(driveLostData))
    }

    @Test
    fun `an index file that differs on the drive from the cache is damaged`() {
        createDatabase()
        createIndex(files.textIndex)
        val segment = largestFile(files.textIndex)
        val driveLostData =
            object : LibraryIntegrityProbe by RealLibraryIntegrityProbe {
                override fun matchesDrive(
                    file: Path,
                    checkCancelled: () -> Unit,
                ): Boolean? = file != segment
            }

        assertEquals(listOf(DamagedPart.TextIndex), verifyWith(driveLostData))
    }

    @Test
    fun `verification runs once per installed library and only on a later portable launch`() {
        val fingerprint = libraryFingerprint("20260601094341", databaseSize = 7_000L)

        assertTrue(shouldVerifyLibrary(isPortable = true, installedThisSession = false, fingerprint, lastVerified = null))
        assertFalse(shouldVerifyLibrary(isPortable = true, installedThisSession = false, fingerprint, lastVerified = fingerprint))
        assertFalse(shouldVerifyLibrary(isPortable = true, installedThisSession = true, fingerprint, lastVerified = null))
        assertFalse(shouldVerifyLibrary(isPortable = false, installedThisSession = false, fingerprint, lastVerified = null))
        val updated = libraryFingerprint("20260701000000", databaseSize = 7_000L)
        assertTrue(shouldVerifyLibrary(isPortable = true, installedThisSession = false, updated, lastVerified = fingerprint))
    }

    @Test
    fun `a check that could not run is inconclusive, not damage`() {
        createDatabase()
        val probe =
            object : LibraryIntegrityProbe by RealLibraryIntegrityProbe {
                override fun databaseIntact(
                    database: Path,
                    onStart: (cancel: () -> Unit) -> Unit,
                ): Boolean = throw VerificationInconclusiveException("quick_check could not run")
            }

        assertFailsWith<VerificationInconclusiveException> { verifyWith(probe) }
    }

    @Test
    fun `a drive that disappears during the check is not reported as damaged`() {
        createDatabase()
        val probe =
            object : LibraryIntegrityProbe by RealLibraryIntegrityProbe {
                override fun databaseIntact(
                    database: Path,
                    onStart: (cancel: () -> Unit) -> Unit,
                ): Boolean {
                    Files.delete(database) // the stick is pulled out
                    return false
                }
            }

        assertFailsWith<VerificationInconclusiveException> { verifyWith(probe) }
    }

    @Test
    fun `an index that cannot be read for a reason other than damage is inconclusive`() {
        createIndex(files.textIndex)
        val segments =
            Files.list(files.textIndex).use { entries ->
                entries.filter { it.fileName.toString().startsWith("segments_") }.findFirst().get()
            }
        Files.delete(segments) // reading fails with NoSuchFileException, as when the drive goes away mid-read

        assertFailsWith<VerificationInconclusiveException> { RealLibraryIntegrityProbe.indexIntact(files.textIndex) { } }
    }

    @Test
    fun `read back buffers are sized to the file, in whole blocks`() {
        assertEquals(4096L, chunkBytesFor(fileSize = 0, blockSize = 4096))
        assertEquals(4096L, chunkBytesFor(fileSize = 100, blockSize = 4096))
        assertEquals(8192L, chunkBytesFor(fileSize = 4097, blockSize = 4096))
        assertEquals(1L shl 20, chunkBytesFor(fileSize = 7_500_000_000L, blockSize = 4096))
    }
}

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
    fun `verification runs once per installed library and only on a later portable launch`() {
        val fingerprint = libraryFingerprint("20260601094341", databaseSize = 7_000L, databaseModified = 1_000L)

        assertTrue(shouldVerifyLibrary(isPortable = true, installedThisSession = false, fingerprint, lastVerified = null))
        assertFalse(shouldVerifyLibrary(isPortable = true, installedThisSession = false, fingerprint, lastVerified = fingerprint))
        assertFalse(shouldVerifyLibrary(isPortable = true, installedThisSession = true, fingerprint, lastVerified = null))
        assertFalse(shouldVerifyLibrary(isPortable = false, installedThisSession = false, fingerprint, lastVerified = null))
        val reinstalled = libraryFingerprint("20260601094341", databaseSize = 7_000L, databaseModified = 2_000L)
        assertTrue(shouldVerifyLibrary(isPortable = true, installedThisSession = false, reinstalled, lastVerified = fingerprint))
    }
}

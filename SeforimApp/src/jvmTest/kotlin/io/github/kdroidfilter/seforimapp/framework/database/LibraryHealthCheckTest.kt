package io.github.kdroidfilter.seforimapp.framework.database

import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.NIOFSDirectory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryHealthCheckTest {
    private val root: Path = createTempDirectory("zayit-library-health")
    private val files = libraryFilesFor(root.resolve("seforim.db"))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun sql(
        path: Path,
        vararg statements: String,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
    }

    private fun createBooksDatabase(
        books: Int = 1,
        padding: Int = 0,
    ) {
        sql(files.database, "CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT)")
        repeat(books) { sql(files.database, "INSERT INTO book (title) VALUES ('${"x".repeat(padding)}')") }
    }

    private fun createIndex(directory: Path) {
        NIOFSDirectory(directory).use { lucene ->
            IndexWriter(lucene, IndexWriterConfig()).use { writer ->
                writer.addDocument(Document().apply { add(StringField("id", "1", Field.Store.YES)) })
                writer.commit()
            }
        }
    }

    private fun createCompleteLibrary() {
        createBooksDatabase()
        Files.write(files.catalog, byteArrayOf(1))
        createIndex(files.textIndex)
        createIndex(files.lookupIndex)
        sql(
            files.dictionary,
            "CREATE TABLE surface (a)",
            "CREATE TABLE variant (a)",
            "CREATE TABLE base (a)",
            "CREATE TABLE surface_variant (a)",
        )
    }

    private fun check(): LibraryHealth = checkLibraryHealth(files, dictionaryOverridden = false)

    @Test
    fun `complete library is healthy`() {
        createCompleteLibrary()

        assertEquals(emptyList(), check().problems)
    }

    @Test
    fun `missing database needs a reinstall`() {
        val health = check()

        assertTrue(LibraryProblem.DatabaseMissing in health.problems)
        assertTrue(health.needsReinstall)
    }

    @Test
    fun `zero byte database is reported empty and stays zero bytes`() {
        Files.createFile(files.database)

        assertTrue(LibraryProblem.DatabaseEmpty in check().problems)
        assertEquals(0L, Files.size(files.database), "the check must not write a fresh schema into it")
    }

    @Test
    fun `text file is not an SQLite database`() {
        Files.writeString(files.database, "not a database, just text that is long enough to fill a header ".repeat(4))

        assertTrue(LibraryProblem.DatabaseNotSqlite in check().problems)
    }

    @Test
    fun `database cut short by an interrupted copy is truncated`() {
        createBooksDatabase(books = 200, padding = 2_000)
        val bytes = Files.readAllBytes(files.database)
        Files.write(files.database, bytes.copyOf(bytes.size / 2))

        assertTrue(LibraryProblem.DatabaseTruncated in check().problems)
    }

    @Test
    fun `database without books or without the book table is empty`() {
        createBooksDatabase(books = 0)
        assertTrue(LibraryProblem.DatabaseEmpty in check().problems)

        Files.delete(files.database)
        sql(files.database, "CREATE TABLE other (a)")
        assertTrue(LibraryProblem.DatabaseEmpty in check().problems)
    }

    @Test
    fun `check leaves a WAL database untouched`() {
        createCompleteLibrary()
        sql(files.database, "PRAGMA journal_mode=WAL")
        val modified = Files.getLastModifiedTime(files.database)

        check()

        assertFalse(Files.exists(root.resolve("seforim.db-wal")))
        assertFalse(Files.exists(root.resolve("seforim.db-shm")))
        assertEquals(modified, Files.getLastModifiedTime(files.database))
    }

    @Test
    fun `missing index folder is reported and not created`() {
        createCompleteLibrary()
        files.textIndex.toFile().deleteRecursively()

        assertEquals(listOf(LibraryProblem.TextIndexMissing), check().problems)
        assertFalse(Files.exists(files.textIndex))
    }

    @Test
    fun `index with a missing segment file is incomplete`() {
        createCompleteLibrary()
        Files
            .list(files.lookupIndex)
            .use { entries ->
                entries.filter { !it.fileName.toString().startsWith("segments") && !it.fileName.toString().endsWith(".lock") }.findFirst()
            }.ifPresent(Files::delete)

        assertEquals(listOf(LibraryProblem.LookupIndexMissing), check().problems)
    }

    @Test
    fun `missing catalog needs a reinstall and a missing dictionary only degrades`() {
        createCompleteLibrary()
        Files.delete(files.catalog)
        Files.delete(files.dictionary)

        val health = check()

        assertEquals(listOf(LibraryProblem.CatalogMissing, LibraryProblem.DictionaryMissing), health.problems)
        assertTrue(health.needsReinstall)
        assertEquals(listOf(LibraryProblem.DictionaryMissing), checkOptionalParts(files, dictionaryOverridden = false))
        assertEquals(Severity.Degraded, LibraryProblem.DictionaryMissing.severity)
    }

    @Test
    fun `the check before the first window leaves the indexes and the dictionary to the one after it`() {
        createCompleteLibrary()
        files.textIndex.toFile().deleteRecursively()
        Files.delete(files.dictionary)

        assertTrue(checkRequiredParts(files).isHealthy)
        val optional = checkOptionalParts(files, dictionaryOverridden = false)
        assertEquals(listOf(LibraryProblem.TextIndexMissing, LibraryProblem.DictionaryMissing), optional)
        assertTrue(optional.all { it.severity == Severity.Degraded }, "what the window does not wait for only degrades the app")
    }

    @Test
    fun `dictionary configured elsewhere is not reported`() {
        createCompleteLibrary()
        Files.delete(files.dictionary)

        assertTrue(checkLibraryHealth(files, dictionaryOverridden = true).isHealthy)
    }

    @Test
    fun `header parsing rejects bad magic and odd page sizes`() {
        assertNull(parseSqliteHeader(ByteArray(100)))
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyOf(100)
        header[16] = 0x03
        header[17] = 0x00 // 768: not a power of two
        assertNull(parseSqliteHeader(header))
        header[16] = 0x00
        header[17] = 0x01 // 1 means 65536
        assertEquals(65_536L, parseSqliteHeader(header)?.pageSize)
    }

    @Test
    fun `a network path gets the URL form SQLite accepts`() {
        assertEquals(
            "jdbc:sqlite:file:////server/share/zayit%20data/seforim.db?mode=ro&immutable=1",
            readOnlyUrl(URI("file://server/share/zayit%20data/seforim.db"), immutable = true),
        )
        assertEquals("jdbc:sqlite:file:///C:/Zayit/seforim.db?mode=ro", readOnlyUrl(URI("file:///C:/Zayit/seforim.db"), immutable = false))
    }

    @Test
    fun `a hot rollback journal keeps SQLite from opening the database as immutable`() {
        Files.write(files.database, byteArrayOf(1))
        assertTrue(readOnlyUrl(files.database).endsWith("immutable=1"))

        Files.write(root.resolve("seforim.db-journal"), byteArrayOf(1))
        assertFalse(readOnlyUrl(files.database).contains("immutable"))
    }

    @Test
    fun `books still only in the write-ahead log are found`() {
        DriverManager.getConnection("jdbc:sqlite:${files.database}").use { writer ->
            writer.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA wal_autocheckpoint=0")
                statement.execute("CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT)")
                statement.execute("INSERT INTO book (title) VALUES ('x')")
            }
            // The writer stays open, so the pages are not yet copied into the database file.
            assertTrue(Files.size(files.database.resolveSibling("seforim.db-wal")) > 0)
            assertEquals(BookTableState.HasBooks, RealLibraryProbe.bookTable(files.database))
        }
    }

    @Test
    fun `library file names follow the database name`() {
        val other = libraryFilesFor(root.resolve("custom.sqlite"))

        assertEquals("seforim.db.lucene", files.textIndex.fileName.toString())
        assertEquals("seforim.db.lookup.lucene", files.lookupIndex.fileName.toString())
        assertEquals("custom.sqlite.luceneindex", other.textIndex.fileName.toString())
        assertEquals("custom.sqlite.lookupindex", other.lookupIndex.fileName.toString())
        assertEquals(root.resolve("lexical.db"), files.dictionary)
        assertEquals(root.resolve("catalog.pb"), files.catalog)
    }

    private class FixedProbe(
        private val table: BookTableState,
    ) : LibraryProbe {
        override fun size(path: Path): Long? = 1_000_000L

        override fun header(database: Path) = SqliteHeader(pageSize = 4096, pageCount = null)

        override fun bookTable(database: Path) = table

        override fun luceneIndexComplete(directory: Path) = true

        override fun dictionaryValid(dictionary: Path) = true
    }

    /** A healthy library that records which files were read. */
    private class RecordingProbe : LibraryProbe {
        val reads = mutableSetOf<Path>()

        override fun size(path: Path): Long? = 1_000_000L.also { reads.add(path) }

        override fun header(database: Path) = SqliteHeader(pageSize = 4096, pageCount = null).also { reads.add(database) }

        override fun bookTable(database: Path) = BookTableState.HasBooks.also { reads.add(database) }

        override fun luceneIndexComplete(directory: Path) = true.also { reads.add(directory) }

        override fun dictionaryValid(dictionary: Path) = true.also { reads.add(dictionary) }
    }

    @Test
    fun `each half of the check reads only its own parts`() {
        val required = RecordingProbe()
        assertTrue(checkRequiredParts(files, required).isHealthy)
        assertEquals(setOf(files.database, files.catalog), required.reads)

        val optional = RecordingProbe()
        assertEquals(emptyList(), checkOptionalParts(files, optional, dictionaryOverridden = false))
        assertEquals(setOf(files.textIndex, files.lookupIndex, files.dictionary), optional.reads)
    }

    @Test
    fun `a check that could not run is not a damaged database, nor a conclusive check`() {
        val inconclusive = checkLibraryHealth(files, FixedProbe(BookTableState.Inconclusive), false)
        assertEquals(emptyList(), inconclusive.problems)
        assertFalse(inconclusive.conclusive, "nothing is ruled out")
        assertFalse(checkRequiredParts(files, FixedProbe(BookTableState.Inconclusive)).conclusive)
        assertTrue(checkRequiredParts(files, FixedProbe(BookTableState.HasBooks)).conclusive)
        val unreadable = checkLibraryHealth(files, FixedProbe(BookTableState.Unreadable), false)
        assertEquals(listOf(LibraryProblem.DatabaseUnreadable), unreadable.problems)
        assertTrue(unreadable.conclusive)
    }

    @Test
    fun `only SQLite reporting a damaged file counts as damage`() {
        assertTrue(java.sql.SQLException("x", null, 11).reportsDamagedFile())
        assertTrue(java.sql.SQLException("x", null, 26).reportsDamagedFile())
        // Extended result codes keep the primary code in the low byte (SQLITE_CORRUPT_VTAB = 267).
        assertTrue(java.sql.SQLException("x", null, 267).reportsDamagedFile())
        assertFalse(java.sql.SQLException("x", null, 14).reportsDamagedFile()) // cannot open
        assertFalse(java.sql.SQLException("x", null, 10).reportsDamagedFile()) // I/O error
        assertFalse(java.sql.SQLException("x", null, 5).reportsDamagedFile()) // busy
        assertFalse(java.sql.SQLException("Error opening connection").reportsDamagedFile()) // native library
    }

    @Test
    fun `a really corrupted database is damaged and a database that cannot be opened is not`() {
        createBooksDatabase(books = 2000, padding = 200)
        val bytes = Files.readAllBytes(files.database)
        for (i in 4096 until 8192) bytes[i] = 0x5A
        Files.write(files.database, bytes)
        assertEquals(BookTableState.Unreadable, RealLibraryProbe.bookTable(files.database))

        assertEquals(BookTableState.Inconclusive, RealLibraryProbe.bookTable(root.resolve("missing/none.db")))
    }
}

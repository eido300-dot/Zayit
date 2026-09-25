package io.github.kdroidfilter.seforimapp.framework.database

import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.NIOFSDirectory
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
        assertEquals(listOf(LibraryProblem.DictionaryMissing), health.degraded)
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
    fun `library file names follow the database name`() {
        val other = libraryFilesFor(root.resolve("custom.sqlite"))

        assertEquals("seforim.db.lucene", files.textIndex.fileName.toString())
        assertEquals("seforim.db.lookup.lucene", files.lookupIndex.fileName.toString())
        assertEquals("custom.sqlite.luceneindex", other.textIndex.fileName.toString())
        assertEquals("custom.sqlite.lookupindex", other.lookupIndex.fileName.toString())
        assertEquals(root.resolve("lexical.db"), files.dictionary)
        assertEquals(root.resolve("catalog.pb"), files.catalog)
    }
}

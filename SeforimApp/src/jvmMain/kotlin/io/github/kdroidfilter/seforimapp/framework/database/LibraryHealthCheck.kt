package io.github.kdroidfilter.seforimapp.framework.database

import io.github.kdroidfilter.seforimapp.logger.warnln
import org.apache.lucene.index.SegmentInfos
import org.apache.lucene.store.NIOFSDirectory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/** How bad a [LibraryProblem] is for the user. */
enum class Severity {
    /** The library cannot be used; only a reinstall helps. */
    Reinstall,

    /** The books open, but a feature (search, suggestions, word forms) is missing. */
    Degraded,
}

/** Something wrong with the installed library files, found at startup. */
enum class LibraryProblem(
    val severity: Severity,
) {
    DatabaseMissing(Severity.Reinstall),
    DatabaseNotSqlite(Severity.Reinstall),
    DatabaseTruncated(Severity.Reinstall),
    DatabaseEmpty(Severity.Reinstall),
    DatabaseUnreadable(Severity.Reinstall),
    CatalogMissing(Severity.Reinstall),
    TextIndexMissing(Severity.Degraded),
    LookupIndexMissing(Severity.Degraded),
    DictionaryMissing(Severity.Degraded),
}

/** The result of [checkLibraryHealth]. */
data class LibraryHealth(
    val problems: List<LibraryProblem>,
) {
    val isHealthy: Boolean get() = problems.isEmpty()

    val needsReinstall: Boolean get() = problems.any { it.severity == Severity.Reinstall }

    val degraded: List<LibraryProblem> get() = problems.filter { it.severity == Severity.Degraded }
}

/** The fields of the 100-byte SQLite header the check needs. [pageCount] is null when the header does not vouch for it. */
data class SqliteHeader(
    val pageSize: Long,
    val pageCount: Long?,
)

/** What a read-only look at the `book` table found. */
enum class BookTableState { HasBooks, NoBooks, NoTable, Unreadable }

/** File system and database reads behind [checkLibraryHealth], replaceable in tests. None of them writes anything. */
interface LibraryProbe {
    /** Size of the regular file at [path], or null when there is none. */
    fun size(path: Path): Long?

    /** The header of [database], or null when it is not an SQLite file. */
    fun header(database: Path): SqliteHeader?

    fun bookTable(database: Path): BookTableState

    fun luceneIndexComplete(directory: Path): Boolean

    fun dictionaryValid(dictionary: Path): Boolean
}

/**
 * Checks that the library files are present and usable, without writing to them and in well under
 * a second. Opening the books database normally is not an option here: `Schema.create` and the WAL
 * pragma would write a fresh, empty database over a 0-byte file, and turn a damaged one into a
 * crash in the main window. There is no `integrity_check`, which takes minutes on 7.5 GB.
 *
 * Never throws. [dictionaryOverridden] is true when a dictionary is configured elsewhere
 * (`-DmagicDict`, `SEFORIM_MAGIC_DICT`), so a missing `lexical.db` is not reported.
 */
internal fun checkLibraryHealth(
    files: LibraryFiles,
    probe: LibraryProbe = RealLibraryProbe,
    dictionaryOverridden: Boolean = isDictionaryOverridden(),
): LibraryHealth {
    val problems = mutableListOf<LibraryProblem>()
    databaseProblem(files.database, probe)?.let(problems::add)
    if (probe.size(files.catalog) == null) problems += LibraryProblem.CatalogMissing
    if (!probe.luceneIndexComplete(files.textIndex)) problems += LibraryProblem.TextIndexMissing
    if (!probe.luceneIndexComplete(files.lookupIndex)) problems += LibraryProblem.LookupIndexMissing
    if (!dictionaryOverridden && !probe.dictionaryValid(files.dictionary)) problems += LibraryProblem.DictionaryMissing
    return LibraryHealth(problems)
}

private fun databaseProblem(
    database: Path,
    probe: LibraryProbe,
): LibraryProblem? {
    val size = probe.size(database) ?: return LibraryProblem.DatabaseMissing
    if (size == 0L) return LibraryProblem.DatabaseEmpty
    val header = probe.header(database) ?: return LibraryProblem.DatabaseNotSqlite
    val pageCount = header.pageCount
    if (pageCount != null && size < pageCount * header.pageSize) return LibraryProblem.DatabaseTruncated
    return when (probe.bookTable(database)) {
        BookTableState.HasBooks -> null
        BookTableState.NoBooks, BookTableState.NoTable -> LibraryProblem.DatabaseEmpty
        BookTableState.Unreadable -> LibraryProblem.DatabaseUnreadable
    }
}

private fun isDictionaryOverridden(): Boolean =
    listOf(System.getProperty("magicDict"), System.getenv("SEFORIM_MAGIC_DICT")).any { !it.isNullOrBlank() }

private const val SQLITE_HEADER_SIZE = 100
private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
private const val PAGE_SIZE_OFFSET = 16
private const val CHANGE_COUNTER_OFFSET = 24
private const val PAGE_COUNT_OFFSET = 28
private const val VERSION_VALID_FOR_OFFSET = 92
private const val MAX_PAGE_SIZE = 65_536L
private const val MIN_PAGE_SIZE = 512L
private val DICTIONARY_TABLES = setOf("surface", "variant", "base", "surface_variant")

/**
 * Parses the SQLite header ([https://sqlite.org/fileformat.html#the_database_header]). The page
 * count at offset 28 is only trusted when the change counter (24) equals "version valid for" (92).
 */
internal fun parseSqliteHeader(bytes: ByteArray): SqliteHeader? {
    if (bytes.size < SQLITE_HEADER_SIZE || !bytes.copyOf(SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return null
    val buffer = ByteBuffer.wrap(bytes)
    val rawPageSize = buffer.getShort(PAGE_SIZE_OFFSET).toInt() and 0xFFFF
    val pageSize = if (rawPageSize == 1) MAX_PAGE_SIZE else rawPageSize.toLong()
    val isPowerOfTwo = pageSize and (pageSize - 1) == 0L
    if (pageSize !in MIN_PAGE_SIZE..MAX_PAGE_SIZE || !isPowerOfTwo) return null
    val pageCount = buffer.getInt(PAGE_COUNT_OFFSET).toLong() and 0xFFFF_FFFFL
    val counterValid = buffer.getInt(CHANGE_COUNTER_OFFSET) == buffer.getInt(VERSION_VALID_FOR_OFFSET)
    return SqliteHeader(pageSize, pageCount.takeIf { counterValid && it > 0 })
}

/**
 * Read-only SQLite URL. `immutable=1` keeps SQLite from creating `-wal`/`-shm` files or touching
 * the file at all, which `mode=ro` alone does not guarantee.
 */
private fun readOnlyUrl(database: Path): String = "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro&immutable=1"

/** Runs one read-only query on [database] and hands its rows to [read]. */
@Throws(SQLException::class)
private fun <T> queryReadOnly(
    database: Path,
    query: String,
    read: (ResultSet) -> T,
): T =
    DriverManager.getConnection(readOnlyUrl(database)).use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(query).use(read) }
    }

/** The real probe. Every read is wrapped so the check as a whole never throws. */
object RealLibraryProbe : LibraryProbe {
    override fun size(path: Path): Long? =
        try {
            if (Files.isRegularFile(path)) Files.size(path) else null
        } catch (e: IOException) {
            warnln(e) { "[LibraryHealth] cannot read size of ${path.fileName}" }
            null
        }

    override fun header(database: Path): SqliteHeader? =
        try {
            Files.newByteChannel(database, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(SQLITE_HEADER_SIZE)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                parseSqliteHeader(buffer.array().copyOf(buffer.position()))
            }
        } catch (e: IOException) {
            warnln(e) { "[LibraryHealth] cannot read the database header" }
            null
        }

    override fun bookTable(database: Path): BookTableState =
        try {
            queryReadOnly(database, "SELECT EXISTS(SELECT 1 FROM book)") { rows ->
                if (rows.next() && rows.getInt(1) == 1) BookTableState.HasBooks else BookTableState.NoBooks
            }
        } catch (e: SQLException) {
            if (e.message.orEmpty().contains("no such table", ignoreCase = true)) {
                BookTableState.NoTable
            } else {
                warnln(e) { "[LibraryHealth] the books database cannot be read" }
                BookTableState.Unreadable
            }
        }

    override fun luceneIndexComplete(directory: Path): Boolean {
        // Checked first: opening a Lucene directory on a missing path creates it.
        if (!Files.isDirectory(directory)) return false
        return try {
            NIOFSDirectory(directory).use { lucene ->
                val present = lucene.listAll().toSet()
                SegmentInfos.readLatestCommit(lucene).files(true).all { it in present }
            }
        } catch (e: IOException) {
            warnln(e) { "[LibraryHealth] index ${directory.fileName} is incomplete" }
            false
        }
    }

    override fun dictionaryValid(dictionary: Path): Boolean {
        if (!Files.isRegularFile(dictionary)) return false
        return try {
            val names =
                queryReadOnly(dictionary, "SELECT name FROM sqlite_master WHERE type = 'table'") { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1).orEmpty()) }
                }
            names.containsAll(DICTIONARY_TABLES)
        } catch (e: SQLException) {
            warnln(e) { "[LibraryHealth] dictionary cannot be read" }
            false
        }
    }
}

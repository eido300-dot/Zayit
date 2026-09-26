package io.github.kdroidfilter.seforimapp.framework.database

import io.github.kdroidfilter.seforimapp.logger.warnln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.store.NIOFSDirectory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A library file whose content did not survive on the drive. */
enum class DamagedPart { Database, TextIndex, LookupIndex }

/** Full reads of the library files, replaceable in tests. Each returns true when the content is intact. */
interface LibraryIntegrityProbe {
    /** `PRAGMA quick_check`; stops early when [onStart] hands over a cancel action and it is invoked. */
    fun databaseIntact(
        database: Path,
        onStart: (cancel: () -> Unit) -> Unit,
    ): Boolean

    /** Reads every file of the index and checks its Lucene checksum. */
    fun indexIntact(
        directory: Path,
        checkCancelled: () -> Unit,
    ): Boolean
}

/**
 * Reads the whole library back once after an install, to catch a drive that lost data.
 *
 * A counterfeit USB stick reports more space than it has: writes past the real size "succeed"
 * and are silently dropped or wrap onto earlier data, and a read right after the write is served
 * from the operating system's cache, so the install looks complete. Reading everything back on a
 * later launch, when that cache is empty, goes to the drive itself: SQLite's page checks and the
 * checksums in every Lucene file then find what was lost. This reads 10 GB and takes minutes on a
 * slow drive, so it runs in the background and only once per installed library.
 *
 * Missing parts are the startup health check's job; only present parts are verified here.
 */
class LibraryVerifier(
    private val probe: LibraryIntegrityProbe = RealLibraryIntegrityProbe,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The damaged parts, empty when everything present reads back intact. Cancellable. */
    suspend fun verify(files: LibraryFiles): List<DamagedPart> {
        val damaged = mutableListOf<DamagedPart>()
        if (Files.isRegularFile(files.database) && !databaseIntact(files.database)) damaged += DamagedPart.Database
        if (Files.isDirectory(files.textIndex) && !indexIntact(files.textIndex)) damaged += DamagedPart.TextIndex
        if (Files.isDirectory(files.lookupIndex) && !indexIntact(files.lookupIndex)) damaged += DamagedPart.LookupIndex
        return damaged
    }

    /**
     * Runs the blocking query in its own coroutine and waits here, so a cancellation reaches this
     * coroutine at once and can stop the query; otherwise it would only be seen after minutes of reading.
     */
    private suspend fun databaseIntact(database: Path): Boolean =
        coroutineScope {
            val cancelQuery = AtomicReference<(() -> Unit)?>(null)
            val cancelled = AtomicBoolean(false)
            val query =
                async(ioDispatcher) {
                    probe.databaseIntact(database) { cancel ->
                        cancelQuery.set(cancel)
                        // A cancellation that came before the query registered its stop action.
                        if (cancelled.get()) cancel()
                    }
                }
            try {
                query.await()
            } catch (e: CancellationException) {
                cancelled.set(true)
                cancelQuery.get()?.invoke()
                throw e
            }
        }

    private suspend fun indexIntact(directory: Path): Boolean =
        withContext(ioDispatcher) {
            val job = currentCoroutineContext().job
            probe.indexIntact(directory) { job.ensureActive() }
        }
}

/**
 * Identifies an installed library, so a new install of the same version is verified again:
 * the version from `release_info.txt` and the database's size and modification time.
 */
internal fun libraryFingerprint(
    version: String?,
    databaseSize: Long?,
    databaseModified: Long?,
): String = listOf(version.orEmpty(), (databaseSize ?: -1L).toString(), (databaseModified ?: -1L).toString()).joinToString("|")

/**
 * Whether to verify now. Only for a portable copy (the fake-drive problem is a removable-drive
 * problem), only when the fingerprint differs from the last verified one, and never in the
 * session that installed the library, whose reads would still come from the cache.
 */
internal fun shouldVerifyLibrary(
    isPortable: Boolean,
    installedThisSession: Boolean,
    fingerprint: String,
    lastVerified: String?,
): Boolean = isPortable && !installedThisSession && fingerprint != lastVerified

/** The real probe. Reads only: `immutable=1` keeps SQLite from touching the files. */
object RealLibraryIntegrityProbe : LibraryIntegrityProbe {
    override fun databaseIntact(
        database: Path,
        onStart: (cancel: () -> Unit) -> Unit,
    ): Boolean =
        try {
            val url = "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro&immutable=1"
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    onStart {
                        try {
                            statement.cancel()
                        } catch (_: SQLException) {
                            // Already finished or closed: nothing left to stop.
                        }
                    }
                    statement.executeQuery("PRAGMA quick_check").use { rows ->
                        rows.next() && rows.getString(1) == "ok"
                    }
                }
            }
        } catch (e: SQLException) {
            warnln(e) { "[LibraryVerify] quick_check failed" }
            false
        }

    override fun indexIntact(
        directory: Path,
        checkCancelled: () -> Unit,
    ): Boolean =
        try {
            NIOFSDirectory(directory).use { lucene ->
                DirectoryReader.open(lucene).use { reader ->
                    reader.leaves().forEach { leaf ->
                        checkCancelled()
                        leaf.reader().checkIntegrity()
                    }
                }
            }
            true
        } catch (e: IOException) {
            warnln(e) { "[LibraryVerify] index ${directory.fileName} failed its checksums" }
            false
        }
}

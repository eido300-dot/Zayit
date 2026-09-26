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
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.store.NIOFSDirectory
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A library file whose content did not survive on the drive. */
enum class DamagedPart { Database, TextIndex, LookupIndex }

/**
 * The verification could not run to the end (the drive stopped answering, the SQLite library did
 * not load), which says nothing about the files: it must never be reported as damage, and nothing
 * may be remembered as verified.
 */
class VerificationInconclusiveException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

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

    /**
     * Whether [file] on the drive still holds what the computer's cache holds for it; null when
     * this cannot be told here. See [readsBackFromDrive].
     */
    fun matchesDrive(
        file: Path,
        checkCancelled: () -> Unit,
    ): Boolean? = null
}

/**
 * Reads the whole library back once after an install, to catch a drive that lost data.
 *
 * A counterfeit USB stick reports more space than it has: writes past the real size "succeed"
 * and are silently dropped or wrap onto earlier data, and a read right after the write is served
 * from the operating system's cache, so the install looks complete. Reading everything back on a
 * later launch, when that cache is empty, goes to the drive itself: SQLite's page checks and the
 * checksums in every Lucene file then find what was lost. The cache can outlive the app, though
 * (the computer was not restarted and the drive not unplugged), so every file is first read around
 * the cache and compared with what the cache holds. This reads the library up to twice and takes
 * minutes on a slow drive, so it runs in the background and only once per installed library.
 *
 * Missing parts are the startup health check's job; only present parts are verified here.
 */
class LibraryVerifier(
    private val probe: LibraryIntegrityProbe = RealLibraryIntegrityProbe,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The damaged parts, empty when everything present reads back intact. Cancellable. */
    suspend fun verify(files: LibraryFiles): List<DamagedPart> {
        // File system calls can take seconds on a slow drive: never on the caller's thread.
        val present = withContext(ioDispatcher) { PresentParts.of(files) }
        val damaged = mutableListOf<DamagedPart>()
        if (present.database && (!matchesDrive(listOf(files.database)) || !databaseIntact(files.database))) damaged += DamagedPart.Database
        if (present.textIndex && !indexIntact(files.textIndex)) damaged += DamagedPart.TextIndex
        if (present.lookupIndex && !indexIntact(files.lookupIndex)) damaged += DamagedPart.LookupIndex
        if (damaged.isNotEmpty()) ensureDriveStillAnswers(files, present)
        return damaged
    }

    /** Which parts were there when the check began; a part that has vanished since is a drive that is gone. */
    private data class PresentParts(
        val database: Boolean,
        val textIndex: Boolean,
        val lookupIndex: Boolean,
    ) {
        companion object {
            fun of(files: LibraryFiles) =
                PresentParts(
                    database = Files.isRegularFile(files.database),
                    textIndex = Files.isDirectory(files.textIndex),
                    lookupIndex = Files.isDirectory(files.lookupIndex),
                )
        }
    }

    /**
     * A drive unplugged during the check makes every read fail, which looks like damage. Damage is
     * only believed while the parts that were checked can still be found.
     */
    private suspend fun ensureDriveStillAnswers(
        files: LibraryFiles,
        before: PresentParts,
    ) {
        val now = withContext(ioDispatcher) { PresentParts.of(files) }
        if (before != now) throw VerificationInconclusiveException("the library files disappeared during the check")
    }

    /** False when any of [files] differs on the drive from the cache; true when equal or when this cannot be told. */
    private suspend fun matchesDrive(files: List<Path>): Boolean =
        withContext(ioDispatcher) {
            val job = currentCoroutineContext().job
            files.all { probe.matchesDrive(it) { job.ensureActive() } != false }
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
        matchesDrive(regularFilesIn(directory)) &&
            withContext(ioDispatcher) {
                val job = currentCoroutineContext().job
                probe.indexIntact(directory) { job.ensureActive() }
            }

    private suspend fun regularFilesIn(directory: Path): List<Path> =
        withContext(ioDispatcher) {
            try {
                Files.list(directory).use { entries -> entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList() }
            } catch (e: IOException) {
                warnln(e) { "[LibraryVerify] cannot list ${directory.fileName}" }
                emptyList()
            }
        }
}

/**
 * Identifies an installed library: the version from `release_info.txt` and the database's size. Not
 * its modification time, which changes whenever the app writes to the database. A reinstall of the
 * same version clears the stored fingerprint instead (see `verifyLibraryIfNeeded`).
 */
internal fun libraryFingerprint(
    version: String?,
    databaseSize: Long?,
): String = listOf(version.orEmpty(), (databaseSize ?: -1L).toString()).joinToString("|")

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

private const val SQLITE_INTERRUPT = 9

/** The real probe. Reads only: `immutable=1` keeps SQLite from touching the files. */
object RealLibraryIntegrityProbe : LibraryIntegrityProbe {
    override fun databaseIntact(
        database: Path,
        onStart: (cancel: () -> Unit) -> Unit,
    ): Boolean =
        try {
            DriverManager.getConnection(readOnlyUrl(database)).use { connection ->
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
            when {
                // The check was stopped on purpose (cancellation); the caller ignores the answer.
                (e.errorCode and 0xFF) == SQLITE_INTERRUPT -> false
                e.reportsDamagedFile() -> {
                    warnln(e) { "[LibraryVerify] quick_check found the database damaged" }
                    false
                }
                else -> throw VerificationInconclusiveException("quick_check could not run", e)
            }
        }

    override fun matchesDrive(
        file: Path,
        checkCancelled: () -> Unit,
    ): Boolean? = readsBackFromDrive(file, checkCancelled)

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
        } catch (e: CorruptIndexException) {
            warnln(e) { "[LibraryVerify] index ${directory.fileName} failed its checksums" }
            false
        } catch (e: EOFException) {
            warnln(e) { "[LibraryVerify] index ${directory.fileName} is cut short" }
            false
        } catch (e: IOException) {
            // Any other read failure is the device, not proof that the index lost data.
            throw VerificationInconclusiveException("index ${directory.fileName} could not be read", e)
        }
}

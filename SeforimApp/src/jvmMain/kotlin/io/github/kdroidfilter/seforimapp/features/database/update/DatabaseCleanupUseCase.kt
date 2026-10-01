package io.github.kdroidfilter.seforimapp.features.database.update

import io.github.kdroidfilter.seforimapp.core.settings.AppSettings
import io.github.kdroidfilter.seforimapp.framework.database.DatabasePathProvider
import io.github.kdroidfilter.seforimapp.framework.database.PendingDbCleanup
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.framework.portable.deleteTree
import io.github.kdroidfilter.seforimapp.framework.portable.treeSize
import io.github.kdroidfilter.seforimapp.logger.debugln
import io.github.kdroidfilter.seforimapp.logger.warnln
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Removes a previously installed database and all of its companion artifacts
 * (Lucene indexes, catalog, lexical dictionary, version stamp, download leftovers)
 * before a fresh install.
 *
 * Deletion is authoritative: it targets the directory of the *actual* configured
 * database ([appSettings.getDatabasePath]) as well as the default databases
 * directory, so a database installed by an older build in a non-default location is
 * still removed. NIO [Files.deleteIfExists] is used so a locked file — common on
 * Windows when an antivirus, the Windows Search indexer, or a leftover handle holds
 * it — surfaces an exception we can log instead of the silent `false` returned by
 * [File.delete]. Files that cannot be removed are reported via [CleanupResult.Incomplete]
 * and recorded for a retry at the next launch via [PendingDbCleanup], so the caller
 * can refuse to start a multi-GB download that would otherwise fill the disk.
 */
class DatabaseCleanupUseCase(
    private val databasePathProvider: DatabasePathProvider,
    private val appSettings: AppSettings,
) {
    sealed interface CleanupResult {
        /** Every known artifact was removed (or was already absent). */
        data class Success(
            val freedBytes: Long,
        ) : CleanupResult

        /** Some files could not be deleted (e.g. locked by another process). */
        data class Incomplete(
            val undeletable: List<File>,
        ) : CleanupResult
    }

    /**
     * @param keep files that must survive even if they look like install leftovers — e.g. the
     *   `.tar.zst.part01/02` the user just picked for an offline update, which may live in the
     *   databases directory and would otherwise be deleted before extraction reads them.
     */
    suspend fun cleanupDatabaseFiles(keep: Collection<File> = emptyList()): CleanupResult =
        withContext(Dispatchers.IO) {
            val currentDbPath = appSettings.getDatabasePath()

            // The old database is going away: forget the recorded path and the cached
            // resolution so the app re-resolves the freshly installed location later.
            appSettings.setDatabasePath(null)
            databasePathProvider.reset()

            // Candidate directories: the real DB directory (may be non-default for
            // legacy installs) plus the current default databases directory.
            val dirs = LinkedHashSet<File>()
            currentDbPath?.let { File(it).parentFile?.let(dirs::add) }
            runCatching { File(FileKit.databasesDir.path) }.getOrNull()?.let(dirs::add)
            dirs.removeAll { dir ->
                !PortableEnvironment.mayCleanUp(dir.toPath()).also { allowed ->
                    if (!allowed) warnln { "[DatabaseCleanup] ${dir.name} leads outside the drive's data folder; skipped" }
                }
            }

            val result = removeArtifacts(dirs, keep)
            when (result) {
                is CleanupResult.Success ->
                    debugln { "[DatabaseCleanup] Removed previous database artifacts, freed ${result.freedBytes / (1024 * 1024)} MB" }
                is CleanupResult.Incomplete -> {
                    warnln {
                        "[DatabaseCleanup] ${result.undeletable.size} file(s) could not be deleted (locked?): " +
                            result.undeletable.joinToString { it.name }
                    }
                    PendingDbCleanup.record(result.undeletable)
                }
            }
            result
        }

    /** Deletes the database artifacts directly inside [dirs], except the files in [keep]. */
    internal fun removeArtifacts(
        dirs: Collection<File>,
        keep: Collection<File>,
    ): CleanupResult {
        val keepPaths = keep.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        var freed = 0L
        val undeletable = mutableListOf<File>()

        try {
            for (dir in dirs) {
                val files = dir.takeIf { it.exists() }?.listFiles() ?: continue
                for (file in files) {
                    if (!isDatabaseArtifact(file)) continue
                    if (runCatching { file.canonicalPath }.getOrNull() in keepPaths) continue
                    val size = sizeOf(file)
                    if (deleteTree(file.toPath(), ::logFailure)) {
                        freed += size
                    } else {
                        undeletable += file
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnln { "[DatabaseCleanup] Unexpected error during cleanup: ${e.message}" }
        }

        return if (undeletable.isEmpty()) CleanupResult.Success(freed) else CleanupResult.Incomplete(undeletable)
    }

    /** True for files this app installs alongside the database and must remove on reinstall. */
    private fun isDatabaseArtifact(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".db") ||
            // seforim.db, lexical.db
            name.endsWith(".db-wal") ||
            name.endsWith(".db-shm") ||
            // SQLite WAL/SHM sidecars
            name.endsWith(".lucene") ||
            name.contains(".lookup.lucene") ||
            // Lucene index dirs
            name == "catalog.pb" ||
            // precomputed catalog (previously mis-targeted as ".proto")
            name == "release_info.txt" ||
            // version stamp
            name == "delta-cache" ||
            // delta updater work dir
            name == PendingDbCleanup.MARKER_NAME ||
            // stale pending-cleanup marker
            // download / extraction leftovers
            name.endsWith(".tar.zst") ||
            name.endsWith(".part01") ||
            name.endsWith(".part02") ||
            name.endsWith(".zst") ||
            name.endsWith(".tmp")
    }

    /** Links are not entered: on a drive someone else prepared, one to `/` would make this walk the computer. */
    private fun sizeOf(file: File): Long = runCatching { treeSize(file.toPath()) }.getOrDefault(0L)

    /** Logs an entry [deleteTree] could not remove; NIO surfaces the reason that [File.delete] would hide. */
    private fun logFailure(
        path: Path,
        e: IOException,
    ) {
        warnln { "[DatabaseCleanup] Could not delete $path: ${e.javaClass.simpleName} ${e.message}" }
    }
}

package io.github.kdroidfilter.seforimapp.framework.database

import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.logger.infoln
import io.github.kdroidfilter.seforimapp.logger.warnln
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.path
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Persists a failed database cleanup across restarts.
 *
 * When [io.github.kdroidfilter.seforimapp.features.database.update.DatabaseCleanupUseCase]
 * cannot delete an old artifact — typically a file still locked by an antivirus, the
 * Windows Search indexer, or a leftover handle — it records the absolute paths here.
 * [runOnce] is invoked at startup, BEFORE the repository opens the database, to retry
 * the deletion once the transient lock is gone. This is what lets the app recover on
 * its own instead of asking the user to delete the old database by hand.
 *
 * In portable mode the paths are stored relative to the databases folder and only files inside it
 * are ever deleted: the drive may come back under another letter, which on the next computer can
 * belong to another drive.
 */
object PendingDbCleanup {
    const val MARKER_NAME = "pending-db-cleanup.txt"

    private fun databasesDir(): Path? = runCatching { File(FileKit.databasesDir.path).toPath() }.getOrNull()

    /** Records [files] (merged with any existing entries, de-duplicated) for a retry next launch. */
    fun record(files: List<File>) {
        if (files.isEmpty()) return
        val root = databasesDir() ?: return
        val marker = root.resolve(MARKER_NAME).toFile()
        val isPortable = PortableEnvironment.isPortable
        val existing =
            if (marker.exists()) runCatching { marker.readLines() }.getOrDefault(emptyList()) else emptyList()
        val all =
            (existing + files.mapNotNull { markerEntryFor(it.toPath(), root, isPortable) })
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSortedSet()
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(all.joinToString("\n"))
        }.onFailure { warnln { "[PendingDbCleanup] Could not write marker: ${it.message}" } }
    }

    /**
     * Retries any pending deletions. Cheap stat when no marker is present; never throws.
     * Clears the marker only once every recorded path is gone.
     */
    fun runOnce() {
        val root = databasesDir() ?: return
        val marker = root.resolve(MARKER_NAME).toFile()
        if (!marker.exists()) return
        val isPortable = PortableEnvironment.isPortable

        val paths =
            runCatching { marker.readLines() }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (paths.isEmpty()) {
            runCatching { Files.deleteIfExists(marker.toPath()) }
            return
        }

        val remaining =
            paths.filterNot { entry ->
                val target = resolveMarkerEntry(entry, root, isPortable)
                if (target == null) warnln { "[PendingDbCleanup] Skipping entry outside the databases folder: $entry" }
                target == null || deleteRecursively(target.toFile())
            }
        if (remaining.isEmpty()) {
            runCatching { Files.deleteIfExists(marker.toPath()) }
            infoln { "[PendingDbCleanup] All pending database deletions completed." }
        } else {
            runCatching { marker.writeText(remaining.joinToString("\n")) }
            warnln { "[PendingDbCleanup] ${remaining.size} file(s) still locked; will retry next launch." }
        }
    }

    /** Deletes a file or directory tree via NIO. Returns true if nothing remains afterwards. */
    private fun deleteRecursively(target: File): Boolean {
        if (!target.exists()) return true
        if (target.isDirectory) target.listFiles()?.forEach { deleteRecursively(it) }
        return runCatching {
            Files.deleteIfExists(target.toPath())
            !target.exists()
        }.getOrDefault(!target.exists())
    }
}

/**
 * How [file] is recorded in the marker. In portable mode: relative to [databasesDir], or `null`
 * (not recorded) when it lies outside. Otherwise the absolute path, as before.
 */
internal fun markerEntryFor(
    file: Path,
    databasesDir: Path,
    isPortable: Boolean,
): String? {
    if (!isPortable) return file.toAbsolutePath().toString()
    val root = databasesDir.toAbsolutePath().normalize()
    val target = file.toAbsolutePath().normalize()
    return if (target.startsWith(root) && target != root) root.relativize(target).joinToString("/") else null
}

/**
 * The file to delete for a marker [entry], or `null` when it must be skipped. In portable mode only
 * relative entries that stay inside [databasesDir] are accepted.
 */
internal fun resolveMarkerEntry(
    entry: String,
    databasesDir: Path,
    isPortable: Boolean,
): Path? =
    try {
        val path = Path.of(entry)
        if (!isPortable) {
            path
        } else {
            val root = databasesDir.toAbsolutePath().normalize()
            val target = root.resolve(path).normalize()
            target.takeIf { !path.isAbsolute && it.startsWith(root) && it != root }
        }
    } catch (_: InvalidPathException) {
        null
    }

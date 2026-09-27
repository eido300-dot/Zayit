package io.github.kdroidfilter.seforimapp.features.database.health

import io.github.kdroidfilter.platformtools.appmanager.restartApplication
import io.github.kdroidfilter.seforimapp.core.settings.AppSettings
import io.github.kdroidfilter.seforimapp.framework.database.LibraryProblem
import io.github.kdroidfilter.seforimapp.framework.database.encodeProblems
import io.github.kdroidfilter.seforimapp.framework.database.getDatabasePath
import io.github.kdroidfilter.seforimapp.framework.database.isRepeatedAfterReinstall
import io.github.kdroidfilter.seforimapp.framework.database.reinstallMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

/**
 * Asks for the library to be reinstalled at the next launch and restarts. The running app keeps the
 * database and indexes open, so they cannot be replaced from inside this session: on Windows the
 * files are locked, elsewhere the app would go on reading the deleted ones. The request is also
 * recorded as the last reinstall, so the same problems coming back afterwards are recognized.
 */
suspend fun requestLibraryReinstall(problems: List<LibraryProblem>) {
    withContext(Dispatchers.IO) {
        AppSettings.setReinstallRequest(encodeProblems(problems))
        AppSettings.setLastReinstallMarker(reinstallMarker(problems, installedDatabase()?.let(::modifiedTime)))
        try {
            // The new process starts before this one exits and must read the request.
            Preferences.userRoot().flush()
        } catch (_: BackingStoreException) {
            // The shutdown hook still saves it; at worst the banner shows once more.
        }
        restartApplication()
    }
}

/** False when a reinstall already ran for these same [problems] and did not fix them. */
suspend fun reinstallStillHelps(problems: List<LibraryProblem>): Boolean =
    withContext(Dispatchers.IO) {
        !isRepeatedAfterReinstall(AppSettings.getLastReinstallMarker(), problems, installedDatabase()?.let(::modifiedTime))
    }

/** The installed database, or null when there is none; a missing database is the startup check's job. */
private fun installedDatabase(): Path? =
    try {
        Path.of(getDatabasePath())
    } catch (_: IllegalStateException) {
        null
    } catch (_: InvalidPathException) {
        null
    }

private fun modifiedTime(database: Path): Long? =
    try {
        Files.getLastModifiedTime(database).toMillis()
    } catch (_: IOException) {
        null
    }

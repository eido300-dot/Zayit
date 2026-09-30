package io.github.kdroidfilter.seforimapp.features.database.health

import io.github.kdroidfilter.seforimapp.core.coroutines.runSuspendCatching
import io.github.kdroidfilter.seforimapp.core.settings.AppSettings
import io.github.kdroidfilter.seforimapp.framework.database.DamagedPart
import io.github.kdroidfilter.seforimapp.framework.database.DatabaseVersionManager
import io.github.kdroidfilter.seforimapp.framework.database.LibraryProblem
import io.github.kdroidfilter.seforimapp.framework.database.LibraryVerifier
import io.github.kdroidfilter.seforimapp.framework.database.VerificationInconclusiveException
import io.github.kdroidfilter.seforimapp.framework.database.canForgetReinstall
import io.github.kdroidfilter.seforimapp.framework.database.checkOptionalParts
import io.github.kdroidfilter.seforimapp.framework.database.encodeProblems
import io.github.kdroidfilter.seforimapp.framework.database.getDatabasePath
import io.github.kdroidfilter.seforimapp.framework.database.isRepeatedAfterReinstall
import io.github.kdroidfilter.seforimapp.framework.database.libraryFilesFor
import io.github.kdroidfilter.seforimapp.framework.database.libraryFingerprint
import io.github.kdroidfilter.seforimapp.framework.database.reinstallMarker
import io.github.kdroidfilter.seforimapp.framework.database.shouldVerifyLibrary
import io.github.kdroidfilter.seforimapp.framework.platform.restartApp
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.logger.errorln
import io.github.kdroidfilter.seforimapp.logger.infoln
import io.github.kdroidfilter.seforimapp.logger.warnln
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** What the check after startup found. */
data class LibraryAfterStart(
    val damaged: List<DamagedPart>,
    /** False when a reinstall already ran for these same problems and did not fix them. */
    val reinstallHelps: Boolean,
)

/**
 * Checks the search indexes and the dictionary once the main window is up. They only degrade the
 * app, so the first window does not wait for them: the check before it covers only the database and
 * the catalog. Never throws: it runs in a launched effect, so a failure is logged and the result is
 * null, which proves nothing about the library. Runs its file work on the IO dispatcher.
 */
internal suspend fun checkOptionalLibraryParts(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): List<LibraryProblem>? =
    runSuspendCatching {
        withContext(ioDispatcher) {
            val database = installedDatabase() ?: return@withContext emptyList<LibraryProblem>()
            checkOptionalParts(libraryFilesFor(database))
        }
    }.onFailure { e -> errorln(e) { "[LibraryHealth] the indexes and the dictionary could not be checked; carrying on without them" } }
        .getOrNull()
        ?.also { problems -> if (problems.isNotEmpty()) warnln { "[LibraryHealth] missing optional parts: $problems" } }

/**
 * Whether a reinstall is still worth offering for [problems]: false when one already ran for these
 * same problems and did not fix them. Never throws; when it cannot tell, the reinstall stays offered.
 * Runs its file work on the IO dispatcher.
 */
internal suspend fun reinstallHelpsFor(
    problems: List<LibraryProblem>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean =
    runSuspendCatching {
        withContext(ioDispatcher) {
            val database = installedDatabase() ?: return@withContext true
            !isRepeatedAfterReinstall(AppSettings.getLastReinstallMarker(), problems, modifiedTime(database))
        }
    }.getOrDefault(true)

/**
 * Reads the installed library back once, when needed (see [shouldVerifyLibrary]), and decides
 * whether a reinstall is still worth offering for [degraded] and the damage found. An intact library
 * is remembered and not read again; a damaged one is checked again on every launch until it is
 * reinstalled. Once nothing is wrong, the last reinstall is forgotten (see [canForgetReinstall]).
 * Runs its file work on the IO dispatcher.
 *
 * @param installedThisSession true when this session showed onboarding or the reinstall window.
 * @param libraryChecked false when the startup check or [checkOptionalLibraryParts] failed, so
 *   nothing is known to be fine and the last reinstall is not forgotten.
 */
suspend fun checkLibraryAfterStart(
    installedThisSession: Boolean,
    degraded: List<LibraryProblem>,
    libraryChecked: Boolean,
    verifier: LibraryVerifier = LibraryVerifier(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    startDelayMillis: Long = VERIFY_START_DELAY_MILLIS,
): LibraryAfterStart =
    runSuspendCatching { checkLibrary(installedThisSession, degraded, libraryChecked, verifier, ioDispatcher, startDelayMillis) }
        .onFailure { e ->
            // Runs in a launched effect, where an exception would repeat on every launch and take the
            // app down. Nothing was remembered as verified, so the next launch tries again.
            if (e is VerificationInconclusiveException) {
                warnln(e) { "[LibraryVerify] could not be completed; will try again next launch" }
            } else {
                errorln(e) { "[LibraryVerify] the check failed; carrying on without it" }
            }
        }.getOrElse { LibraryAfterStart(emptyList(), true) }

/** Verification starts this long after launch, so it does not compete with the first searches for the drive. */
private const val VERIFY_START_DELAY_MILLIS = 30_000L

private suspend fun checkLibrary(
    installedThisSession: Boolean,
    degraded: List<LibraryProblem>,
    libraryChecked: Boolean,
    verifier: LibraryVerifier,
    ioDispatcher: CoroutineDispatcher,
    startDelayMillis: Long,
): LibraryAfterStart {
    val database = withContext(ioDispatcher) { installedDatabase() } ?: return LibraryAfterStart(emptyList(), true)
    val outcome = verifyIfNeeded(database, installedThisSession, verifier, ioDispatcher, startDelayMillis)
    return withContext(ioDispatcher) {
        val damaged = (outcome as? Verification.Damaged)?.parts.orEmpty()
        val problems = (degraded + damaged.map(DamagedPart::asProblem)).distinct()
        val marker = AppSettings.getLastReinstallMarker()
        val modified = modifiedTime(database)
        val forget =
            canForgetReinstall(
                problems = problems,
                libraryChecked = libraryChecked,
                installedThisSession = installedThisSession,
                isPortable = PortableEnvironment.isPortable,
                readBackIntact = outcome == Verification.Intact,
            )
        if (marker != null && forget) {
            // Everything is fine now: forget the last reinstall.
            AppSettings.setLastReinstallMarker(null)
        }
        LibraryAfterStart(damaged, reinstallHelps = !isRepeatedAfterReinstall(marker, problems, modified))
    }
}

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
        restartApp()
        // Reached only when no new process was started: forget the request, or the next launch
        // would reinstall the library unasked.
        AppSettings.setReinstallRequest(null)
    }
}

/** How a damaged part is named among the startup problems, for the reinstall list and the loop check. */
fun DamagedPart.asProblem(): LibraryProblem =
    when (this) {
        DamagedPart.Database -> LibraryProblem.DatabaseUnreadable
        DamagedPart.TextIndex -> LibraryProblem.TextIndexMissing
        DamagedPart.LookupIndex -> LibraryProblem.LookupIndexMissing
    }

private sealed interface Verification {
    /** Not needed now: a normal installation, or the reads would come from the cache. */
    data object Skipped : Verification

    /** Read back now or before, and intact. */
    data object Intact : Verification

    data class Damaged(
        val parts: List<DamagedPart>,
    ) : Verification
}

private suspend fun verifyIfNeeded(
    database: Path,
    installedThisSession: Boolean,
    verifier: LibraryVerifier,
    ioDispatcher: CoroutineDispatcher,
    startDelayMillis: Long,
): Verification {
    if (!PortableEnvironment.isPortable) return Verification.Skipped
    if (installedThisSession) {
        // A reinstall of the same version has the same fingerprint: forget the old result.
        withContext(ioDispatcher) { if (AppSettings.getVerifiedLibrary() != null) AppSettings.setVerifiedLibrary(null) }
        return Verification.Skipped
    }
    val fingerprint =
        withContext(ioDispatcher) {
            try {
                libraryFingerprint(DatabaseVersionManager.getCurrentDatabaseVersion(), Files.size(database))
            } catch (_: IOException) {
                null
            }
        } ?: return Verification.Skipped
    val lastVerified = withContext(ioDispatcher) { AppSettings.getVerifiedLibrary() }
    if (!shouldVerifyLibrary(isPortable = true, installedThisSession = false, fingerprint, lastVerified)) return Verification.Intact
    delay(startDelayMillis)
    infoln { "[LibraryVerify] reading the library back to check the drive" }
    val damaged = verifier.verify(libraryFilesFor(database))
    return withContext(ioDispatcher) {
        if (damaged.isEmpty()) {
            AppSettings.setVerifiedLibrary(fingerprint)
            Verification.Intact
        } else {
            errorln { "[LibraryVerify] library damaged on the drive: $damaged" }
            Verification.Damaged(damaged)
        }
    }
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

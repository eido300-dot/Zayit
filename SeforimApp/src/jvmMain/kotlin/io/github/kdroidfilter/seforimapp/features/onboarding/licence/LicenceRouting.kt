package io.github.kdroidfilter.seforimapp.features.onboarding.licence

import io.github.kdroidfilter.seforimapp.core.coroutines.runSuspendCatching
import io.github.kdroidfilter.seforimapp.features.onboarding.navigation.OnBoardingDestination
import io.github.kdroidfilter.seforimapp.framework.database.DatabaseVersionManager
import io.github.kdroidfilter.seforimapp.framework.database.checkLibraryHealth
import io.github.kdroidfilter.seforimapp.framework.database.expectedDatabasePath
import io.github.kdroidfilter.seforimapp.framework.database.libraryFilesFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The step after the licence. A copy running from a drive goes on as before; an installed Zayit
 * first asks whether to install on this computer or to create a portable copy on a drive.
 */
internal fun nextAfterLicence(
    isPortable: Boolean,
    isDatabaseReady: Boolean,
): OnBoardingDestination = if (isPortable) nextAfterLocalInstallChoice(isDatabaseReady) else OnBoardingDestination.InstallLocationScreen

/** Installing on this computer: an installed, compatible library skips the install flow. */
internal fun nextAfterLocalInstallChoice(isDatabaseReady: Boolean): OnBoardingDestination =
    if (isDatabaseReady) OnBoardingDestination.UserProfilScreen else OnBoardingDestination.AvailableDiskSpaceScreen

/**
 * Whether the installed library can be used as it is. It reads the library files, which can be slow
 * on a drive, so not on the UI thread; a check that fails counts as not installed, so the install
 * flow is the way forward.
 */
internal suspend fun checkInstalledLibraryReady(): Boolean =
    runSuspendCatching { withContext(Dispatchers.IO) { isInstalledLibraryReady() } }.getOrDefault(false)

/**
 * Skip the download only for a complete, readable library of a compatible version: a seforim.db
 * truncated by an interrupted install must not look installed.
 */
private fun isInstalledLibraryReady(): Boolean {
    val database =
        try {
            Path.of(expectedDatabasePath())
        } catch (_: InvalidPathException) {
            return false
        }
    return checkLibraryHealth(libraryFilesFor(database)).isHealthy && DatabaseVersionManager.isDatabaseVersionCompatible()
}

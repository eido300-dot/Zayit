package io.github.kdroidfilter.seforimapp.features.database.health

import io.github.kdroidfilter.seforimapp.core.settings.AppSettings
import io.github.kdroidfilter.seforimapp.framework.database.DamagedPart
import io.github.kdroidfilter.seforimapp.framework.database.DatabaseVersionManager
import io.github.kdroidfilter.seforimapp.framework.database.LibraryVerifier
import io.github.kdroidfilter.seforimapp.framework.database.getDatabasePath
import io.github.kdroidfilter.seforimapp.framework.database.libraryFilesFor
import io.github.kdroidfilter.seforimapp.framework.database.libraryFingerprint
import io.github.kdroidfilter.seforimapp.framework.database.shouldVerifyLibrary
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.logger.errorln
import io.github.kdroidfilter.seforimapp.logger.infoln
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the installed library back once, when needed (see [shouldVerifyLibrary]), and returns the
 * damaged parts. An intact library is remembered and not read again; a damaged one is checked again
 * on every launch until it is reinstalled.
 *
 * @param installedThisSession true when this session showed onboarding or the reinstall window.
 */
suspend fun verifyLibraryIfNeeded(
    installedThisSession: Boolean,
    verifier: LibraryVerifier = LibraryVerifier(),
): List<DamagedPart> {
    val database =
        try {
            Path.of(getDatabasePath())
        } catch (_: IllegalStateException) {
            // No database at all is the startup check's job.
            return emptyList()
        }
    val fingerprint =
        try {
            libraryFingerprint(
                version = DatabaseVersionManager.getCurrentDatabaseVersion(),
                databaseSize = Files.size(database),
                databaseModified = Files.getLastModifiedTime(database).toMillis(),
            )
        } catch (_: IOException) {
            return emptyList()
        }
    if (!shouldVerifyLibrary(PortableEnvironment.isPortable, installedThisSession, fingerprint, AppSettings.getVerifiedLibrary())) {
        return emptyList()
    }
    infoln { "[LibraryVerify] reading the library back to check the drive" }
    val damaged = verifier.verify(libraryFilesFor(database))
    if (damaged.isEmpty()) {
        AppSettings.setVerifiedLibrary(fingerprint)
    } else {
        errorln { "[LibraryVerify] library damaged on the drive: $damaged" }
    }
    return damaged
}

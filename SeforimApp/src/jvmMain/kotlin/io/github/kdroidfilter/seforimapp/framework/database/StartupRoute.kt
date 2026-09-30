package io.github.kdroidfilter.seforimapp.framework.database

/** Where the app goes at startup. */
sealed interface StartupRoute {
    data object Onboarding : StartupRoute

    /**
     * The main window. Missing optional parts are found once it is up, and shown in its banner.
     * [libraryChecked] is false when the startup check failed or could not look at the book table, and
     * the app opened without it, or when this stands in for a copy that found the drive in use.
     */
    data class Main(
        val libraryChecked: Boolean,
    ) : StartupRoute

    /** The database update window: a reinstall when [problems] is not empty, a version update otherwise. */
    data class Update(
        val problems: List<LibraryProblem>,
    ) : StartupRoute

    /** The library is broken and reinstalling would not help; explain instead of offering it. */
    data class LibraryError(
        val problems: List<LibraryProblem>,
        val reason: BlockedReason,
    ) : StartupRoute
}

enum class BlockedReason {
    /** `SEFORIMAPP_DATABASE_PATH` points at the database; a reinstall does not touch that path. */
    DatabasePathOverridden,

    /** The same problems came back right after a reinstall; reinstalling again would loop. */
    RepeatedAfterReinstall,
}

/**
 * Decides the startup route. Onboarding comes first, then problems that need a reinstall, then the
 * version check, which stays as before: a missing version file still means a reinstall.
 */
internal fun routeStartup(
    onboardingFinished: Boolean,
    health: LibraryHealth,
    versionCompatible: Boolean,
    databasePathOverridden: Boolean,
    repeatedAfterReinstall: Boolean,
    reinstallRequested: List<LibraryProblem>? = null,
): StartupRoute {
    if (!onboardingFinished) return StartupRoute.Onboarding
    if (reinstallRequested != null) {
        // Asked for from the banner in the last session; the user already decided.
        val problems = (reinstallRequested + health.problems).distinct()
        return if (databasePathOverridden) {
            StartupRoute.LibraryError(problems, BlockedReason.DatabasePathOverridden)
        } else {
            StartupRoute.Update(problems)
        }
    }
    if (health.needsReinstall) {
        return when {
            databasePathOverridden -> StartupRoute.LibraryError(health.problems, BlockedReason.DatabasePathOverridden)
            repeatedAfterReinstall -> StartupRoute.LibraryError(health.problems, BlockedReason.RepeatedAfterReinstall)
            else -> StartupRoute.Update(health.problems)
        }
    }
    if (!versionCompatible) return StartupRoute.Update(emptyList())
    return StartupRoute.Main(libraryChecked = health.conclusive)
}

/**
 * The record kept when a reinstall is offered: the problems and the database's modification time.
 * The same problems with a different time on the next launch mean a reinstall ran and did not help.
 */
internal fun reinstallMarker(
    problems: List<LibraryProblem>,
    databaseModified: Long?,
): String = problems.sorted().joinToString(",") + "@" + (databaseModified ?: -1L)

/** Problems as stored in settings: their names, comma-separated. */
internal fun encodeProblems(problems: List<LibraryProblem>): String = problems.joinToString(",") { it.name }

/** The problems in [text]; unknown names (from another version) are dropped. */
internal fun decodeProblems(text: String): List<LibraryProblem> =
    text.split(',').mapNotNull { name -> LibraryProblem.entries.firstOrNull { it.name == name.trim() } }

/**
 * Whether the record of the last reinstall can be dropped: every check ran ([libraryChecked]) and
 * found nothing wrong, on a launch after the one that installed, whose next launch must still
 * recognize the same problems coming back. On a drive, only once the library was read back intact
 * ([readBackIntact]).
 */
internal fun canForgetReinstall(
    problems: List<LibraryProblem>,
    libraryChecked: Boolean,
    installedThisSession: Boolean,
    isPortable: Boolean,
    readBackIntact: Boolean,
): Boolean = libraryChecked && problems.isEmpty() && !installedThisSession && (readBackIntact || !isPortable)

internal fun isRepeatedAfterReinstall(
    marker: String?,
    problems: List<LibraryProblem>,
    databaseModified: Long?,
): Boolean {
    if (marker.isNullOrBlank()) return false
    val separator = marker.lastIndexOf('@')
    if (separator < 0) return false
    val sameProblems = marker.substring(0, separator) == problems.sorted().joinToString(",")
    val changedSince = marker.substring(separator + 1) != (databaseModified ?: -1L).toString()
    return sameProblems && changedSince
}

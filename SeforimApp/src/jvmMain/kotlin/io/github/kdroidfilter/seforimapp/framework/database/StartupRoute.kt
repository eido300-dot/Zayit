package io.github.kdroidfilter.seforimapp.framework.database

/** Where the app goes at startup. */
sealed interface StartupRoute {
    data object Onboarding : StartupRoute

    /** The main window; [degraded] lists missing optional parts to warn about. */
    data class Main(
        val degraded: List<LibraryProblem>,
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
): StartupRoute {
    if (!onboardingFinished) return StartupRoute.Onboarding
    if (health.needsReinstall) {
        return when {
            databasePathOverridden -> StartupRoute.LibraryError(health.problems, BlockedReason.DatabasePathOverridden)
            repeatedAfterReinstall -> StartupRoute.LibraryError(health.problems, BlockedReason.RepeatedAfterReinstall)
            else -> StartupRoute.Update(health.problems)
        }
    }
    if (!versionCompatible) return StartupRoute.Update(emptyList())
    return StartupRoute.Main(health.degraded)
}

/**
 * The record kept when a reinstall is offered: the problems and the database's modification time.
 * The same problems with a different time on the next launch mean a reinstall ran and did not help.
 */
internal fun reinstallMarker(
    problems: List<LibraryProblem>,
    databaseModified: Long?,
): String = problems.sorted().joinToString(",") + "@" + (databaseModified ?: -1L)

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

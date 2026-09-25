package io.github.kdroidfilter.seforimapp.framework.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupRouteTest {
    private val healthy = LibraryHealth(emptyList())
    private val truncated = LibraryHealth(listOf(LibraryProblem.DatabaseTruncated))
    private val noIndex = LibraryHealth(listOf(LibraryProblem.TextIndexMissing))

    private fun route(
        onboardingFinished: Boolean = true,
        health: LibraryHealth = healthy,
        versionCompatible: Boolean = true,
        overridden: Boolean = false,
        repeated: Boolean = false,
    ) = routeStartup(onboardingFinished, health, versionCompatible, overridden, repeated)

    @Test
    fun `onboarding comes before any library problem`() {
        assertEquals(StartupRoute.Onboarding, route(onboardingFinished = false, health = truncated))
    }

    @Test
    fun `healthy library of a compatible version opens the main window`() {
        assertEquals(StartupRoute.Main(emptyList()), route())
    }

    @Test
    fun `missing optional parts open the main window with a warning`() {
        assertEquals(StartupRoute.Main(listOf(LibraryProblem.TextIndexMissing)), route(health = noIndex))
    }

    @Test
    fun `broken database offers a reinstall listing the problems`() {
        assertEquals(StartupRoute.Update(listOf(LibraryProblem.DatabaseTruncated)), route(health = truncated))
    }

    @Test
    fun `incompatible version of a healthy library offers the version update`() {
        assertEquals(StartupRoute.Update(emptyList()), route(versionCompatible = false))
    }

    @Test
    fun `database path override blocks a reinstall that could not help`() {
        assertEquals(
            StartupRoute.LibraryError(truncated.problems, BlockedReason.DatabasePathOverridden),
            route(health = truncated, overridden = true),
        )
    }

    @Test
    fun `same problems right after a reinstall block another one`() {
        assertEquals(
            StartupRoute.LibraryError(truncated.problems, BlockedReason.RepeatedAfterReinstall),
            route(health = truncated, repeated = true),
        )
    }

    @Test
    fun `repeat is detected only when the database changed since the marker`() {
        val problems = listOf(LibraryProblem.DatabaseTruncated, LibraryProblem.CatalogMissing)
        val marker = reinstallMarker(problems, databaseModified = 1_000L)

        assertFalse(isRepeatedAfterReinstall(marker, problems, databaseModified = 1_000L), "user closed the window without reinstalling")
        assertTrue(isRepeatedAfterReinstall(marker, problems.reversed(), databaseModified = 2_000L), "reinstalled, same problems")
        assertFalse(isRepeatedAfterReinstall(marker, listOf(LibraryProblem.DatabaseEmpty), databaseModified = 2_000L))
        assertFalse(isRepeatedAfterReinstall(null, problems, databaseModified = 2_000L))
        assertFalse(isRepeatedAfterReinstall("garbage", problems, databaseModified = 2_000L))
    }
}

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
        requested: List<LibraryProblem>? = null,
    ) = routeStartup(onboardingFinished, health, versionCompatible, overridden, repeated, requested)

    @Test
    fun `a reinstall asked for from the banner opens the reinstall window with every problem`() {
        assertEquals(
            StartupRoute.Update(listOf(LibraryProblem.DatabaseUnreadable, LibraryProblem.TextIndexMissing)),
            route(health = noIndex, requested = listOf(LibraryProblem.DatabaseUnreadable)),
        )
        assertEquals(
            StartupRoute.LibraryError(listOf(LibraryProblem.TextIndexMissing), BlockedReason.DatabasePathOverridden),
            route(overridden = true, requested = listOf(LibraryProblem.TextIndexMissing)),
        )
        assertEquals(StartupRoute.Onboarding, route(onboardingFinished = false, requested = listOf(LibraryProblem.TextIndexMissing)))
    }

    @Test
    fun `stored problems read back, skipping names this version does not know`() {
        val problems = listOf(LibraryProblem.DatabaseUnreadable, LibraryProblem.DictionaryMissing)

        assertEquals(problems, decodeProblems(encodeProblems(problems)))
        assertEquals(listOf(LibraryProblem.CatalogMissing), decodeProblems("SomethingNew,CatalogMissing"))
    }

    @Test
    fun `onboarding comes before any library problem`() {
        assertEquals(StartupRoute.Onboarding, route(onboardingFinished = false, health = truncated))
    }

    @Test
    fun `healthy library of a compatible version opens the main window`() {
        assertEquals(StartupRoute.Main(libraryChecked = true), route())
    }

    @Test
    fun `missing optional parts do not keep the main window from opening`() {
        assertEquals(StartupRoute.Main(libraryChecked = true), route(health = noIndex))
    }

    @Test
    fun `a book table that could not be looked at opens the main window unchecked`() {
        assertEquals(StartupRoute.Main(libraryChecked = false), route(health = LibraryHealth(emptyList(), conclusive = false)))
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
    fun `an overridden database without a catalog still opens`() {
        val noCatalog = LibraryHealth(listOf(LibraryProblem.CatalogMissing))

        assertEquals(StartupRoute.Main(libraryChecked = true), route(health = noCatalog, overridden = true))
        assertEquals(StartupRoute.Update(noCatalog.problems), route(health = noCatalog))
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

    @Test
    fun `the last reinstall is forgotten only when a later launch finds nothing wrong`() {
        fun forget(
            problems: List<LibraryProblem> = emptyList(),
            checked: Boolean = true,
            installed: Boolean = false,
            portable: Boolean = false,
            readBack: Boolean = false,
        ) = canForgetReinstall(
            problems = problems,
            libraryChecked = checked,
            installedThisSession = installed,
            isPortable = portable,
            readBackIntact = readBack,
        )

        assertTrue(forget())
        assertFalse(forget(problems = listOf(LibraryProblem.TextIndexMissing)))
        assertFalse(forget(checked = false), "a check that failed proves nothing")
        assertFalse(forget(installed = true), "the next launch must still see the same problems coming back")
        assertFalse(forget(portable = true), "not read back yet")
        assertTrue(forget(portable = true, readBack = true))
        assertFalse(forget(problems = listOf(LibraryProblem.DictionaryMissing), portable = true, readBack = true))
    }
}

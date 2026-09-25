package io.github.kdroidfilter.seforimapp.framework.database

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingDbCleanupContainmentTest {
    private val root: Path = createTempDirectory("zayit-pending-cleanup")
    private val databasesDir: Path = root.resolve("E/zayit-data/databases")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `portable entry inside the databases folder is stored relative`() {
        assertEquals(
            "seforim.db.lucene/_0.cfs",
            markerEntryFor(databasesDir.resolve("seforim.db.lucene/_0.cfs"), databasesDir, isPortable = true),
        )
    }

    @Test
    fun `portable entry outside the databases folder is not recorded`() {
        assertNull(markerEntryFor(root.resolve("E/zayit-data/settings.properties"), databasesDir, isPortable = true))
        assertNull(markerEntryFor(databasesDir, databasesDir, isPortable = true))
    }

    @Test
    fun `relative entry resolves against the drive it is on now`() {
        val movedDir = root.resolve("F/zayit-data/databases")

        assertEquals(
            movedDir.resolve("seforim.db").toAbsolutePath().normalize(),
            resolveMarkerEntry("seforim.db", movedDir, isPortable = true),
        )
    }

    @Test
    fun `portable mode rejects entries that escape or point elsewhere`() {
        assertNull(resolveMarkerEntry("../settings.properties", databasesDir, isPortable = true))
        assertNull(resolveMarkerEntry(root.resolve("E/zayit-data/databases/seforim.db").toString(), databasesDir, isPortable = true))
        assertNull(resolveMarkerEntry(".", databasesDir, isPortable = true))
    }

    @Test
    fun `host mode keeps absolute paths as before`() {
        val file = root.resolve("custom/seforim.db").toAbsolutePath()

        assertEquals(file.toString(), markerEntryFor(file, databasesDir, isPortable = false))
        assertEquals(file, resolveMarkerEntry(file.toString(), databasesDir, isPortable = false))
    }
}

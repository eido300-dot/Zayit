package io.github.kdroidfilter.seforimapp.framework.database

import app.cash.sqldelight.db.QueryResult
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistentSqliteDriverTest {
    private val root: Path = createTempDirectory("zayit-driver")
    private val database: Path = root.resolve("seforim.db")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun names(): Set<String> = Files.list(root).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

    private fun PersistentSqliteDriver.pragma(name: String): String? =
        executeQuery(
            null,
            "PRAGMA $name",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            0,
        ).value

    @Test
    fun `read tuning is rewritten for a removable drive only`() {
        assertEquals("PRAGMA journal_mode=DELETE", removableDriveSql("PRAGMA journal_mode=WAL"))
        assertEquals("PRAGMA journal_mode=DELETE", removableDriveSql("  pragma  Journal_Mode = wal "))
        assertEquals("PRAGMA mmap_size=0", removableDriveSql("PRAGMA mmap_size=536870912"))
        assertEquals("PRAGMA synchronous=NORMAL", removableDriveSql("PRAGMA synchronous=NORMAL"))
        assertEquals("SELECT 1", removableDriveSql("SELECT 1"))
    }

    @Test
    fun `the repository opens in rollback mode with no wal or shm file on a removable drive`() {
        val driver = PersistentSqliteDriver("jdbc:sqlite:$database", removableDrive = true)
        try {
            SeforimRepository(database.toString(), driver)

            assertEquals("delete", driver.pragma("journal_mode"))
            assertEquals("0", driver.pragma("mmap_size"))
            assertEquals(setOf("seforim.db"), names())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a normal installation keeps the repository's own tuning`() {
        val driver = PersistentSqliteDriver("jdbc:sqlite:$database")
        try {
            SeforimRepository(database.toString(), driver)

            assertEquals("wal", driver.pragma("journal_mode"))
            assertTrue("seforim.db-shm" in names())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a library left in WAL mode is converted, and an open second connection does not stop the app`() {
        PersistentSqliteDriver("jdbc:sqlite:$database").also { SeforimRepository(database.toString(), it) }.close()
        DriverManager.getConnection("jdbc:sqlite:$database").use { other ->
            other.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            val driver = PersistentSqliteDriver("jdbc:sqlite:$database", removableDrive = true)
            try {
                // The other connection is open: the switch is refused, the repository still opens.
                SeforimRepository(database.toString(), driver)
            } finally {
                driver.close()
            }
        }

        val driver = PersistentSqliteDriver("jdbc:sqlite:$database", removableDrive = true)
        try {
            SeforimRepository(database.toString(), driver)
            assertEquals("delete", driver.pragma("journal_mode"))
        } finally {
            driver.close()
        }
    }
}

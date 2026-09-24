package io.github.kdroidfilter.seforimapp.framework.database

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserSettingsImportTest {
    private val dir: File = Files.createTempDirectory("usersettings").toFile()
    private val db = File(dir, "user_settings.db")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `pending import replaces the database and drops stale sidecars`() {
        db.writeText("old")
        File(dir, "user_settings.db-wal").writeText("old wal")
        pendingUserSettingsImportFile(db.path).writeText("imported")

        applyPendingUserSettingsImport(db.path)

        assertEquals("imported", db.readText())
        assertFalse(pendingUserSettingsImportFile(db.path).exists())
        assertFalse(File(dir, "user_settings.db-wal").exists())
    }

    @Test
    fun `no pending import leaves the database untouched`() {
        db.writeText("current")

        applyPendingUserSettingsImport(db.path)

        assertEquals("current", db.readText())
    }

    @Test
    fun `only files with the SQLite header are accepted`() {
        val sqlite = File(dir, "backup.db").apply { writeBytes("SQLite format 3\u0000rest".toByteArray()) }
        val text = File(dir, "notes.txt").apply { writeText("hello") }
        val empty = File(dir, "empty.db").apply { writeBytes(ByteArray(0)) }

        assertTrue(isSqliteDatabase(sqlite))
        assertFalse(isSqliteDatabase(text))
        assertFalse(isSqliteDatabase(empty))
        assertFalse(isSqliteDatabase(File(dir, "missing.db")))
    }
}

package io.github.kdroidfilter.seforimapp.framework.database

import io.github.kdroidfilter.seforimapp.framework.io.moveOver
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.path
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.Properties

private const val SETTINGS_DB_DIR = "settings"
private const val SETTINGS_DB_NAME = "user_settings.db"

fun getUserSettingsDatabasePath(): String {
    val root = File(FileKit.databasesDir.path, SETTINGS_DB_DIR).apply { mkdirs() }
    return File(root, SETTINGS_DB_NAME).absolutePath
}

private const val PENDING_IMPORT_SUFFIX = ".import-pending"
private const val REPLACED_BACKUP_SUFFIX = ".before-import"
private val JOURNAL_SUFFIXES = listOf("-journal", "-wal")
private const val SHM_SUFFIX = "-shm"

// sqlite-jdbc open flags: SQLITE_OPEN_READONLY.
private const val SQLITE_OPEN_READONLY = "1"
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/**
 * Where an imported backup is staged until the next launch. The running app keeps the user
 * database open, so replacing it in place fails on Windows (file locked) and elsewhere swaps
 * the file under a live connection.
 */
fun pendingUserSettingsImportFile(dbPath: String = getUserSettingsDatabasePath()): File = File(dbPath + PENDING_IMPORT_SUFFIX)

/** Where the database an import replaced is kept, so an import never loses the user's data. */
fun replacedUserSettingsBackupFile(dbPath: String = getUserSettingsDatabasePath()): File = File(dbPath + REPLACED_BACKUP_SUFFIX)

/** Moves a staged import over the user database. Must run before the database is opened. */
fun applyPendingUserSettingsImport(dbPath: String = getUserSettingsDatabasePath()) {
    val pending = pendingUserSettingsImportFile(dbPath)
    if (!pending.exists()) return
    val db = File(dbPath)
    if (db.exists()) {
        // The copy keeps the journal files, so it opens exactly as the replaced database would have.
        val backup = replacedUserSettingsBackupFile(dbPath).path
        Files.copy(Paths.get(dbPath), Paths.get(backup), StandardCopyOption.REPLACE_EXISTING)
        JOURNAL_SUFFIXES.forEach { suffix ->
            val journal = Paths.get(dbPath + suffix)
            if (Files.exists(journal)) {
                Files.copy(journal, Paths.get(backup + suffix), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.deleteIfExists(Paths.get(backup + suffix))
            }
        }
    }
    // Journal sidecars of the old database must not be replayed onto the imported one. One that
    // cannot be deleted (still locked) throws, and the import waits for the next launch.
    (JOURNAL_SUFFIXES + SHM_SUFFIX).forEach { suffix -> Files.deleteIfExists(Paths.get(dbPath + suffix)) }
    pending.moveOver(db)
}

/**
 * True when [file] opens read-only as an SQLite database and passes `PRAGMA quick_check`. A
 * truncated or damaged file can still carry a valid header, and would otherwise replace the user
 * database at the next launch.
 */
fun passesQuickCheck(file: File): Boolean =
    runCatching {
        val readOnly = Properties().apply { setProperty("open_mode", SQLITE_OPEN_READONLY) }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", readOnly).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { rows -> rows.next() && rows.getString(1) == "ok" }
            }
        }
    }.getOrDefault(false)

/** True when [file] starts with the SQLite 3 header, i.e. can be used as the user database. */
fun isSqliteDatabase(file: File): Boolean =
    runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.readNBytes(header, 0, header.size) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }.getOrDefault(false)

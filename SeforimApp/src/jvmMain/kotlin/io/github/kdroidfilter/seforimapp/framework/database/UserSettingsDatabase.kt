package io.github.kdroidfilter.seforimapp.framework.database

import io.github.kdroidfilter.seforimapp.framework.io.moveOver
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.path
import java.io.File

private const val SETTINGS_DB_DIR = "settings"
private const val SETTINGS_DB_NAME = "user_settings.db"

fun getUserSettingsDatabasePath(): String {
    val root = File(FileKit.databasesDir.path, SETTINGS_DB_DIR).apply { mkdirs() }
    return File(root, SETTINGS_DB_NAME).absolutePath
}

private const val PENDING_IMPORT_SUFFIX = ".import-pending"
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/**
 * Where an imported backup is staged until the next launch. The running app keeps the user
 * database open, so replacing it in place fails on Windows (file locked) and elsewhere swaps
 * the file under a live connection.
 */
fun pendingUserSettingsImportFile(dbPath: String = getUserSettingsDatabasePath()): File = File(dbPath + PENDING_IMPORT_SUFFIX)

/** Moves a staged import over the user database. Must run before the database is opened. */
fun applyPendingUserSettingsImport(dbPath: String = getUserSettingsDatabasePath()) {
    val pending = pendingUserSettingsImportFile(dbPath)
    if (!pending.exists()) return
    // Journal sidecars of the old database must not be replayed onto the imported one.
    File("$dbPath-wal").delete()
    File("$dbPath-shm").delete()
    File("$dbPath-journal").delete()
    pending.moveOver(File(dbPath))
}

/** True when [file] starts with the SQLite 3 header, i.e. can be used as the user database. */
fun isSqliteDatabase(file: File): Boolean =
    runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.readNBytes(header, 0, header.size) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }.getOrDefault(false)

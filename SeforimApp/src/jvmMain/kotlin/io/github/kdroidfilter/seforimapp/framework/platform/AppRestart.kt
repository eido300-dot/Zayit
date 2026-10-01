package io.github.kdroidfilter.seforimapp.framework.platform

import dev.nucleusframework.core.runtime.AppRestarter
import dev.nucleusframework.core.runtime.SingleInstanceManager
import io.github.kdroidfilter.seforimapp.core.settings.AppSettingsStore
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

/** How long a restarted copy waits for the previous one to let go of the single-instance lock. */
private const val RESTART_WAIT_MILLIS = 10_000L
private const val RESTART_POLL_MILLIS = 100L

/**
 * Restarts the app. The settings are saved first, because the new process starts before this one
 * exits and must read what was just written (a reinstall request, a reset). A restart note is left
 * too: without it the new process could find this one still holding the single-instance lock (or,
 * portable, the drive), hand over to it and quit, and the app would just close. The drive lock stays
 * held until this process exits: [AppRestarter.restartApplication] starts the new process and then
 * exits, and returns only when it could not start one. The note is then removed and the app carries
 * on.
 */
fun restartApp() {
    AppSettingsStore.flushIfPortable()
    val dataDir = PortableEnvironment.layout?.dataDir
    if (dataDir == null) {
        flushHostSettings()
        writeHostRestartNote()
    } else {
        DriveLock.noteRestart(dataDir)
    }
    AppRestarter.restartApplication()
    if (dataDir == null) deleteHostRestartNote() else DriveLock.forgetRestart(dataDir)
}

/**
 * Called by `main()` after [SingleInstanceManager.configuration] is set and before the lock is
 * taken. When the previous copy left a restart note, waits until its single-instance lock is free,
 * so this copy does not hand over to a process that is exiting. Without a note it returns at once.
 * A portable copy waits for its drive instead (see [DriveLock.acquireForProcess]).
 */
fun waitForRestartingInstance(
    sleeper: (Long) -> Unit = Thread::sleep,
    now: () -> Long = System::currentTimeMillis,
) {
    val note = hostRestartNote()
    val noted =
        try {
            Files.isRegularFile(note) && now() - Files.getLastModifiedTime(note).toMillis() < RESTART_WAIT_MILLIS
        } catch (_: IOException) {
            false
        }
    if (!noted) return
    val deadline = now() + RESTART_WAIT_MILLIS
    while (!isLockFree(SingleInstanceManager.configuration.lockFilePath) && now() < deadline) {
        sleeper(RESTART_POLL_MILLIS)
    }
    deleteHostRestartNote()
}

/** True when nobody holds [lockFile]; the lock taken to find out is released at once. */
private fun isLockFree(lockFile: Path): Boolean =
    try {
        FileChannel.open(lockFile, CREATE, WRITE).use { channel -> channel.tryLock()?.use { true } ?: false }
    } catch (_: OverlappingFileLockException) {
        false
    } catch (_: IOException) {
        // Cannot tell; let the single-instance check decide as before.
        true
    }

private fun hostRestartNote(): Path = SingleInstanceManager.configuration.let { it.lockFilesDir.resolve("${it.lockIdentifier}.restarting") }

private fun writeHostRestartNote() {
    try {
        Files.write(hostRestartNote(), byteArrayOf(1))
    } catch (e: IOException) {
        warnln(e) { "[restart] cannot leave a restart note" }
    }
}

private fun deleteHostRestartNote() {
    try {
        Files.deleteIfExists(hostRestartNote())
    } catch (_: IOException) {
        // A stale note only makes the next start wait while the lock is really held.
    }
}

/**
 * An installed copy keeps its settings in the host's Preferences, which Linux and macOS write only
 * later. Never called in portable mode, which must not touch the host's Preferences.
 */
private fun flushHostSettings() {
    try {
        Preferences.userRoot().flush()
    } catch (_: BackingStoreException) {
        // The shutdown hook still saves them; at worst the change shows one launch late.
    }
}

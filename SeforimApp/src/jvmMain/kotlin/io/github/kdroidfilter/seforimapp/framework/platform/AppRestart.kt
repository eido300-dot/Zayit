package io.github.kdroidfilter.seforimapp.framework.platform

import dev.nucleusframework.core.runtime.AppRestarter
import io.github.kdroidfilter.seforimapp.core.settings.AppSettingsStore
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

/**
 * Restarts the app. The settings are saved first, because the new process starts before this one
 * exits and must read what was just written (a reinstall request, a reset). A portable copy also
 * leaves a restart note, so the new process waits for the drive instead of reporting it in use. The
 * drive lock stays held until this process exits: [AppRestarter.restartApplication] starts the new
 * process and then exits, and returns only when it could not start one. The note is then removed and
 * the app carries on, still holding the drive.
 */
fun restartApp() {
    AppSettingsStore.flushIfPortable()
    val dataDir = PortableEnvironment.layout?.dataDir
    if (dataDir == null) flushHostSettings()
    dataDir?.let(DriveLock::noteRestart)
    AppRestarter.restartApplication()
    dataDir?.let(DriveLock::forgetRestart)
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

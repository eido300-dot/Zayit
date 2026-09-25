package io.github.kdroidfilter.seforimapp.framework.platform

import io.github.kdroidfilter.platformtools.appmanager.restartApplication
import io.github.kdroidfilter.seforimapp.core.settings.AppSettingsStore
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment

/**
 * Restarts the app. A portable copy first saves its settings and leaves a restart note, so the new
 * process waits for the drive instead of reporting it in use. The drive lock stays held until this
 * process exits: `restartApplication` starts the new process and then exits (verified for
 * platformtools 0.7.5), and returns only when it could not start one. The note is then removed and
 * the app carries on, still holding the drive.
 */
fun restartApp() {
    AppSettingsStore.flushIfPortable()
    val dataDir = PortableEnvironment.layout?.dataDir
    dataDir?.let(DriveLock::noteRestart)
    restartApplication()
    dataDir?.let(DriveLock::forgetRestart)
}

package io.github.kdroidfilter.seforimapp.core.settings

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.framework.portable.PortableLayout
import io.github.kdroidfilter.seforimapp.framework.portable.PortableSettingsStore
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.util.Properties

/**
 * The one settings instance shared by [AppSettings] and the DI graph.
 *
 * This is an `object` rather than a DI binding because [AppSettings] (itself an `object`) reads its
 * settings on first access, which can happen before the Metro graph exists. There must be exactly
 * one instance: in portable mode, two stores on the same file would each write their own snapshot
 * and lose the other's changes.
 *
 * Nothing here throws: a failure while [AppSettings] initializes would leave that object unusable
 * for the rest of the process (`NoClassDefFoundError`), so the app would never start.
 *
 * Must not reference [AppSettings], `DatabaseUtils` or `MainAppState`.
 */
object AppSettingsStore {
    private val portableStore: PortableSettingsStore? by lazy {
        PortableEnvironment.layout?.let(::openPortableStore)
    }

    /** Settings on the drive in portable mode, the host's Preferences otherwise. */
    val settings: Settings by lazy { createAppSettings(PortableEnvironment.layout, portableStore) }

    /**
     * Writes pending portable settings and waits briefly. Called before the app exits or restarts:
     * the shutdown hook alone may run after the next instance has already read the old file.
     */
    fun flushIfPortable() {
        portableStore?.flush()
    }

    /** Deletes the portable settings file and its backup copies, so a reset cannot bring old values back. */
    fun resetPortableFiles() {
        portableStore?.resetFiles()
    }

    private fun openPortableStore(layout: PortableLayout): PortableSettingsStore {
        val store = PortableSettingsStore.open(layout.settingsFile)
        try {
            // Best effort only: hooks do not run on a Windows logoff or a forced kill. Settings are
            // written as soon as they change, which is the real protection.
            Runtime.getRuntime().addShutdownHook(Thread({ store.flush() }, "zayit-settings-flush"))
        } catch (e: IllegalStateException) {
            warnln(e) { "[portable] settings flush on exit not registered: shutdown already started" }
        }
        return store
    }
}

/**
 * Chooses the settings backend. With a portable [layout] this never touches [hostFactory], so
 * nothing is read from or written to the host's Preferences (the Windows Registry), even when the
 * file on the drive cannot be opened: the session then keeps its settings in memory only.
 */
internal fun createAppSettings(
    layout: PortableLayout?,
    portableStore: PortableSettingsStore?,
    hostFactory: () -> Settings = { Settings() },
): Settings =
    when {
        layout == null -> hostFactory()
        portableStore != null -> portableStore.settings
        else -> PropertiesSettings(Properties())
    }

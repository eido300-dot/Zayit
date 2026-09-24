package io.github.kdroidfilter.seforimapp.framework.portable

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.github.kdroidfilter.seforimapp.logger.errorln
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executor

/**
 * Settings kept in a file on the portable drive instead of the host's Preferences (the Windows
 * Registry), wired so that a bad file or an unplugged drive never stops the app from starting.
 */
class PortableSettingsStore private constructor(
    /** The settings to hand to `AppSettings`; typed reads never throw on a bad value. */
    val settings: Settings,
    /** What was found on disk when the store was opened. */
    val loadResult: LoadResult,
    private val file: PortableSettingsFile,
    private val writer: SettingsWriter?,
) {
    /** True when the drive could not be read at startup; changes then stay in memory only. */
    val isReadOnly: Boolean get() = writer == null

    /** Writes pending changes and waits up to [timeoutMillis]. Returns `false` on timeout. */
    fun flush(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean = writer?.flush(timeoutMillis) ?: true

    /**
     * Deletes the settings file and its backup copies, after any pending write. Called on "reset
     * app" so old values cannot come back from `.bak`. A read-only session leaves the files alone.
     */
    fun resetFiles(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean =
        writer?.runExclusive(timeoutMillis) { file.resetFiles() } ?: true

    companion object {
        /** Opens the store backed by [settingsFile]. Never throws. */
        fun open(
            settingsFile: Path,
            executor: Executor = SettingsWriter.newWriterExecutor(),
        ): PortableSettingsStore {
            val file = PortableSettingsFile(settingsFile)
            val result = file.load()
            val initial = (result as? LoadResult.Loaded)?.properties ?: Properties()
            val writer =
                when (result) {
                    is LoadResult.IoError -> {
                        errorln(result.cause) { "[portable] settings unreadable; running without saving" }
                        null
                    }
                    LoadResult.Corrupt -> {
                        warnln { "[portable] settings damaged; starting from defaults" }
                        SettingsWriter(save = file::save, executor = executor)
                    }
                    is LoadResult.Loaded -> SettingsWriter(save = file::save, executor = executor)
                }
            val backing = PropertiesSettings(initial) { properties -> writer?.markDirty(properties) }
            return PortableSettingsStore(LenientSettings(backing), result, file, writer)
        }
    }
}

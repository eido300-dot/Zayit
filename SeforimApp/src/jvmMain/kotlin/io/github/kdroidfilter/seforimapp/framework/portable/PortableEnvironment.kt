package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.framework.platform.currentExecutablePath
import io.github.kdroidfilter.seforimapp.logger.infoln

/**
 * Process-wide portable-mode decision.
 *
 * This is an `object` rather than a DI binding because it must be available before the Metro
 * graph exists: `AppSettings` (itself an `object`) loads its settings store on first access, and
 * FileKit is initialized in `main()` before the graph is created. The value is computed once and
 * never changes during the process.
 *
 * Must not reference `AppSettings`, `DatabaseUtils` or `MainAppState`, which read this object
 * during their own initialization.
 */
object PortableEnvironment {
    /** The portable layout, or `null` when running as a normal installation. */
    val layout: PortableLayout? by lazy {
        resolvePortableLayout(currentExecutablePath(), readOverride()).also { resolved ->
            infoln { "[portable] mode=${if (resolved != null) "portable" else "host"}" }
        }
    }

    /** True when all app data and settings live next to the program. */
    val isPortable: Boolean get() = layout != null

    private fun readOverride(): String? =
        (System.getenv(PORTABLE_DATA_DIR_OVERRIDE) ?: System.getProperty(PORTABLE_DATA_DIR_OVERRIDE))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

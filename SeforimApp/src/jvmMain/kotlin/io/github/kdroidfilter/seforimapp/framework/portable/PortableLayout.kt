package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.warnln
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.zip.CRC32

/** Folder next to the program that holds all app data and settings when running portable. */
const val PORTABLE_DATA_DIR_NAME = "zayit-data"

/** File inside [PORTABLE_DATA_DIR_NAME] whose presence switches portable mode on. */
const val PORTABLE_MARKER_NAME = ".zayit-portable"

/** Dev/QA override pointing at a data dir; portable mode still requires the marker inside it. */
const val PORTABLE_DATA_DIR_OVERRIDE = "SEFORIMAPP_PORTABLE_DATA_DIR"

private const val HEX_WIDTH = 8

/**
 * Where portable data lives. Only [dataDir] is stored so equality depends on it alone; the other
 * paths are derived. The marker and the settings file sit at the root of [dataDir], never inside
 * `databases/`, because "reset app" wipes that folder and must not switch portable mode off.
 */
data class PortableLayout(
    val dataDir: Path,
) {
    val filesDir: Path get() = dataDir
    val cacheDir: Path get() = dataDir.resolve("cache")
    val settingsFile: Path get() = dataDir.resolve("settings.properties")
    val markerFile: Path get() = dataDir.resolve(PORTABLE_MARKER_NAME)
}

/**
 * Returns the `X.app` bundle when [executable] has the macOS layout `X.app/Contents/MacOS/exe`.
 * Detection is by path shape, not by name, because the shipped bundle name is localized.
 */
fun appBundleOf(executable: Path): Path? {
    val macOsDir = executable.parent ?: return null
    val contentsDir = macOsDir.parent ?: return null
    val bundle = contentsDir.parent ?: return null
    val isBundleLayout =
        macOsDir.fileName?.toString() == "MacOS" &&
            contentsDir.fileName?.toString() == "Contents" &&
            bundle.fileName?.toString()?.endsWith(".app") == true
    return if (isBundleLayout) bundle else null
}

/** The folder that makes up the installed program: the macOS bundle, or the executable's folder. */
fun programRootOf(executable: Path): Path? = appBundleOf(executable) ?: executable.parent

/** The folder that holds the program and, in portable mode, its [PORTABLE_DATA_DIR_NAME] sibling. */
fun containerOf(executable: Path): Path? = appBundleOf(executable)?.parent ?: executable.parent

/**
 * True when macOS runs the app from a randomized read-only copy (App Translocation). The real
 * location, and any data folder next to it, is not visible from there.
 */
fun isTranslocated(executable: Path): Boolean = executable.toString().replace('\\', '/').contains("/AppTranslocation/")

/**
 * Resolves the portable layout, or `null` for a normal (host) installation.
 *
 * Portable mode is on only when [PORTABLE_MARKER_NAME] exists in the data dir. [override], when
 * set, replaces the location next to [executable] but still requires the marker. Never throws:
 * an unusable override is logged and treated as host mode.
 */
fun resolvePortableLayout(
    executable: Path?,
    override: String?,
    isFile: (Path) -> Boolean = Files::isRegularFile,
): PortableLayout? {
    val dataDir = dataDirFor(executable, override) ?: return null
    val layout = PortableLayout(dataDir)
    return if (isFile(layout.markerFile)) layout else null
}

private fun dataDirFor(
    executable: Path?,
    override: String?,
): Path? {
    val trimmedOverride = override?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmedOverride != null) {
        return try {
            Path.of(trimmedOverride)
        } catch (e: InvalidPathException) {
            warnln(e) { "[portable] ignoring invalid $PORTABLE_DATA_DIR_OVERRIDE value" }
            null
        }
    }
    return executable?.let(::containerOf)?.resolve(PORTABLE_DATA_DIR_NAME)
}

/**
 * Single-instance lock name for a portable copy. It differs from the installed app's lock so a
 * portable copy started while the installed app runs opens its own window instead of handing
 * over to the installed one (which would show host data).
 */
fun lockIdentifierFor(
    appId: String,
    dataDir: Path,
): String {
    val crc = CRC32()
    crc.update(
        dataDir
            .toAbsolutePath()
            .normalize()
            .toString()
            .toByteArray(Charsets.UTF_8),
    )
    return "$appId-portable-" +
        java.lang.Long
            .toHexString(crc.value)
            .padStart(HEX_WIDTH, '0')
}

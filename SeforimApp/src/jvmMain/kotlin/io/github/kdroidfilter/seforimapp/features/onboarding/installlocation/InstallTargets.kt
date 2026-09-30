package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.framework.portable.appBundleOf
import io.github.kdroidfilter.seforimapp.framework.portable.programRootOf
import java.io.IOException
import java.nio.file.Path
import java.util.Locale

/** Name of the folder a portable copy is created in, inside the folder the user picks. */
const val PORTABLE_FOLDER_NAME = "Zayit"

/** Suffix of the folder a copy is built in; renamed to [PORTABLE_FOLDER_NAME] only once complete. */
internal const val STAGING_SUFFIX = ".partial"

/** Suffix the previous program folder gets while an existing portable copy is updated. */
internal const val PREVIOUS_SUFFIX = ".old"

/**
 * Longest folder a copy may be created in on Windows. The library adds about 100 characters of
 * nested names below it, and SQLite and many tools there still stop at 260 characters.
 */
internal const val MAX_WINDOWS_TARGET_CHARS = 120

private val FAT_TYPES = setOf("fat", "fat12", "fat16", "fat32", "vfat", "msdos")

/** Network file systems: the library needs file locks and memory that a share does not give reliably. */
private val NETWORK_TYPE_PREFIXES = listOf("nfs", "cifs", "smb", "fuse.sshfs", "9p", "afpfs", "webdav")

private val UNINSTALLER = Regex("""(?i)^uninstall .*\.exe$""")

/** The result of checking a folder the user picked for a portable copy. */
sealed interface TargetCheck {
    data class Ok(
        val free: Long,
        val required: Long,
    ) : TargetCheck

    /** FAT cannot hold the 7.5 GB database file; a network share cannot hold the library safely. */
    data class UnsupportedFileSystem(
        val type: String,
    ) : TargetCheck

    data class NotEnoughSpace(
        val free: Long,
        val required: Long,
    ) : TargetCheck

    data object NotWritable : TargetCheck

    /** The folder is inside the installed program, which a copy would then copy into itself. */
    data object InsideProgramDir : TargetCheck

    data object PathTooLong : TargetCheck

    /** A portable copy is already there: its program can be updated and its data kept. */
    data class ExistingPortable(
        val path: String,
    ) : TargetCheck

    /** Something that is not a portable copy already has the name the copy would get. */
    data class AlreadyExists(
        val path: String,
    ) : TargetCheck

    /** An earlier copy stopped halfway; it has to be removed first. */
    data class StalePartial(
        val path: String,
    ) : TargetCheck

    /** The program's own location is unknown (a development run) or the folder cannot be read. */
    data object Unavailable : TargetCheck
}

/** Why a copy or an update did not complete. Nothing is left half-done in any of these cases. */
enum class FailureReason {
    /** The copied program cannot be run from there, as on a drive mounted without exec rights. */
    NotExecutable,
    AlreadyExists,
    CopyFailed,
    Unavailable,

    /** The portable copy being updated is running, on this computer or another one. */
    DriveInUse,

    /** An earlier update stopped halfway and left the previous program aside; see [PREVIOUS_SUFFIX]. */
    UpdateLeftover,
}

/** A failed copy or update; an [IOException] so callers that handle file errors also handle this. */
class PortableInstallException(
    val reason: FailureReason,
    cause: Throwable? = null,
) : IOException("Portable install failed: $reason", cause)

/**
 * The folders involved in copying the program at [source] into [destination].
 *
 * @property copyRoot where [source] lands inside [staging]: the staging folder itself, or the
 *   `.app` bundle inside it on macOS, next to which the data folder is then created.
 * @property relativeExecutable the program's executable, relative to [destination].
 */
internal data class InstallPlan(
    val source: Path,
    val staging: Path,
    val destination: Path,
    val previous: Path,
    val copyRoot: Path,
    val relativeExecutable: Path,
)

/** Where a copy of the program running as [executable] goes when [targetParent] is picked. */
internal fun planInstall(
    executable: Path,
    targetParent: Path,
): InstallPlan? {
    val source = programRootOf(executable) ?: return null
    val staging = targetParent.resolve(PORTABLE_FOLDER_NAME + STAGING_SUFFIX)
    val bundle = appBundleOf(executable)
    val bundleName = bundle?.fileName?.toString()
    return InstallPlan(
        source = source,
        staging = staging,
        destination = targetParent.resolve(PORTABLE_FOLDER_NAME),
        previous = targetParent.resolve(PORTABLE_FOLDER_NAME + PREVIOUS_SUFFIX),
        copyRoot = if (bundleName != null) staging.resolve(bundleName) else staging,
        relativeExecutable = (bundle?.parent ?: source).relativize(executable),
    )
}

/**
 * Whether a copy may be created on a file system of [type] (as `FileStore.type()` reports it)
 * whose folder is [path]. Only what is known not to work is refused: on Linux, exFAT and NTFS both
 * report `fuseblk`, so a list of accepted types would refuse them.
 */
internal fun isFileSystemSupported(
    type: String,
    path: String,
): Boolean {
    val normalized = type.lowercase(Locale.ROOT)
    return normalized !in FAT_TYPES &&
        NETWORK_TYPE_PREFIXES.none { normalized.startsWith(it) } &&
        !isUncPath(path)
}

/** A `\\server\share` path, which Windows reports with the file system of the remote disk. */
internal fun isUncPath(path: String): Boolean = path.startsWith("""\\""") || path.startsWith("//")

/** Whether [path] is too long for the library below it, on a file system with [separator]. */
internal fun isPathTooLong(
    path: String,
    separator: String,
): Boolean = separator == "\\" && path.length > MAX_WINDOWS_TARGET_CHARS

/**
 * The per-user Windows installer puts an uninstaller in the program folder. Run from the drive it
 * would delete the whole copy, notes included, and unregister the Zayit installed on the computer.
 */
internal fun isUninstaller(fileName: String): Boolean = UNINSTALLER.matches(fileName)

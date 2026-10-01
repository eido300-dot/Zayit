package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.framework.platform.PlatformInfo
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_DATA_DIR_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_MARKER_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.RetryPolicy
import io.github.kdroidfilter.seforimapp.framework.portable.deleteTree
import io.github.kdroidfilter.seforimapp.framework.portable.moveWithRetry
import io.github.kdroidfilter.seforimapp.framework.portable.syncDirectory
import io.github.kdroidfilter.seforimapp.framework.portable.writeDurably
import io.github.kdroidfilter.seforimapp.logger.errorln
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes

/**
 * The data folder and its marker, which is what makes the copy start in portable mode. Built aside
 * and renamed into place, so a data folder is never seen without its marker (see [isPortableCopy]).
 */
internal fun createDataFolder(staging: Path) {
    val building = Files.createDirectory(staging.resolve(PORTABLE_DATA_DIR_NAME + STAGING_SUFFIX))
    writeDurably(building.resolve(PORTABLE_MARKER_NAME), ByteArray(0))
    syncDirectory(building)
    moveForInstall(building, staging.resolve(PORTABLE_DATA_DIR_NAME))
    syncDirectory(staging)
}

/**
 * `Zayit` → `Zayit.old`, its data folder → the new program, new program → `Zayit`. A failed step
 * undoes the earlier ones, so the data always stays with a complete program.
 */
internal fun swapProgram(
    plan: InstallPlan,
    move: (Path, Path) -> Unit,
) {
    val oldData = plan.previous.resolve(PORTABLE_DATA_DIR_NAME)
    val newData = plan.staging.resolve(PORTABLE_DATA_DIR_NAME)
    move(plan.destination, plan.previous)
    try {
        move(oldData, newData)
    } catch (e: IOException) {
        throw undone(e) { move(plan.previous, plan.destination) }
    }
    try {
        move(plan.staging, plan.destination)
    } catch (e: IOException) {
        throw undone(e) {
            move(newData, oldData)
            move(plan.previous, plan.destination)
        }
    }
    syncDirectory(plan.destination.parent)
}

/**
 * A rename in one step, retried while a scanner holds the fresh copy (see [RetryPolicy.INSTALL]).
 * An existing target fails at once: a rename would silently replace an empty folder, and a full
 * one would only be retried.
 */
@Throws(IOException::class)
internal fun moveForInstall(
    from: Path,
    to: Path,
) {
    if (Files.exists(to, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(to.toString())
    moveWithRetry(from, to, ATOMIC_MOVE, policy = RetryPolicy.INSTALL)
}

/**
 * Runs the [steps] that undo a failed swap and returns what to throw: [failure] once undone, or
 * [FailureReason.UpdateLeftover] when the data could not be put back and is now aside.
 */
private inline fun undone(
    failure: IOException,
    steps: () -> Unit,
): IOException =
    try {
        steps()
        failure
    } catch (e: IOException) {
        failure.addSuppressed(e)
        errorln(e) { "[portable-install] could not undo a failed update; the data is in $STAGING_SUFFIX or $PREVIOUS_SUFFIX" }
        PortableInstallException(FailureReason.UpdateLeftover, failure)
    }

/**
 * Deletes a staging folder. With [protectData], one that holds a data folder is kept: outside a
 * fresh copy that happens only when undoing a failed update failed too, and then the user's
 * notes are in it.
 */
internal fun discardStaging(
    staging: Path,
    protectData: Boolean = true,
) {
    if (protectData && mayHoldData(staging)) {
        errorln { "[portable-install] keeping $STAGING_SUFFIX folder: it holds the data of a portable copy" }
        return
    }
    if (!deleteTree(staging)) warnln { "[portable-install] an interrupted copy could not be fully removed" }
}

/**
 * Removes what an interrupted copy left, before a new one starts. A leftover that still holds a
 * user's data folder (see [discardStaging]) stops the copy instead, before anything is written.
 */
internal fun clearStaging(staging: Path) {
    discardStaging(staging)
    if (Files.exists(staging, NOFOLLOW_LINKS)) throw PortableInstallException(FailureReason.UpdateLeftover)
}

/**
 * Removes the previous program a finished update could not delete, before a new update. One that
 * may still hold a data folder (see [mayHoldData]) stops this update instead.
 */
internal fun clearPrevious(previous: Path) {
    if (mayHoldData(previous) || !deleteTree(previous) || Files.exists(previous, NOFOLLOW_LINKS)) {
        throw PortableInstallException(FailureReason.UpdateLeftover)
    }
}

/**
 * Runs [block], which moves the portable copy in [copy], unless that copy runs, on this computer or
 * another one. The drive lock is held meanwhile, so the copy cannot start in between; except on
 * Windows, which cannot rename a folder while a file in it is open, and refuses to rename a running
 * copy by itself.
 */
internal inline fun <T> whileNotRunning(
    copy: Path,
    block: () -> T,
): T {
    val lock =
        when (val result = DriveLock.tryAcquire(copy.resolve(PORTABLE_DATA_DIR_NAME))) {
            is DriveLock.Result.Acquired -> result.lock
            DriveLock.Result.InUse -> throw PortableInstallException(FailureReason.DriveInUse)
            // A drive without locks: only Windows can tell, by refusing the rename.
            is DriveLock.Result.Unavailable -> null
        }
    if (PlatformInfo.isWindows) lock?.close()
    return lock.use { block() }
}

/** Removes the previous program once the copy's data is out of it; kept if it may still hold data. */
internal fun removePrevious(previous: Path) {
    if (mayHoldData(previous) || !deleteTree(previous)) warnln { "[portable-install] the previous program could not be fully removed" }
}

/**
 * Whether [folder] may hold a copy's data, for the checks that guard a deletion: anything with the
 * data folder's name counts, and so does a drive error that leaves the answer unknown.
 */
internal fun mayHoldData(folder: Path): Boolean = !Files.notExists(folder.resolve(PORTABLE_DATA_DIR_NAME), NOFOLLOW_LINKS)

/** A portable copy: real folders (see [isRealDirectory]) with the marker that makes it start in portable mode. */
internal fun isPortableCopy(folder: Path): Boolean {
    val dataDir = folder.resolve(PORTABLE_DATA_DIR_NAME)
    return isRealDirectory(folder) && isRealDirectory(dataDir) && Files.isRegularFile(dataDir.resolve(PORTABLE_MARKER_NAME), NOFOLLOW_LINKS)
}

/** A folder that is not a link, nor a Windows junction, which reads as a folder too. */
private fun isRealDirectory(path: Path): Boolean =
    try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther
    } catch (_: IOException) {
        false
    }

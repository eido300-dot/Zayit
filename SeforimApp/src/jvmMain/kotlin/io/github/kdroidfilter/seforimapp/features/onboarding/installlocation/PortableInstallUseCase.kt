package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.features.onboarding.diskspace.AvailableDiskSpaceUseCase
import io.github.kdroidfilter.seforimapp.framework.platform.currentExecutablePath
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_DATA_DIR_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.deleteTree
import io.github.kdroidfilter.seforimapp.framework.portable.isReallyInside
import io.github.kdroidfilter.seforimapp.framework.portable.syncDirectory
import io.github.kdroidfilter.seforimapp.framework.portable.treeSize
import io.github.kdroidfilter.seforimapp.logger.warnln
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Copies the running program to a folder the user picks, as a portable copy that keeps all its
 * data and settings next to itself. Only the program is copied: the copy downloads the library
 * into its own data folder on first start, through the usual onboarding.
 *
 * Every copy is built in a `Zayit.partial` folder and renamed to `Zayit` in one atomic step once
 * complete, so a copy interrupted at any point (cancelled, failed, drive pulled) never looks like
 * a finished one. The data folder of an existing copy is never deleted, only moved.
 */
class PortableInstallUseCase(
    private val executable: () -> Path? = ::currentExecutablePath,
    private val copyFile: (Path, Path) -> Unit = ::copyFileDurably,
    private val canWrite: (Path) -> Boolean = ::probeWritable,
    private val isExecutable: (Path) -> Boolean = Files::isExecutable,
    private val move: (Path, Path) -> Unit = ::moveForInstall,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Checks [targetParent] without changing anything; never throws for a file error. */
    suspend fun check(targetParent: Path): TargetCheck =
        withContext(ioDispatcher) {
            try {
                checkBlocking(targetParent)
            } catch (e: IOException) {
                warnln(e) { "[portable-install] cannot check the chosen folder" }
                TargetCheck.Unavailable
            }
        }

    /** Removes the folder an interrupted copy left in [targetParent]; true when it is gone. */
    suspend fun discardStalePartial(targetParent: Path): Boolean =
        mutex.withLock {
            withContext(ioDispatcher) {
                val staging = targetParent.resolve(PORTABLE_FOLDER_NAME + STAGING_SUFFIX)
                discardStaging(staging)
                !Files.exists(staging, NOFOLLOW_LINKS)
            }
        }

    /**
     * Creates a new portable copy in [targetParent] and returns its folder. [onProgress] gets the
     * number of copied entries and the total, on the IO thread.
     *
     * @throws PortableInstallException when the copy did not complete; nothing is left behind.
     */
    suspend fun install(
        targetParent: Path,
        onProgress: (copied: Int, total: Int) -> Unit,
    ): String =
        mutex.withLock {
            withContext(ioDispatcher) {
                val plan = requirePlan(targetParent)
                if (Files.exists(plan.destination, NOFOLLOW_LINKS)) throw PortableInstallException(FailureReason.AlreadyExists)
                clearStaging(plan.staging)
                var committed = false
                try {
                    asInstallFailure {
                        stageProgram(plan, onProgress)
                        createDataFolder(plan.staging)
                        move(plan.staging, plan.destination)
                        syncDirectory(targetParent)
                    }
                    committed = true
                } finally {
                    // The data folder in there is the new, empty one this copy created.
                    if (!committed) withContext(NonCancellable) { discardStaging(plan.staging, protectData = false) }
                }
                plan.destination.toString()
            }
        }

    /**
     * Replaces the program of the portable copy in [targetParent] with the running one, keeping its
     * data folder (library, notes, settings), and returns the copy's folder.
     *
     * The new program is built next to the old one first; the swap is then three renames, each
     * undone if a later one fails. If even that fails, the previous program stays in `Zayit.old`
     * with its data, and nothing is deleted.
     *
     * @throws PortableInstallException when the update did not complete.
     */
    suspend fun updateProgram(
        targetParent: Path,
        onProgress: (copied: Int, total: Int) -> Unit,
    ): String =
        mutex.withLock {
            withContext(ioDispatcher) {
                val plan = requirePlan(targetParent)
                requireUpdatable(plan)
                clearStaging(plan.staging)
                var committed = false
                try {
                    asInstallFailure {
                        stageProgram(plan, onProgress)
                        swapProgram(plan, move)
                    }
                    committed = true
                } finally {
                    if (!committed) withContext(NonCancellable) { discardStaging(plan.staging) }
                }
                if (!deleteTree(plan.previous)) warnln { "[portable-install] the previous program could not be fully removed" }
                plan.destination.toString()
            }
        }

    private fun checkBlocking(targetParent: Path): TargetCheck {
        val plan = executable()?.let { planInstall(it, targetParent) }
        if (plan == null || !Files.isDirectory(targetParent)) return TargetCheck.Unavailable
        val store = Files.getFileStore(targetParent)
        return locationProblem(plan, targetParent, store.type())
            ?: existingCopy(plan)
            ?: writeAndSpace(plan, targetParent, store)
    }

    private fun locationProblem(
        plan: InstallPlan,
        targetParent: Path,
        type: String,
    ): TargetCheck? =
        when {
            isReallyInside(targetParent, plan.source) -> TargetCheck.InsideProgramDir
            isPathTooLong(targetParent.toString(), targetParent.fileSystem.separator) -> TargetCheck.PathTooLong
            !isFileSystemSupported(type, targetParent.toString()) -> TargetCheck.UnsupportedFileSystem(type)
            else -> null
        }

    private fun existingCopy(plan: InstallPlan): TargetCheck? =
        when {
            isPortableCopy(plan.destination) -> TargetCheck.ExistingPortable(plan.destination.toString())
            Files.exists(plan.destination, NOFOLLOW_LINKS) -> TargetCheck.AlreadyExists(plan.destination.toString())
            Files.exists(plan.staging, NOFOLLOW_LINKS) -> TargetCheck.StalePartial(plan.staging.toString())
            else -> null
        }

    private fun writeAndSpace(
        plan: InstallPlan,
        targetParent: Path,
        store: FileStore,
    ): TargetCheck {
        if (!canWrite(targetParent)) return TargetCheck.NotWritable
        val required = treeSize(plan.source) + AvailableDiskSpaceUseCase.REQUIRED_SPACE_BYTES
        val free = store.usableSpace
        return if (free < required) TargetCheck.NotEnoughSpace(free, required) else TargetCheck.Ok(free, required)
    }

    private fun requirePlan(targetParent: Path): InstallPlan =
        executable()?.let { planInstall(it, targetParent) } ?: throw PortableInstallException(FailureReason.Unavailable)

    /** Refuses to touch a copy that is not one, that is running, or that a failed update left aside. */
    private fun requireUpdatable(plan: InstallPlan) {
        val refusal =
            when {
                !isPortableCopy(plan.destination) -> FailureReason.Unavailable
                Files.exists(plan.previous, NOFOLLOW_LINKS) -> FailureReason.UpdateLeftover
                else ->
                    when (val lock = DriveLock.tryAcquire(plan.destination.resolve(PORTABLE_DATA_DIR_NAME))) {
                        // Released at once: Windows cannot rename a folder while a file in it is open.
                        is DriveLock.Result.Acquired -> {
                            lock.lock.close()
                            null
                        }
                        DriveLock.Result.InUse -> FailureReason.DriveInUse
                        // A drive without locks: the rename below fails on its own if the copy is running.
                        is DriveLock.Result.Unavailable -> null
                    }
            }
        if (refusal != null) throw PortableInstallException(refusal)
    }

    /** Copies the program into a fresh staging folder and checks that it can run from there. */
    private suspend fun stageProgram(
        plan: InstallPlan,
        onProgress: (copied: Int, total: Int) -> Unit,
    ) {
        val context = currentCoroutineContext()
        Files.createDirectories(plan.copyRoot)
        val entries = listProgramEntries(plan.source)
        copyProgramEntries(entries, plan.source, plan.copyRoot, context, copyFile, onProgress)
        if (!isExecutable(plan.staging.resolve(plan.relativeExecutable.toString()))) {
            throw PortableInstallException(FailureReason.NotExecutable)
        }
    }

    private inline fun <T> asInstallFailure(block: () -> T): T =
        try {
            block()
        } catch (e: PortableInstallException) {
            throw e
        } catch (e: IOException) {
            throw PortableInstallException(FailureReason.CopyFailed, e)
        }
}

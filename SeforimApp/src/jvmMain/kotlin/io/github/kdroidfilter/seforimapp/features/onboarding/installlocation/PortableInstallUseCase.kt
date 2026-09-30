package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.features.onboarding.diskspace.AvailableDiskSpaceUseCase
import io.github.kdroidfilter.seforimapp.framework.platform.currentExecutablePath
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
import java.nio.file.FileAlreadyExistsException
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
    private val copyFile: (from: Path, to: Path, onCopied: (bytes: Long) -> Unit) -> Unit = ::copyFileDurably,
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
     * work done and the total (see [copyProgramEntries]), on the IO thread.
     *
     * @throws PortableInstallException when the copy did not complete; nothing is left behind.
     */
    suspend fun install(
        targetParent: Path,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): String =
        mutex.withLock {
            withContext(ioDispatcher) {
                val plan = requirePlan(targetParent)
                if (Files.exists(plan.destination, NOFOLLOW_LINKS)) throw PortableInstallException(FailureReason.AlreadyExists)
                // A new Zayit would stand in the way of putting back a copy whose data may be aside.
                if (mayHoldData(plan.staging) || mayHoldData(plan.previous)) throw PortableInstallException(FailureReason.UpdateLeftover)
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
        onProgress: (copied: Long, total: Long) -> Unit,
    ): String =
        mutex.withLock {
            withContext(ioDispatcher) {
                val plan = requirePlan(targetParent)
                if (!isPortableCopy(plan.destination)) throw PortableInstallException(FailureReason.Unavailable)
                // Asked now too, before the long copy.
                whileNotRunning(plan.destination) {}
                clearPrevious(plan.previous)
                clearStaging(plan.staging)
                var committed = false
                try {
                    asInstallFailure {
                        stageProgram(plan, onProgress)
                        // Again: the copy may have been started from the drive while this one was staged.
                        whileNotRunning(plan.destination) { swapProgram(plan, move) }
                    }
                    committed = true
                } finally {
                    if (!committed) withContext(NonCancellable) { discardStaging(plan.staging) }
                }
                removePrevious(plan.previous)
                plan.destination.toString()
            }
        }

    /**
     * Puts back the portable copy an interrupted update left aside (see
     * [TargetCheck.InterruptedUpdate]) and returns its folder. From `Zayit.partial` the update is
     * finished: its program was complete before the data moved in. From `Zayit.old` the update is
     * undone. The program left without data is then removed.
     *
     * @throws PortableInstallException when there is nothing to put back, or `Zayit` is in the way.
     */
    suspend fun recoverInterruptedUpdate(targetParent: Path): String =
        mutex.withLock {
            withContext(ioDispatcher) {
                val plan = requirePlan(targetParent)
                val leftover = interruptedUpdateOf(plan)
                if (leftover == null || Files.exists(plan.destination, NOFOLLOW_LINKS)) {
                    throw PortableInstallException(FailureReason.UpdateLeftover)
                }
                // The copy left aside can be started from there, and must not be moved while it runs.
                whileNotRunning(leftover) { asInstallFailure { move(leftover, plan.destination) } }
                syncDirectory(targetParent)
                discardStaging(plan.staging)
                removePrevious(plan.previous)
                plan.destination.toString()
            }
        }

    private fun checkBlocking(targetParent: Path): TargetCheck {
        val plan = executable()?.let { planInstall(it, targetParent) }
        if (plan == null || !Files.isDirectory(targetParent)) return TargetCheck.Unavailable
        val store = Files.getFileStore(targetParent)
        return when (val found = locationProblem(plan, targetParent, store.type()) ?: existingCopy(plan)) {
            null -> writeAndSpace(plan, targetParent, store, isUpdate = false)
            // An update needs room and write access too, for the new program next to the old one.
            is TargetCheck.ExistingPortable -> {
                val room = writeAndSpace(plan, targetParent, store, isUpdate = true)
                if (room is TargetCheck.Ok) found else room
            }
            // Offered only where the renames or the removal it takes can work; the data stays reported.
            is TargetCheck.InterruptedUpdate -> if (canWrite(targetParent)) found else found.copy(isWritable = false)
            is TargetCheck.StalePartial -> if (canWrite(targetParent)) found else TargetCheck.NotWritable
            else -> found
        }
    }

    private fun locationProblem(
        plan: InstallPlan,
        targetParent: Path,
        type: String,
    ): TargetCheck? =
        when {
            isReallyInside(targetParent, plan.source) -> TargetCheck.InsideProgramDir
            isPathTooLong(targetParent.toString(), targetParent.fileSystem.separator) -> TargetCheck.PathTooLong
            // The real path: a link or junction to a network share reports the remote disk's type.
            !isFileSystemSupported(type, targetParent.toRealPath().toString()) -> TargetCheck.UnsupportedFileSystem(type)
            else -> null
        }

    private fun existingCopy(plan: InstallPlan): TargetCheck? {
        val interrupted = interruptedUpdateOf(plan)
        val hasDestination = Files.exists(plan.destination, NOFOLLOW_LINKS)
        // What may hold data but cannot be put back, and so blocks every step here (see mayHoldData).
        val inTheWay =
            if (interrupted != null) {
                interrupted.takeIf { hasDestination }
            } else {
                listOf(plan.staging, plan.previous).firstOrNull(::mayHoldData)
            }
        return when {
            inTheWay != null -> TargetCheck.LeftoverInTheWay(inTheWay.toString())
            interrupted != null -> TargetCheck.InterruptedUpdate(interrupted.toString())
            isPortableCopy(plan.destination) -> TargetCheck.ExistingPortable(plan.destination.toString())
            hasDestination -> TargetCheck.AlreadyExists(plan.destination.toString())
            Files.exists(plan.staging, NOFOLLOW_LINKS) -> TargetCheck.StalePartial(plan.staging.toString())
            else -> null
        }
    }

    /**
     * The folder an interrupted update left the copy's data in, if any: a whole portable copy. Two
     * of them are not one update's leftover, and which holds the user's data is unclear.
     */
    private fun interruptedUpdateOf(plan: InstallPlan): Path? = listOf(plan.staging, plan.previous).filter(::isPortableCopy).singleOrNull()

    /** Room for the program, plus the library for a new copy; an update keeps the library it has. */
    private fun writeAndSpace(
        plan: InstallPlan,
        targetParent: Path,
        store: FileStore,
        isUpdate: Boolean,
    ): TargetCheck {
        if (!canWrite(targetParent)) return TargetCheck.NotWritable
        val library = if (isUpdate) 0 else AvailableDiskSpaceUseCase.REQUIRED_SPACE_BYTES
        val required = treeSize(plan.source) + library
        val free = store.usableSpace
        return if (free < required) TargetCheck.NotEnoughSpace(free, required) else TargetCheck.Ok(free, required)
    }

    private fun requirePlan(targetParent: Path): InstallPlan =
        executable()?.let { planInstall(it, targetParent) } ?: throw PortableInstallException(FailureReason.Unavailable)

    /** Copies the program into a fresh staging folder and checks that it can run from there. */
    private suspend fun stageProgram(
        plan: InstallPlan,
        onProgress: (copied: Long, total: Long) -> Unit,
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
        } catch (e: FileAlreadyExistsException) {
            // Only the final rename can meet an existing folder: a Zayit created meanwhile.
            throw PortableInstallException(FailureReason.AlreadyExists, e)
        } catch (e: IOException) {
            throw PortableInstallException(FailureReason.CopyFailed, e)
        }
}

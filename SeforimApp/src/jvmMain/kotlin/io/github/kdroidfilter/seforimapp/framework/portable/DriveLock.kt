package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

/** File in the data dir that a running portable copy keeps locked. */
const val DRIVE_LOCK_NAME = ".lock"

/**
 * A lock on the drive itself, held for the life of the process.
 *
 * The single-instance lock of Nucleus lives in the host's temp folder, keyed by the path text, so
 * the same data folder can still be opened twice: from two computers on a network share, or on one
 * computer through two paths (a mapped letter and a UNC path, `subst`). Two processes would then
 * write the same user database and settings file. A lock in the data folder catches all of these.
 */
class DriveLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val key: Path,
) : Closeable {
    /** Releases the lock; a second call does nothing. */
    override fun close() {
        if (!channel.isOpen) return
        try {
            lock.release()
        } finally {
            channel.close()
            heldKeys.remove(key)
        }
    }

    /** The result of [tryAcquire]. */
    sealed interface Result {
        data class Acquired(
            val lock: DriveLock,
        ) : Result

        /** Another process, on this computer or another one, holds the data folder. */
        data object InUse : Result

        /**
         * The lock could not be taken for another reason (a read-only drive, a file system without
         * locks). The app still starts: blocking it would leave the user with no way in.
         */
        data class Unavailable(
            val cause: IOException,
        ) : Result
    }

    companion object {
        /** Written next to the lock by a copy that is about to restart itself; see [noteRestart]. */
        const val RESTART_NOTE_NAME = ".restarting"

        /** How long a restarted copy waits for the old one to let go of the drive. */
        private const val RESTART_WAIT_MILLIS = 10_000L
        private const val RESTART_POLL_MILLIS = 200L

        /** A restart note older than this is left over from a crash and ignored. */
        private const val RESTART_NOTE_MAX_AGE_MILLIS = 60_000L

        /**
         * The data folders this process holds a lock on. Closing any channel on a file drops every
         * lock this process holds on it (POSIX `fcntl`), so a second attempt must be refused before a
         * channel is opened: the second channel's `close()` would silently release the first lock.
         */
        private val heldKeys: MutableSet<Path> = ConcurrentHashMap.newKeySet()

        /** The lock this process holds, kept referenced for the life of the process so it is never collected. */
        @Volatile
        private var held: DriveLock? = null

        /**
         * Takes the drive for this process and keeps it until exit. When the previous process wrote a
         * restart note, it may still be shutting down: then this waits up to 10 seconds (of real
         * time, however slow a poll turns out on a drive that answers late) for it.
         */
        fun acquireForProcess(
            dataDir: Path,
            sleeper: (Long) -> Unit = Thread::sleep,
            now: () -> Long = System::currentTimeMillis,
        ): Result {
            var result = tryAcquire(dataDir)
            if (result is Result.InUse && isRestarting(dataDir, now())) {
                val deadline = now() + RESTART_WAIT_MILLIS
                while (result is Result.InUse && now() < deadline) {
                    sleeper(RESTART_POLL_MILLIS)
                    result = tryAcquire(dataDir)
                }
            }
            if (result is Result.Acquired) {
                deleteQuietly(dataDir.resolve(RESTART_NOTE_NAME))
                // Kept referenced and never closed: the operating system releases the lock only once
                // the process is gone, after every shutdown hook (settings flush, the single-instance
                // lock) has run. A hook here would hand the drive over while those still write.
                held = result.lock
            }
            return result
        }

        /**
         * Tells the copy about to be started that this one is only restarting: it then waits for the
         * drive instead of reporting it in use. The lock is not released here. It is held until this
         * process is gone, so the new copy never reads or writes the data while this one still
         * flushes its last changes, and a restart that does not happen leaves the drive locked.
         */
        fun noteRestart(dataDir: Path) {
            try {
                // One byte, not none: truncating an empty file may leave its modified time unchanged.
                Files.write(dataDir.resolve(RESTART_NOTE_NAME), byteArrayOf(1), CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS)
            } catch (e: IOException) {
                warnln(e) { "[portable] cannot leave a restart note" }
            }
        }

        /** Removes the note after a restart that did not happen, so no later start waits for it. */
        fun forgetRestart(dataDir: Path) = deleteQuietly(dataDir.resolve(RESTART_NOTE_NAME))

        private fun isRestarting(
            dataDir: Path,
            now: Long,
        ): Boolean =
            try {
                val note = dataDir.resolve(RESTART_NOTE_NAME)
                Files.isRegularFile(note, NOFOLLOW_LINKS) &&
                    now - Files.getLastModifiedTime(note).toMillis() < RESTART_NOTE_MAX_AGE_MILLIS
            } catch (_: IOException) {
                false
            }

        private fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // A stale note only makes a later start wait if the drive is really in use.
            }
        }

        /**
         * Tries once, without waiting. Never throws. The lock file is never opened through a link, and
         * only when it is a regular file or absent: opening a pipe planted under that name would block
         * for good, before any window exists.
         */
        fun tryAcquire(dataDir: Path): Result {
            val lockFile = dataDir.resolve(DRIVE_LOCK_NAME)
            val notRegular = Files.exists(lockFile, NOFOLLOW_LINKS) && !Files.isRegularFile(lockFile, NOFOLLOW_LINKS)
            if (notRegular) {
                return Result.Unavailable(IOException("$DRIVE_LOCK_NAME is not a regular file"))
            }
            val key = dataDir.toAbsolutePath().normalize()
            // Held by this same process already: answer without opening a second channel.
            if (!heldKeys.add(key)) return Result.InUse
            val result = openAndLock(lockFile, key)
            if (result !is Result.Acquired) heldKeys.remove(key)
            return result
        }

        private fun openAndLock(
            lockFile: Path,
            key: Path,
        ): Result {
            val channel =
                try {
                    FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    warnln(e) { "[portable] cannot open the drive lock file" }
                    return Result.Unavailable(e)
                }
            val lock =
                try {
                    channel.tryLock()
                } catch (e: IOException) {
                    channel.close()
                    warnln(e) { "[portable] cannot lock the drive" }
                    return Result.Unavailable(e)
                }
            if (lock == null) {
                channel.close()
                return Result.InUse
            }
            return Result.Acquired(DriveLock(channel, lock, key))
        }
    }
}

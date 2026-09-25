package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
) : Closeable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
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
        /** Tries once, without waiting. Never throws. */
        fun tryAcquire(dataDir: Path): Result {
            val channel =
                try {
                    FileChannel.open(dataDir.resolve(DRIVE_LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                } catch (e: IOException) {
                    warnln(e) { "[portable] cannot open the drive lock file" }
                    return Result.Unavailable(e)
                }
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    // Held by this same process through another channel.
                    null
                } catch (e: IOException) {
                    channel.close()
                    warnln(e) { "[portable] cannot lock the drive" }
                    return Result.Unavailable(e)
                }
            if (lock == null) {
                channel.close()
                return Result.InUse
            }
            return Result.Acquired(DriveLock(channel, lock))
        }
    }
}

package io.github.kdroidfilter.seforimapp.framework.portable

import java.nio.file.Path

/** Run in a child JVM by [DriveLockTest]: holds the drive lock until its input closes. */
object DriveLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = DriveLock.tryAcquire(Path.of(args.single()))
        println(if (result is DriveLock.Result.Acquired) "locked" else "failed")
        System.`in`.read()
    }
}

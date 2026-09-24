package io.github.kdroidfilter.seforimapp.framework.io

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [target] through a sibling temp file that is synced and then moved over [target].
 *
 * A crash, kill or full disk mid-write leaves the previous [target] (or nothing) in place,
 * never a truncated file. If [write] throws, the temp file is deleted and [target] is untouched.
 */
internal fun <T> File.writeAtomically(write: (FileOutputStream) -> T): T {
    val dir = absoluteFile.parentFile ?: error("No parent directory for $this")
    dir.mkdirs()
    val tmp = File(dir, "$name.tmp")
    try {
        val result =
            FileOutputStream(tmp).use { out ->
                write(out).also {
                    out.flush()
                    out.fd.sync()
                }
            }
        tmp.moveOver(this)
        return result
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}

/** Moves this file over [target], atomically where the file system supports it. */
internal fun File.moveOver(target: File) {
    try {
        Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

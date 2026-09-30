package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_DATA_DIR_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.forceToDisk
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext

/**
 * Lists what a copy of the program folder [source] takes along, parents before children. Links
 * are listed as links and never entered. Left out: the data folder of a copy that is itself
 * portable, and the uninstaller at the top ([isUninstaller]). Anything that is neither a file, a
 * folder nor a link (a Windows junction) fails the copy rather than being silently dropped.
 */
@Throws(IOException::class)
internal fun listProgramEntries(source: Path): List<ProgramEntry> {
    val entries = mutableListOf<ProgramEntry>()
    val skippedData = source.resolve(PORTABLE_DATA_DIR_NAME)
    Files.walkFileTree(
        source,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (dir == skippedData) return FileVisitResult.SKIP_SUBTREE
                if (dir != source) entries += ProgramEntry(source.relativize(dir), ProgramEntry.Kind.Directory)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                val relative = source.relativize(file)
                val isTopLevelUninstaller = relative.nameCount == 1 && isUninstaller(relative.toString())
                when {
                    isTopLevelUninstaller -> Unit
                    attrs.isSymbolicLink -> entries += ProgramEntry(relative, ProgramEntry.Kind.Link)
                    attrs.isRegularFile -> entries += ProgramEntry(relative, ProgramEntry.Kind.File)
                    else -> throw PortableInstallException(FailureReason.CopyFailed, IOException("unsupported entry: $relative"))
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
    return entries
}

/** One entry of the program folder, relative to it, in the order it must be created. */
internal data class ProgramEntry(
    val relative: Path,
    val kind: Kind,
) {
    enum class Kind { Directory, File, Link }
}

/**
 * Copies [entries] from [source] into [target], which must exist, checking [context] for
 * cancellation between entries and reporting each finished one to [onProgress].
 */
@Throws(IOException::class)
internal fun copyProgramEntries(
    entries: List<ProgramEntry>,
    source: Path,
    target: Path,
    context: CoroutineContext,
    copyFile: (Path, Path) -> Unit,
    onProgress: (copied: Int, total: Int) -> Unit,
) {
    entries.forEachIndexed { index, entry ->
        context.ensureActive()
        val from = source.resolve(entry.relative.toString())
        val to = target.resolve(entry.relative.toString())
        when (entry.kind) {
            ProgramEntry.Kind.Directory -> Files.createDirectory(to)
            ProgramEntry.Kind.File -> copyFile(from, to)
            ProgramEntry.Kind.Link -> copyLink(from, to)
        }
        onProgress(index + 1, entries.size)
    }
}

/** Links are recreated as they are, which a drive that does not support them (exFAT) refuses. */
private fun copyLink(
    from: Path,
    to: Path,
) {
    try {
        Files.createSymbolicLink(to, Files.readSymbolicLink(from))
    } catch (e: UnsupportedOperationException) {
        throw PortableInstallException(FailureReason.CopyFailed, e)
    }
}

/**
 * Copies one file and flushes it to the device. File attributes are not copied: the new file gets
 * the source's permission bits (so the program stays executable) but no timestamps, owner or
 * macOS quarantine flag.
 */
@Throws(IOException::class)
internal fun copyFileDurably(
    from: Path,
    to: Path,
) {
    Files.copy(from, to, NOFOLLOW_LINKS)
    try {
        RandomAccessFile(to.toFile(), "rw").use { forceToDisk(it) }
    } catch (_: FileNotFoundException) {
        // A read-only file cannot be opened for writing: flush it through a read handle, which
        // Linux and macOS accept. Windows refuses that too, and its own write-back still applies.
        try {
            FileChannel.open(to, READ).use { it.force(true) }
        } catch (_: IOException) {
            // Best effort, as explained above.
        }
    }
}

/** Whether a file can really be created in [dir]; permission bits alone say little on a drive. */
internal fun probeWritable(dir: Path): Boolean =
    try {
        Files.delete(Files.createTempFile(dir, ".zayit-probe", null))
        true
    } catch (_: IOException) {
        false
    }

package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.framework.platform.PlatformInfo
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_DATA_DIR_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.forceToDisk
import io.github.kdroidfilter.seforimapp.logger.warnln
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** How much of a file is copied between two checks for cancellation and two progress reports. */
private const val COPY_CHUNK_BYTES = 8L * 1024 * 1024

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
                if (dir == source) return FileVisitResult.CONTINUE
                // A Windows junction reads as a folder too; entering it would copy what it points to.
                if (attrs.isOther) throw unsupportedEntry(source.relativize(dir))
                entries += ProgramEntry(source.relativize(dir), ProgramEntry.Kind.Directory)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                // The data folder of a portable program, even as a link or a file: the copy makes its own.
                if (file == skippedData) return FileVisitResult.CONTINUE
                val relative = source.relativize(file)
                val isTopLevelUninstaller = relative.nameCount == 1 && isUninstaller(relative.toString())
                when {
                    isTopLevelUninstaller -> Unit
                    attrs.isSymbolicLink -> entries += ProgramEntry(relative, ProgramEntry.Kind.Link)
                    attrs.isRegularFile -> entries += ProgramEntry(relative, ProgramEntry.Kind.File, attrs.size())
                    else -> throw unsupportedEntry(relative)
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
    return entries
}

private fun unsupportedEntry(relative: Path): PortableInstallException =
    PortableInstallException(FailureReason.CopyFailed, IOException("unsupported entry: $relative"))

/**
 * One entry of the program folder, relative to it, in the order it must be created.
 *
 * @property size the bytes to copy: the file's size when listed, 0 for folders and links.
 */
internal data class ProgramEntry(
    val relative: Path,
    val kind: Kind,
    val size: Long = 0,
) {
    enum class Kind { Directory, File, Link }
}

/**
 * Copies [entries] from [source] into [target], which must exist, checking [context] for
 * cancellation between entries and between the chunks of a file (the program is mostly one large
 * native binary). [onProgress] gets the work done and the total: the bytes, plus one for each
 * entry so that folders and empty files count too.
 */
@Throws(IOException::class)
internal fun copyProgramEntries(
    entries: List<ProgramEntry>,
    source: Path,
    target: Path,
    context: CoroutineContext,
    copyFile: (from: Path, to: Path, onCopied: (bytes: Long) -> Unit) -> Unit,
    onProgress: (copied: Long, total: Long) -> Unit,
) {
    val total = entries.sumOf { it.size + 1 }
    var copied = 0L
    val onCopied = { bytes: Long ->
        context.ensureActive()
        copied += bytes
        onProgress(copied, total)
    }
    entries.forEach { entry ->
        context.ensureActive()
        val from = source.resolve(entry.relative.toString())
        val to = target.resolve(entry.relative.toString())
        when (entry.kind) {
            ProgramEntry.Kind.Directory -> Files.createDirectory(to)
            ProgramEntry.Kind.File -> copyFile(from, to, onCopied)
            ProgramEntry.Kind.Link -> copyLink(from, to)
        }
        onCopied(1)
    }
}

/**
 * Links are recreated as they are. A drive that cannot hold them (exFAT, or Windows without the
 * privilege) refuses with a bare file-system or access error, which retrying would not change.
 */
private fun copyLink(
    from: Path,
    to: Path,
) {
    val target = Files.readSymbolicLink(from)
    try {
        Files.createSymbolicLink(to, target)
    } catch (e: UnsupportedOperationException) {
        throw PortableInstallException(FailureReason.LinksUnsupported, e)
    } catch (e: FileSystemException) {
        val refused = e is AccessDeniedException || e.javaClass == FileSystemException::class.java
        throw if (refused) PortableInstallException(FailureReason.LinksUnsupported, e) else e
    }
}

/**
 * Copies one file in chunks, each flushed to the device before it is reported to [onCopied] (which
 * may throw to stop the copy). Without that, a copy to a slow drive would only fill the cache: the
 * progress would run ahead of the drive and a cancel would wait for one long flush at the end.
 *
 * File attributes are not copied: the new file gets the source's permission bits (so the program
 * stays executable) but no timestamps, owner or macOS quarantine flag, as with `Files.copy` without
 * `COPY_ATTRIBUTES`.
 */
@Throws(IOException::class)
internal fun copyFileDurably(
    from: Path,
    to: Path,
    onCopied: (bytes: Long) -> Unit,
) = copyFileDurably(from, to, DeviceFlush.forDrive(to.parent), onCopied)

/** [copyFileDurably] with the given way to flush, for tests. */
@Throws(IOException::class)
internal fun copyFileDurably(
    from: Path,
    to: Path,
    flush: DeviceFlush,
    onCopied: (bytes: Long) -> Unit,
) {
    FileChannel.open(from, READ, NOFOLLOW_LINKS).use { input ->
        FileChannel.open(to, setOf(CREATE_NEW, WRITE), *permissionsOf(from)).use { output ->
            flush.open(output, to).use { fileFlush -> copyChunks(input, output, from, fileFlush, onCopied) }
        }
    }
}

private fun copyChunks(
    input: FileChannel,
    output: FileChannel,
    from: Path,
    fileFlush: FileFlush,
    onCopied: (bytes: Long) -> Unit,
) {
    val size = input.size()
    var position = 0L
    while (position < size) {
        val copied = input.transferTo(position, minOf(COPY_CHUNK_BYTES, size - position), output)
        if (copied <= 0) throw IOException("$from got shorter while it was copied")
        position += copied
        // The last chunk takes the file's metadata along: one flush per chunk.
        fileFlush.flush(metadata = position == size)
        onCopied(copied)
    }
    if (size == 0L) fileFlush.flush(metadata = true)
}

/**
 * How the files written on one drive are flushed. A file is flushed through the channel that wrote
 * it: a failure is the drive's write error, which Linux reports only once per file, so it is never
 * retried through another handle.
 *
 * On macOS `force` asks for a full flush (`F_FULLFSYNC`), which some file systems refuse, as
 * [forceToDisk] allows. Whether a drive does is found once, on a scratch file ([forDrive]), so
 * that a refusal is never confused with a write error; such a drive gets a plain `fsync` instead.
 */
internal class DeviceFlush(
    private val fullFlushRefused: Boolean = false,
    private val force: (FileChannel, metadata: Boolean) -> Unit = FileChannel::force,
) {
    /** Starts flushing [file], just created and written through [output]. */
    @Throws(IOException::class)
    fun open(
        output: FileChannel,
        file: Path,
    ): FileFlush {
        if (!fullFlushRefused) return FileFlush(output, syncHandle = null, force)
        // `fsync` needs a second handle, opened once and never through a link put in the file's place.
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("$file was replaced while it was copied")
        return FileFlush(output, FileInputStream(file.toFile()), force)
    }

    companion object {
        /** Per drive, whether it refuses a full flush; only asked on macOS. */
        private val refusingDrives = ConcurrentHashMap<FileStore, Boolean>()

        /** The flush for files written in [dir]. */
        @Throws(IOException::class)
        fun forDrive(dir: Path): DeviceFlush {
            if (!PlatformInfo.isMacOS) return DeviceFlush()
            return DeviceFlush(fullFlushRefused = refusingDrives.getOrPut(Files.getFileStore(dir)) { refusesFullFlush(dir) })
        }

        private fun refusesFullFlush(dir: Path): Boolean {
            val probe = Files.createTempFile(dir, ".zayit-flush", null)
            return try {
                FileChannel.open(probe, WRITE).use { it.force(true) }
                false
            } catch (e: IOException) {
                warnln(e) { "[portable-install] this drive refuses a full flush, using fsync" }
                true
            } finally {
                Files.deleteIfExists(probe)
            }
        }
    }
}

/** Flushes one file (see [DeviceFlush]): through its [output], or through [syncHandle] with `fsync`. */
internal class FileFlush(
    private val output: FileChannel,
    private val syncHandle: FileInputStream?,
    private val force: (FileChannel, metadata: Boolean) -> Unit,
) : Closeable {
    @Throws(IOException::class)
    fun flush(metadata: Boolean) {
        if (syncHandle != null) syncHandle.fd.sync() else force(output, metadata)
    }

    override fun close() {
        syncHandle?.close()
    }
}

/** The permission bits of [file] as a creation attribute, where the file system has them (not Windows). */
private fun permissionsOf(file: Path): Array<FileAttribute<*>> {
    val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) ?: return emptyArray()
    return arrayOf(PosixFilePermissions.asFileAttribute(view.readAttributes().permissions()))
}

/** Whether a file can really be created in [dir]; permission bits alone say little on a drive. */
internal fun probeWritable(dir: Path): Boolean =
    try {
        Files.delete(Files.createTempFile(dir, ".zayit-probe", null))
        true
    } catch (_: IOException) {
        false
    }

package io.github.kdroidfilter.seforimapp.framework.portable

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes [target] and everything under it; returns true when nothing remains. Links are removed
 * themselves and never entered: a symbolic link or a Windows junction planted on a drive someone
 * else prepared would otherwise make a cleanup delete files anywhere on the computer. A junction
 * is a reparse point that Java reports as "other", which is why that is checked too. A failure on
 * one entry goes to [onError] and the rest is still attempted.
 */
internal fun deleteTree(
    target: Path,
    onError: (Path, IOException) -> Unit = { _, _ -> },
): Boolean {
    val attributes =
        try {
            Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return true
        } catch (e: IOException) {
            onError(target, e)
            return false
        }
    if (attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
        try {
            Files.newDirectoryStream(target).use { entries -> entries.forEach { deleteTree(it, onError) } }
        } catch (e: IOException) {
            onError(target, e)
        } catch (e: DirectoryIteratorException) {
            onError(target, e.cause ?: IOException(e))
        }
    }
    return try {
        Files.deleteIfExists(target)
        true
    } catch (e: IOException) {
        onError(target, e)
        !Files.exists(target, NOFOLLOW_LINKS)
    }
}

/**
 * Whether [path] really lies inside [root] once links are resolved, so a folder on the drive that
 * is a link to somewhere on the computer is not treated as part of the drive. False when either
 * does not exist.
 */
internal fun isReallyInside(
    path: Path,
    root: Path,
): Boolean =
    try {
        path.toRealPath().startsWith(root.toRealPath())
    } catch (_: IOException) {
        false
    }

/**
 * The lines of [file] when it is a regular file, and no lines when it is missing or anything else,
 * a link included. Nothing is followed: a file kept on a drive someone else prepared can be a link
 * to any file on the computer.
 */
internal fun readLinesNoFollow(file: Path): List<String> =
    try {
        if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            Files.newInputStream(file, NOFOLLOW_LINKS).bufferedReader().use { it.readLines() }
        } else {
            emptyList()
        }
    } catch (_: IOException) {
        emptyList()
    }

/**
 * Replaces [file] with [text] as a new regular file. Whatever is at [file] is removed first, so a
 * link there is replaced and never written through.
 */
@Throws(IOException::class)
internal fun writeTextNoFollow(
    file: Path,
    text: String,
) {
    Files.deleteIfExists(file)
    Files.write(file, text.toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
}

/**
 * The size of the regular files under [root], which may itself be a file. Links are not entered
 * and not counted, so a link to `/` cannot make this walk the whole computer. Unreadable entries
 * count as nothing.
 */
internal fun treeSize(root: Path): Long {
    var total = 0L
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    return total
}

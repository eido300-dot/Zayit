package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.framework.platform.PlatformInfo
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.util.Properties
import java.util.zip.CRC32

private const val CHECKSUM_PREFIX = "#crc32="
private const val CHECKSUM_HEX_LENGTH = 8

/** Settings are a few kilobytes; anything near this size is not a settings file. */
internal const val MAX_SETTINGS_BYTES = 1L shl 20

/**
 * Whether [attributes] describe an entry that cannot hold settings: a link, a folder, or outside
 * Windows a pipe, socket or device, which a read could block on or never finish. On Windows,
 * "other" means a file with a reparse point that is not a link, such as a cloud placeholder; it is
 * read like any file, without following that point, and what it holds must pass the checksum.
 */
private fun cannotHoldSettings(attributes: BasicFileAttributes): Boolean =
    attributes.isSymbolicLink || attributes.isDirectory || (attributes.isOther && !PlatformInfo.isWindows)

/**
 * Reads a settings copy of at most [maxBytes]. A link, a pipe or a device planted on a drive
 * someone else prepared is refused instead of read: `readAllBytes` would follow a link to an
 * endless device and run out of memory, which no caller catches.
 */
@Throws(IOException::class)
internal fun readSettingsBytes(
    path: Path,
    maxBytes: Long = MAX_SETTINGS_BYTES,
): ByteArray {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (cannotHoldSettings(attributes)) throw FileSystemException(path.toString(), null, "not a file")
    Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
        val bytes = input.readNBytes(maxBytes.toInt() + 1)
        if (bytes.size > maxBytes) throw FileSystemException(path.toString(), null, "larger than $maxBytes bytes")
        return bytes
    }
}

/** Outcome of reading the portable settings file and its fallback copies. */
sealed interface LoadResult {
    /** Valid settings read from [source], or empty settings when no file existed yet ([source] is null). */
    data class Loaded(
        val properties: Properties,
        val source: Path?,
    ) : LoadResult

    /** Files exist but none is intact; the session starts from defaults. */
    data object Corrupt : LoadResult

    /**
     * The device failed to read, which says nothing about the content. The session must not write,
     * or the rotation would overwrite the good copies with new data. [fallback] holds the first
     * intact copy that could still be read, so the session does not start as a fresh install.
     */
    data class IoError(
        val cause: IOException,
        val fallback: Properties? = null,
    ) : LoadResult
}

/**
 * Serializes [properties] followed by a `#crc32=` trailer line. `Properties.load` silently accepts a
 * truncated file, so the checksum is what detects a half-written copy.
 */
internal fun encodeWithChecksum(properties: Properties): ByteArray {
    val body = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
    val trailer = CHECKSUM_PREFIX + crcHex(body) + "\n"
    return body + trailer.toByteArray(Charsets.US_ASCII)
}

/** Returns the properties when [bytes] carry a matching checksum trailer, otherwise `null`. */
internal fun decodeWithChecksum(bytes: ByteArray): Properties? {
    val bodyEnd = verifiedBodyEnd(bytes) ?: return null
    return try {
        Properties().apply { load(ByteArrayInputStream(bytes, 0, bodyEnd)) }
    } catch (e: IllegalArgumentException) {
        warnln(e) { "[portable] settings copy has a valid checksum but cannot be parsed" }
        null
    }
}

/** Length of the body covered by a valid trailer, or `null` when the trailer is missing or wrong. */
private fun verifiedBodyEnd(bytes: ByteArray): Int? {
    // ISO-8859-1 maps every byte to one char, so string indices equal byte offsets.
    val text = String(bytes, Charsets.ISO_8859_1)
    val bodyEnd = text.lastIndexOf("\n$CHECKSUM_PREFIX") + 1
    if (bodyEnd == 0) return null
    val trailer = text.substring(bodyEnd + CHECKSUM_PREFIX.length)
    val expected =
        trailer
            .takeIf { it.endsWith("\n") }
            ?.removeSuffix("\n")
            ?.removeSuffix("\r")
            ?.takeIf { it.length == CHECKSUM_HEX_LENGTH && it.all(::isHexDigit) }
            ?.lowercase()
    return bodyEnd.takeIf { expected != null && crcHex(bytes.copyOfRange(0, bodyEnd)) == expected }
}

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun crcHex(bytes: ByteArray): String {
    val crc = CRC32().also { it.update(bytes) }.value
    return java.lang.Long
        .toHexString(crc)
        .padStart(CHECKSUM_HEX_LENGTH, '0')
}

/**
 * The portable settings file plus its `.tmp` and `.bak` siblings.
 *
 * Save order: write `.tmp` (flushed), rotate the current file to `.bak`, rename `.tmp` over the
 * current file. Load order: current, `.tmp`, `.bak`; the first intact copy wins. At every instant
 * at least one intact copy exists, whichever step a crash or an unplugged drive interrupts. When
 * the loaded copy is `.tmp`, the next save first promotes it to the current file, since writing
 * `.tmp` would otherwise truncate the only intact copy. A link, a folder or a pipe found at one of
 * the three names is renamed aside before a save, and a read-only flag cleared on Windows, so the
 * steps only ever meet files they can replace.
 *
 * Not thread-safe: all saves must come from one thread ([SettingsWriter] guarantees that).
 */
class PortableSettingsFile(
    private val main: Path,
    private val readBytes: (Path) -> ByteArray = ::readSettingsBytes,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    private val tmp = siblingWithSuffix(main, ".tmp")
    private val bak = siblingWithSuffix(main, ".bak")

    /** Whether [main] is known to be intact, so rotating it to `.bak` does not discard a good backup. */
    @Volatile
    private var mainIsIntact = false

    /** Whether the loaded copy is `.tmp`, which the next save must promote before overwriting it. */
    @Volatile
    private var tmpIsLoadedCopy = false

    /**
     * Reads the first intact copy. Never throws. A failed read of one copy (an antivirus scanner
     * holding it, a slow mount) is retried briefly and does not stop the others from being read,
     * but the result is then [LoadResult.IoError], because the unreadable copy may be the newest.
     * A copy that cannot even be looked up counts as unreadable at once, unless the lookup was
     * refused: that is retried briefly, and a copy gone by then counts as missing.
     */
    fun load(): LoadResult {
        var anyCopy = false
        var readError: IOException? = null
        var intact: LoadResult.Loaded? = null
        for (copy in listOf(main, tmp, bak)) {
            val read = readCopy(copy)
            if (read == CopyRead.Missing) continue
            anyCopy = true
            when (read) {
                is CopyRead.Intact -> {
                    intact = LoadResult.Loaded(read.properties, copy)
                    break
                }
                is CopyRead.Unreadable -> readError = readError ?: read.cause
                CopyRead.Damaged, CopyRead.Missing -> Unit
            }
        }
        if (!anyCopy) return LoadResult.Loaded(Properties(), source = null)
        val error = readError
        mainIsIntact = error == null && intact?.source == main
        tmpIsLoadedCopy = error == null && intact?.source == tmp
        return when {
            error != null -> LoadResult.IoError(error, intact?.properties)
            intact != null -> intact
            else -> LoadResult.Corrupt
        }
    }

    private sealed interface CopyRead {
        data class Intact(
            val properties: Properties,
        ) : CopyRead

        data class Unreadable(
            val cause: IOException,
        ) : CopyRead

        data object Damaged : CopyRead

        data object Missing : CopyRead
    }

    private fun readCopy(copy: Path): CopyRead {
        // Without following links: a planted link counts as a damaged copy, not as a missing one.
        val attributes =
            try {
                lookUp(copy)
            } catch (_: NoSuchFileException) {
                return CopyRead.Missing
            } catch (e: IOException) {
                warnln(e) { "[portable] cannot look up settings copy ${copy.fileName}" }
                return CopyRead.Unreadable(e)
            }
        // A link, a folder or a file far too large for settings, planted in place of a copy, is
        // damaged rather than unreadable: an error would stop every later save, while the next
        // save replaces it, or renames it aside when it is not a file.
        if (cannotHoldSettings(attributes) || attributes.size() > MAX_SETTINGS_BYTES) {
            warnln { "[portable] ignoring settings copy ${copy.fileName}: not a settings file" }
            return CopyRead.Damaged
        }
        val bytes =
            try {
                readWithRetry(copy)
            } catch (e: IOException) {
                warnln(e) { "[portable] cannot read settings copy ${copy.fileName}" }
                return CopyRead.Unreadable(e)
            }
        val properties = decodeWithChecksum(bytes)
        if (properties == null) warnln { "[portable] ignoring damaged settings copy ${copy.fileName}" }
        return properties?.let { CopyRead.Intact(it) } ?: CopyRead.Damaged
    }

    /**
     * Reads the attributes of [copy], retrying only a refusal: on a FAT or exFAT drive, Windows
     * refuses a file deleted while something (an antivirus scanner) still holds it open, until
     * its name goes away. Other errors are not retried, so a failing drive is not asked again.
     */
    @Throws(IOException::class)
    private fun lookUp(copy: Path): BasicFileAttributes {
        var attempt = 1
        while (true) {
            try {
                return Files.readAttributes(copy, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (e: AccessDeniedException) {
                if (attempt >= READ_ATTEMPTS) throw e
                sleeper(READ_RETRY_DELAY_MILLIS * attempt)
                attempt++
            }
        }
    }

    @Throws(IOException::class)
    private fun readWithRetry(copy: Path): ByteArray {
        var attempt = 1
        while (true) {
            try {
                return readBytes(copy)
            } catch (e: IOException) {
                if (attempt >= READ_ATTEMPTS) throw e
                sleeper(READ_RETRY_DELAY_MILLIS * attempt)
                attempt++
            }
        }
    }

    /** Saves [properties] durably. See the class documentation for the step order. */
    @Throws(IOException::class)
    fun save(properties: Properties) {
        listOf(main, tmp, bak).forEach(::clearForSave)
        if (tmpIsLoadedCopy) {
            // The loaded copy lives in .tmp; make it the current file before .tmp is rewritten.
            Files.deleteIfExists(main)
            moveWithRetry(tmp, main, ATOMIC_MOVE, REPLACE_EXISTING)
            tmpIsLoadedCopy = false
            mainIsIntact = true
        }
        writeDurably(tmp, encodeWithChecksum(properties))
        if (Files.exists(main, NOFOLLOW_LINKS)) {
            if (mainIsIntact) {
                moveWithRetry(main, bak, ATOMIC_MOVE, REPLACE_EXISTING)
            } else {
                // A damaged current file must not replace a good backup.
                Files.delete(main)
            }
        }
        moveWithRetry(tmp, main, ATOMIC_MOVE, REPLACE_EXISTING)
        mainIsIntact = true
        main.parent?.let(::syncDirectory)
    }

    /**
     * Deletes every copy, so a reset leaves no old values behind in `.bak`. The backup goes first:
     * if a later delete fails, the copy that loads next is the cleared current file, not old values.
     */
    @Throws(IOException::class)
    fun resetFiles() {
        listOf(bak, tmp, main).forEach(Files::deleteIfExists)
        mainIsIntact = false
        tmpIsLoadedCopy = false
    }

    private companion object {
        const val READ_ATTEMPTS = 3
        const val READ_RETRY_DELAY_MILLIS = 50L
    }
}

/**
 * Clears [path] for the steps of a save. A link, a folder or a pipe is renamed aside, under a new
 * name next to it: a rename moves a link itself, never its target, works for a folder whatever it
 * holds, and keeps what was planted instead of deleting it. On Windows, a read-only flag on a
 * file, which would stop it from being deleted or replaced, is cleared.
 */
@Throws(IOException::class)
private fun clearForSave(path: Path) {
    val attributes =
        try {
            if (PlatformInfo.isWindows) {
                Files.readAttributes(path, DosFileAttributes::class.java, NOFOLLOW_LINKS)
            } else {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            }
        } catch (_: NoSuchFileException) {
            return
        }
    if (cannotHoldSettings(attributes)) {
        val aside = siblingWithSuffix(path, ".aside-${System.currentTimeMillis()}")
        moveWithRetry(path, aside, policy = RetryPolicy.ASIDE)
        warnln { "[portable] renamed ${path.fileName} to ${aside.fileName}: not a file" }
    } else if (attributes is DosFileAttributes && attributes.isReadOnly) {
        Files.setAttribute(path, "dos:readonly", false, NOFOLLOW_LINKS)
    }
}

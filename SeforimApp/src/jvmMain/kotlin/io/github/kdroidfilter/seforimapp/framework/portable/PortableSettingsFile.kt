package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.zip.CRC32

private const val CHECKSUM_PREFIX = "#crc32="
private const val CHECKSUM_HEX_LENGTH = 8

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
 * `.tmp` would otherwise truncate the only intact copy.
 *
 * Not thread-safe: all saves must come from one thread ([SettingsWriter] guarantees that).
 */
class PortableSettingsFile(
    private val main: Path,
    private val readBytes: (Path) -> ByteArray = Files::readAllBytes,
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
     * Reads the first intact copy. Never throws. A read error on one copy (an antivirus scanner
     * holding it, a slow mount) is retried briefly and does not stop the others from being read,
     * but the result is then [LoadResult.IoError], because the unreadable copy may be the newest.
     */
    fun load(): LoadResult {
        val existing = listOf(main, tmp, bak).filter { Files.exists(it) }
        if (existing.isEmpty()) return LoadResult.Loaded(Properties(), source = null)
        var readError: IOException? = null
        var intact: LoadResult.Loaded? = null
        for (copy in existing) {
            when (val read = readCopy(copy)) {
                is CopyRead.Intact -> {
                    intact = LoadResult.Loaded(read.properties, copy)
                    break
                }
                is CopyRead.Unreadable -> readError = readError ?: read.cause
                CopyRead.Damaged -> Unit
            }
        }
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
    }

    private fun readCopy(copy: Path): CopyRead {
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
        if (tmpIsLoadedCopy) {
            // The loaded copy lives in .tmp; make it the current file before .tmp is rewritten.
            Files.deleteIfExists(main)
            moveWithRetry(tmp, main, ATOMIC_MOVE, REPLACE_EXISTING)
            tmpIsLoadedCopy = false
            mainIsIntact = true
        }
        writeDurably(tmp, encodeWithChecksum(properties))
        if (Files.exists(main)) {
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

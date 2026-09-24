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
     * or the rotation would overwrite the good copies with new data.
     */
    data class IoError(
        val cause: IOException,
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
 * at least one intact copy exists, whichever step a crash or an unplugged drive interrupts.
 *
 * Not thread-safe: all saves must come from one thread ([SettingsWriter] guarantees that).
 */
class PortableSettingsFile(
    private val main: Path,
) {
    private val tmp = siblingWithSuffix(main, ".tmp")
    private val bak = siblingWithSuffix(main, ".bak")

    /** Whether [main] is known to be intact, so rotating it to `.bak` does not discard a good backup. */
    @Volatile
    private var mainIsIntact = false

    /** Reads the first intact copy. Never throws. */
    fun load(): LoadResult {
        val existing = listOf(main, tmp, bak).filter { Files.exists(it) }
        if (existing.isEmpty()) return LoadResult.Loaded(Properties(), source = null)
        val result = existing.firstNotNullOfOrNull(::readCopy) ?: LoadResult.Corrupt
        mainIsIntact = result is LoadResult.Loaded && result.source == main
        return result
    }

    private fun readCopy(copy: Path): LoadResult? {
        val bytes =
            try {
                Files.readAllBytes(copy)
            } catch (e: IOException) {
                return LoadResult.IoError(e)
            }
        val properties = decodeWithChecksum(bytes)
        if (properties == null) warnln { "[portable] ignoring damaged settings copy ${copy.fileName}" }
        return properties?.let { LoadResult.Loaded(it, copy) }
    }

    /** Saves [properties] durably. See the class documentation for the step order. */
    @Throws(IOException::class)
    fun save(properties: Properties) {
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

    /** Deletes every copy, so a reset leaves no old values behind in `.bak`. */
    @Throws(IOException::class)
    fun resetFiles() {
        listOf(main, tmp, bak).forEach(Files::deleteIfExists)
        mainIsIntact = false
    }
}

package io.github.kdroidfilter.seforimapp.framework.portable

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableSettingsFileTest {
    private val root: Path = createTempDirectory("zayit-portable-settings")
    private val main: Path = root.resolve("settings.properties")
    private val file = PortableSettingsFile(main)

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun props(vararg pairs: Pair<String, String>): Properties = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    private fun loaded(): Properties = assertIs<LoadResult.Loaded>(file.load()).properties

    @Test
    fun `save then load round trips values including Hebrew and long strings`() {
        val hebrew = "\u05E1\u05E4\u05E8\u05D9\u05DD"
        val long = "x".repeat(20_000)
        file.save(props("name" to hebrew, "long" to long, "flag" to "true", "size" to "16.5"))

        val result = loaded()

        assertEquals(hebrew, result.getProperty("name"))
        assertEquals(long, result.getProperty("long"))
        assertEquals("true", result.getProperty("flag"))
        assertEquals("16.5", result.getProperty("size"))
    }

    @Test
    fun `no files at all loads empty settings`() {
        val result = assertIs<LoadResult.Loaded>(file.load())
        assertTrue(result.properties.isEmpty)
        assertNull(result.source)
    }

    @Test
    fun `second save keeps the previous version as backup`() {
        file.save(props("v" to "1"))
        file.save(props("v" to "2"))

        assertEquals("2", loaded().getProperty("v"))
        val backup = assertNotNull(decodeWithChecksum(Files.readAllBytes(root.resolve("settings.properties.bak"))))
        assertEquals("1", backup.getProperty("v"))
    }

    @Test
    fun `flipped byte in the main file falls back to the backup`() {
        file.save(props("v" to "1"))
        file.save(props("v" to "2"))
        val bytes = Files.readAllBytes(main)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        Files.write(main, bytes)

        assertEquals("1", loaded().getProperty("v"))
    }

    @Test
    fun `truncated main file falls back to the backup`() {
        file.save(props("v" to "1"))
        file.save(props("v" to "2"))
        val bytes = Files.readAllBytes(main)
        Files.write(main, bytes.copyOf(bytes.size / 2))

        assertEquals("1", loaded().getProperty("v"))
    }

    @Test
    fun `missing main after an interrupted save loads the completed temporary file`() {
        file.save(props("v" to "1"))
        // Simulates a crash after main was rotated to .bak but before .tmp was renamed to main.
        Files.write(root.resolve("settings.properties.tmp"), encodeWithChecksum(props("v" to "2")))
        Files.move(main, root.resolve("settings.properties.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        assertEquals("2", loaded().getProperty("v"))
    }

    @Test
    fun `missing checksum trailer is rejected`() {
        val body = ByteArrayOutputStream().also { props("v" to "1").store(it, null) }.toByteArray()
        assertNull(decodeWithChecksum(body))
    }

    @Test
    fun `trailer with Windows line ending is accepted`() {
        val body = ByteArrayOutputStream().also { props("v" to "1").store(it, null) }.toByteArray()
        val crc = CRC32().also { it.update(body) }.value
        val trailer =
            "#crc32=" +
                java.lang.Long
                    .toHexString(crc)
                    .padStart(8, '0') + "\r\n"

        val decoded = assertNotNull(decodeWithChecksum(body + trailer.toByteArray(Charsets.US_ASCII)))
        assertEquals("1", decoded.getProperty("v"))
    }

    @Test
    fun `malformed unicode escape with a valid checksum is treated as corrupt without throwing`() {
        val body = "v=\\uZZZZ\n".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().also { it.update(body) }.value
        val bytes =
            body +
                (
                    "#crc32=" +
                        java.lang.Long
                            .toHexString(crc)
                            .padStart(8, '0') + "\n"
                ).toByteArray(Charsets.US_ASCII)

        assertNull(decodeWithChecksum(bytes))
    }

    @Test
    fun `tiny or empty content is rejected without throwing`() {
        listOf(ByteArray(0), "a".toByteArray(), "abc\n".toByteArray(), "\n#crc32=".toByteArray()).forEach {
            assertNull(decodeWithChecksum(it), "expected rejection of ${it.size} bytes")
        }
    }

    @Test
    fun `non hex checksum characters are rejected`() {
        val body = ByteArrayOutputStream().also { props("v" to "1").store(it, null) }.toByteArray()
        assertNull(decodeWithChecksum(body + "#crc32=zzzzzzzz\n".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `saving after loading from the backup keeps that good backup`() {
        file.save(props("v" to "1"))
        file.save(props("v" to "2"))
        Files.write(main, "damaged".toByteArray())
        assertEquals("1", loaded().getProperty("v"))

        file.save(props("v" to "3"))

        assertEquals("3", loaded().getProperty("v"))
        val backup = assertNotNull(decodeWithChecksum(Files.readAllBytes(root.resolve("settings.properties.bak"))))
        assertEquals("1", backup.getProperty("v"), "the damaged file must not have replaced the good backup")
    }

    @Test
    fun `all three copies corrupt reports corrupt`() {
        listOf("settings.properties", "settings.properties.tmp", "settings.properties.bak").forEach {
            Files.write(root.resolve(it), "garbage".toByteArray())
        }

        assertIs<LoadResult.Corrupt>(file.load())
    }

    @Test
    fun `unreadable main file reports an IO error instead of corrupt`() {
        // A directory where the file should be makes every read fail with an IOException.
        Files.createDirectories(main)

        assertIs<LoadResult.IoError>(file.load())
    }

    @Test
    fun `transient read error is retried and loads normally`() {
        file.save(props("v" to "1"))
        var failures = 1
        val flaky =
            PortableSettingsFile(
                main,
                readBytes = { path ->
                    if (path == main && failures-- > 0) throw java.io.IOException("locked by a scanner")
                    Files.readAllBytes(path)
                },
                sleeper = {},
            )

        val result = assertIs<LoadResult.Loaded>(flaky.load())

        assertEquals("1", result.properties.getProperty("v"))
        assertEquals(main, result.source)
    }

    @Test
    fun `unreadable main still offers the backup values without allowing writes`() {
        file.save(props("v" to "1"))
        file.save(props("v" to "2"))
        val stuck =
            PortableSettingsFile(
                main,
                readBytes = { path ->
                    if (path == main) throw java.io.IOException("bad sector")
                    Files.readAllBytes(path)
                },
                sleeper = {},
            )

        val result = assertIs<LoadResult.IoError>(stuck.load())

        assertEquals("1", assertNotNull(result.fallback).getProperty("v"))
    }

    @Test
    fun `saving after loading from the temporary file never leaves it the only truncated copy`() {
        // An interrupted first save: only .tmp exists, and it is intact.
        Files.write(root.resolve("settings.properties.tmp"), encodeWithChecksum(props("v" to "1")))
        assertEquals("1", loaded().getProperty("v"))

        file.save(props("v" to "2"))

        assertEquals("2", loaded().getProperty("v"))
        val backup = assertNotNull(decodeWithChecksum(Files.readAllBytes(root.resolve("settings.properties.bak"))))
        assertEquals("1", backup.getProperty("v"), "the promoted .tmp copy must survive as the backup")
    }

    @Test
    fun `resetFiles deletes the backup before the current file`() {
        file.save(props("v" to "1"))
        file.save(props())
        val bak = root.resolve("settings.properties.bak")
        // The backup cannot be deleted: a non-empty directory stands in its place.
        Files.delete(bak)
        Files.createDirectories(bak.resolve("locked"))

        assertFailsWith<java.nio.file.DirectoryNotEmptyException> { file.resetFiles() }

        // The cleared current file is still there, so the next launch cannot fall back to old values.
        assertTrue(Files.exists(main))
    }

    @Test
    fun `resetFiles removes main temporary and backup copies`() {
        file.save(props("v" to "1"))
        file.save(props("v" to "2"))
        Files.write(root.resolve("settings.properties.tmp"), byteArrayOf(1))

        file.resetFiles()

        listOf("settings.properties", "settings.properties.tmp", "settings.properties.bak").forEach {
            assertFalse(Files.exists(root.resolve(it)), "$it should be deleted")
        }
    }
}

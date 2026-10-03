package io.github.kdroidfilter.seforimapp.framework.portable

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortableSettingsStoreTest {
    private val root: Path = createTempDirectory("zayit-portable-store")
    private val settingsFile: Path = root.resolve("settings.properties")
    private val executors = mutableListOf<ExecutorService>()

    @AfterTest
    fun cleanUp() {
        executors.forEach { it.shutdownNow() }
        root.toFile().deleteRecursively()
    }

    private fun open(): PortableSettingsStore =
        PortableSettingsStore.open(
            settingsFile,
            Executors.newSingleThreadExecutor().also { executors += it },
        )

    @Test
    fun `a read-only store keeps changes in memory and writes nothing`() {
        val store = PortableSettingsStore.open(settingsFile, Executors.newSingleThreadExecutor().also { executors += it }, readOnly = true)

        store.settings.putString("name", "value")

        assertTrue(store.isReadOnly)
        assertTrue(store.flush())
        assertEquals("value", store.settings.getString("name", ""))
        assertFalse(Files.exists(settingsFile))
    }

    @Test
    fun `values written through settings survive a reopen`() {
        val first = open()
        first.settings.putString("name", "value")
        first.settings.putInt("size", 18)
        assertTrue(first.flush())

        val reopened = open()

        assertEquals("value", reopened.settings.getString("name", ""))
        assertEquals(18, reopened.settings.getInt("size", 0))
    }

    @Test
    fun `IO error at load gives a read only session that never writes`() {
        Files.write(settingsFile, encodeWithChecksum(Properties().apply { setProperty("name", "old") }))
        val before = Files.readAllBytes(settingsFile)
        val unreadable =
            PortableSettingsFile(
                settingsFile,
                readBytes = { throw IOException("bad sector") },
                sleeper = {},
            )
        val store = PortableSettingsStore.open(unreadable, Executors.newSingleThreadExecutor().also { executors += it })

        store.settings.putString("name", "value")
        store.flush()

        assertTrue(store.isReadOnly)
        assertIs<LoadResult.IoError>(store.loadResult)
        assertContentEquals(before, Files.readAllBytes(settingsFile), "the unreadable file must be left untouched")
        assertFalse(Files.exists(root.resolve("settings.properties.tmp")))
        assertEquals("value", store.settings.getString("name", ""), "the session still works in memory")
    }

    @Test
    fun `corrupt file gives a writable session starting empty`() {
        Files.write(settingsFile, "garbage".toByteArray())
        val store = open()

        assertFalse(store.isReadOnly)
        assertEquals("fallback", store.settings.getString("name", "fallback"))
    }

    @Test
    fun `resetFiles after clear leaves no settings on disk`() {
        val store = open()
        store.settings.putString("name", "secret")
        store.flush()
        store.settings.putString("name", "newer")
        store.flush()

        store.settings.clear()
        store.resetFiles()

        listOf("settings.properties", "settings.properties.tmp", "settings.properties.bak").forEach {
            val path = root.resolve(it)
            val keepsSecret = Files.exists(path) && String(Files.readAllBytes(path)).contains("secret")
            assertFalse(keepsSecret, "$it must not keep old values")
        }
    }
}

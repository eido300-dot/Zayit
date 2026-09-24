package io.github.kdroidfilter.seforimapp.framework.portable

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
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
        Files.createDirectories(settingsFile)
        val store = open()

        store.settings.putString("name", "value")
        store.flush()

        assertTrue(store.isReadOnly)
        assertIs<LoadResult.IoError>(store.loadResult)
        assertTrue(Files.isDirectory(settingsFile), "the unreadable path must be left untouched")
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

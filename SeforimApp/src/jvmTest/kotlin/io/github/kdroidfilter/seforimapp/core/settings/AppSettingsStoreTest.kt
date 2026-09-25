package io.github.kdroidfilter.seforimapp.core.settings

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.github.kdroidfilter.seforimapp.framework.portable.PortableLayout
import io.github.kdroidfilter.seforimapp.framework.portable.PortableSettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class AppSettingsStoreTest {
    private val root: Path = createTempDirectory("zayit-app-settings-store")
    private val layout = PortableLayout(root.resolve("zayit-data"))
    private val executors = mutableListOf<ExecutorService>()
    private val hostMustNotBeUsed: () -> Settings = { fail("host Preferences must not be touched in portable mode") }

    @AfterTest
    fun cleanUp() {
        executors.forEach { it.shutdownNow() }
        root.toFile().deleteRecursively()
    }

    private fun openStore(): PortableSettingsStore {
        Files.createDirectories(layout.dataDir)
        return PortableSettingsStore.open(layout.settingsFile, Executors.newSingleThreadExecutor().also { executors += it })
    }

    @Test
    fun `host mode uses the host factory`() {
        val host = PropertiesSettings(Properties())

        assertSame(host, createAppSettings(layout = null, portableStore = null, hostFactory = { host }))
    }

    @Test
    fun `portable mode keeps settings in the file on the drive`() {
        val store = openStore()
        val settings = createAppSettings(layout, store, hostMustNotBeUsed)

        settings.putString("theme", "dark")
        assertTrue(store.flush())

        val reopened = createAppSettings(layout, openStore(), hostMustNotBeUsed)
        assertEquals("dark", reopened.getString("theme", ""))
    }

    @Test
    fun `unreadable settings file still never falls back to the host`() {
        Files.createDirectories(layout.settingsFile)
        val settings = createAppSettings(layout, openStore(), hostMustNotBeUsed)

        settings.putString("theme", "dark")

        assertEquals("dark", settings.getString("theme", ""), "the session works in memory")
    }

    @Test
    fun `portable layout without a store runs in memory without the host`() {
        val settings = createAppSettings(layout, portableStore = null, hostFactory = hostMustNotBeUsed)

        settings.putInt("size", 18)

        assertEquals(18, settings.getInt("size", 0))
    }
}

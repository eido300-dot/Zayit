package io.github.kdroidfilter.seforimapp.framework.portable

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableLayoutTest {
    private val root: Path = createTempDirectory("zayit-portable-layout")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun createExecutable(relative: String): Path {
        val exe = root.resolve(relative)
        Files.createDirectories(exe.parent)
        Files.createFile(exe)
        return exe
    }

    private fun createMarker(dataDir: Path) {
        Files.createDirectories(dataDir)
        Files.createFile(dataDir.resolve(PORTABLE_MARKER_NAME))
    }

    @Test
    fun `windows style executable resolves data dir next to the executable`() {
        val exe = createExecutable("Zayit/zayit.exe")
        createMarker(root.resolve("Zayit/$PORTABLE_DATA_DIR_NAME"))

        val layout = assertNotNull(resolvePortableLayout(exe, override = null))

        assertEquals(root.resolve("Zayit/$PORTABLE_DATA_DIR_NAME"), layout.dataDir)
    }

    @Test
    fun `linux style executable without extension resolves data dir next to the executable`() {
        val exe = createExecutable("zayit/zayit")
        createMarker(root.resolve("zayit/$PORTABLE_DATA_DIR_NAME"))

        val layout = assertNotNull(resolvePortableLayout(exe, override = null))

        assertEquals(root.resolve("zayit/$PORTABLE_DATA_DIR_NAME"), layout.dataDir)
    }

    @Test
    fun `mac bundle executable resolves data dir next to the bundle`() {
        // The shipped bundle name is Hebrew; escapes keep the source file ASCII.
        val bundle = "\u05D6\u05D9\u05EA.app"
        val exe = createExecutable("$bundle/Contents/MacOS/zayit")
        createMarker(root.resolve(PORTABLE_DATA_DIR_NAME))

        val layout = assertNotNull(resolvePortableLayout(exe, override = null))

        assertEquals(root.resolve(PORTABLE_DATA_DIR_NAME), layout.dataDir)
        assertEquals(root.resolve(bundle), appBundleOf(exe))
        assertEquals(root.resolve(bundle), programRootOf(exe))
    }

    @Test
    fun `Contents MacOS without app suffix is not a bundle`() {
        val exe = createExecutable("Tools/Contents/MacOS/zayit")

        assertNull(appBundleOf(exe))
        assertEquals(exe.parent, programRootOf(exe))
        assertEquals(exe.parent, containerOf(exe))
    }

    @Test
    fun `missing marker means host mode`() {
        val exe = createExecutable("Zayit/zayit.exe")
        Files.createDirectories(root.resolve("Zayit/$PORTABLE_DATA_DIR_NAME"))

        assertNull(resolvePortableLayout(exe, override = null))
    }

    @Test
    fun `null executable without override means host mode`() {
        assertNull(resolvePortableLayout(executable = null, override = null))
    }

    @Test
    fun `override with marker wins over the executable location`() {
        val exe = createExecutable("Zayit/zayit.exe")
        createMarker(root.resolve("Zayit/$PORTABLE_DATA_DIR_NAME"))
        val overrideDir = root.resolve("elsewhere")
        createMarker(overrideDir)

        val layout = assertNotNull(resolvePortableLayout(exe, override = overrideDir.toString()))

        assertEquals(overrideDir, layout.dataDir)
    }

    @Test
    fun `override without marker means host mode`() {
        val overrideDir = root.resolve("no-marker")
        Files.createDirectories(overrideDir)

        assertNull(resolvePortableLayout(executable = null, override = overrideDir.toString()))
    }

    @Test
    fun `blank override is ignored`() {
        val exe = createExecutable("Zayit/zayit.exe")
        createMarker(root.resolve("Zayit/$PORTABLE_DATA_DIR_NAME"))

        val layout = assertNotNull(resolvePortableLayout(exe, override = "   "))

        assertEquals(root.resolve("Zayit/$PORTABLE_DATA_DIR_NAME"), layout.dataDir)
    }

    @Test
    fun `invalid override path means host mode and does not throw`() {
        assertNull(resolvePortableLayout(executable = null, override = "bad\u0000path"))
    }

    @Test
    fun `layout derives all paths from the data dir`() {
        val dataDir = root.resolve(PORTABLE_DATA_DIR_NAME)
        val layout = PortableLayout(dataDir)

        assertEquals(dataDir, layout.filesDir)
        assertEquals(dataDir.resolve("cache"), layout.cacheDir)
        assertEquals(dataDir.resolve("settings.properties"), layout.settingsFile)
        assertEquals(dataDir.resolve(PORTABLE_MARKER_NAME), layout.markerFile)
    }

    @Test
    fun `translocated paths are detected with either separator`() {
        val translocated = "/private/var/folders/x/T/AppTranslocation/ABC/d/Zayit.app/Contents/MacOS/zayit"
        assertTrue(isTranslocated(Path.of(translocated)))
        assertTrue(isTranslocated(Path.of("C:\\x\\AppTranslocation\\y\\zayit.exe")))
        assertFalse(isTranslocated(Path.of("/Applications/Zayit.app/Contents/MacOS/zayit")))
    }

    @Test
    fun `lock identifier is deterministic and differs between data dirs`() {
        val first = lockIdentifierFor("io.github.app", root.resolve("a"))
        val again = lockIdentifierFor("io.github.app", root.resolve("a"))
        val other = lockIdentifierFor("io.github.app", root.resolve("b"))

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertTrue(Regex("io\\.github\\.app-portable-[0-9a-f]{8}").matches(first), first)
    }

    @Test
    fun `lock identifier is the same through a symlink to the data dir`() {
        val real = Files.createDirectories(root.resolve("real"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)

        assertEquals(lockIdentifierFor("io.github.app", real), lockIdentifierFor("io.github.app", link))
    }
}

package io.github.kdroidfilter.seforimapp.framework.update

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.UpdateLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** N1 — pure decision logic, no I/O. */
class UpdateDecisionTest {
    @Test
    fun `patch is silent on windows and macos only`() {
        assertEquals(UpdateMode.SILENT_ON_CLOSE, resolveUpdateMode(UpdateLevel.PATCH, Platform.Windows))
        assertEquals(UpdateMode.SILENT_ON_CLOSE, resolveUpdateMode(UpdateLevel.PATCH, Platform.MacOS))
        assertEquals(UpdateMode.PROMPT, resolveUpdateMode(UpdateLevel.PATCH, Platform.Linux))
    }

    @Test
    fun `minor and major always prompt`() {
        for (os in listOf(Platform.Windows, Platform.MacOS, Platform.Linux)) {
            assertEquals(UpdateMode.PROMPT, resolveUpdateMode(UpdateLevel.MINOR, os))
            assertEquals(UpdateMode.PROMPT, resolveUpdateMode(UpdateLevel.MAJOR, os))
        }
    }

    @Test
    fun `pre-release always prompts`() {
        for (os in listOf(Platform.Windows, Platform.MacOS, Platform.Linux)) {
            assertEquals(UpdateMode.PROMPT, resolveUpdateMode(UpdateLevel.PRE_RELEASE, os))
        }
    }

    @Test
    fun `db warning only for minor and major`() {
        assertTrue(needsDbWarning(UpdateLevel.MINOR))
        assertTrue(needsDbWarning(UpdateLevel.MAJOR))
        assertFalse(needsDbWarning(UpdateLevel.PATCH))
        assertFalse(needsDbWarning(UpdateLevel.PRE_RELEASE))
    }

    @Test
    fun `only patch is pre-downloaded`() {
        assertTrue(shouldPreDownload(UpdateLevel.PATCH))
        assertFalse(shouldPreDownload(UpdateLevel.MINOR))
        assertFalse(shouldPreDownload(UpdateLevel.MAJOR))
        assertFalse(shouldPreDownload(UpdateLevel.PRE_RELEASE))
    }

    @Test
    fun `available update state depends on portable mode`() {
        for (os in listOf(Platform.Windows, Platform.MacOS, Platform.Linux)) {
            for (level in UpdateLevel.entries) {
                val portable = availableUpdateState("2.0.0", level, os, isPortable = true)
                assertEquals(UpdateUiState.PortableUpdateAvailable("2.0.0", level), portable)

                val installed = availableUpdateState("2.0.0", level, os, isPortable = false)
                assertIs<UpdateUiState.Available>(installed)
                assertEquals(resolveUpdateMode(level, os), installed.mode)
                assertEquals(needsDbWarning(level), installed.needsDbWarning)
            }
        }
    }
}

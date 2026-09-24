package io.github.kdroidfilter.seforimapp.framework.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutablePathTest {
    @Test
    fun `isJvmLauncherName recognises all JVM launcher names`() {
        listOf("java", "javaw", "java.exe", "javaw.exe", "JAVA.EXE", "Javaw").forEach { name ->
            assertTrue(isJvmLauncherName(name), "expected $name to be a JVM launcher")
        }
    }

    @Test
    fun `isJvmLauncherName rejects application binaries`() {
        listOf("zayit", "zayit.exe", "javafx", "java-cli", "").forEach { name ->
            assertFalse(isJvmLauncherName(name), "expected $name not to be a JVM launcher")
        }
    }

    @Test
    fun `currentExecutablePath is null when running under a JVM launcher`() {
        // Unit tests run inside a plain `java` process, which is exactly the dev/classpath case.
        assertNull(currentExecutablePath())
    }
}

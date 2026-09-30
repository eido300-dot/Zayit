package io.github.kdroidfilter.seforimapp.framework.platform

import java.io.File
import java.nio.file.Path

private val JVM_LAUNCHER_NAMES = setOf("java", "javaw", "java.exe", "javaw.exe")

/**
 * Resolves the path of the currently running application binary.
 *
 * - `jpackage.app-path` is set by jpackage-style launchers and points at the user-facing
 *   executable.
 * - Otherwise [ProcessHandle.current] returns the launching command, which for the GraalVM
 *   native image is the `zayit` binary itself.
 *
 * Returns `null` for a plain `java -jar` or Gradle dev run, where the command is the JDK launcher
 * rather than a packaged binary.
 */
fun currentExecutablePath(): Path? {
    System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() && File(it).exists() }?.let { return Path.of(it) }

    val command =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
            ?.takeIf { it.isNotBlank() }
    if (command != null) {
        // A JVM launcher means we are running from a dev/classpath setup, not the packaged
        // native binary.
        if (!isJvmLauncherName(File(command).name) && File(command).exists()) return Path.of(command)
    }
    return null
}

/** True when [name] is the file name of a JVM launcher (`java`, `javaw`, with or without `.exe`). */
internal fun isJvmLauncherName(name: String): Boolean = name.lowercase() in JVM_LAUNCHER_NAMES

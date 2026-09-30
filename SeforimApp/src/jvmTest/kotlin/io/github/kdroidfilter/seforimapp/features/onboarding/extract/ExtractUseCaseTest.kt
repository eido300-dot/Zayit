package io.github.kdroidfilter.seforimapp.features.onboarding.extract

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtractUseCaseTest {
    private val useCase = ExtractUseCase()

    @Test
    fun `entries inside the target directory are resolved`() {
        val dir = Files.createTempDirectory("extract").toFile()
        try {
            assertEquals(File(dir, "seforim.db").canonicalFile, useCase.resolveEntryFile(dir, "seforim.db"))
            assertEquals(File(dir, "sub/a.db").canonicalFile, useCase.resolveEntryFile(dir, "./sub/a.db"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `entries escaping the target directory are rejected`() {
        val dir = Files.createTempDirectory("extract").toFile()
        try {
            assertFailsWith<IllegalArgumentException> { useCase.resolveEntryFile(dir, "../evil.desktop") }
            assertFailsWith<IllegalArgumentException> { useCase.resolveEntryFile(dir, "sub/../../evil") }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `main database is chosen by name regardless of archive order`() {
        val main = File("seforim.db")
        val lexical = File("lexical.db")
        assertEquals(main, useCase.pickMainDatabase(listOf(main, lexical)))
        assertEquals(main, useCase.pickMainDatabase(listOf(lexical, main)))
        assertEquals(File("other.db"), useCase.pickMainDatabase(listOf(lexical, File("other.db"))))
        assertFailsWith<IllegalStateException> { useCase.pickMainDatabase(listOf(lexical)) }
    }
}

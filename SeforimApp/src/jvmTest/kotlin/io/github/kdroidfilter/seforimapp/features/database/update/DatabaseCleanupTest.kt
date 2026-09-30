package io.github.kdroidfilter.seforimapp.features.database.update

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DatabaseCleanupTest {
    private val dir: File = Files.createTempDirectory("db-cleanup").toFile()
    private val useCase = DatabaseCleanupUseCase()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(
        name: String,
        bytes: Int = 1,
    ): File = File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `removes database artifacts and leaves other files alone`() {
        val artifacts =
            listOf(
                file("seforim.db", 10),
                file("seforim.db-wal", 5),
                file("lexical.db", 3),
                file("catalog.pb"),
                file("release_info.txt"),
                file("zayit.tar.zst.part01"),
                file("zayit.tar.zst.part02"),
                file("seforim.db.tmp"),
            )
        val lucene = File(dir, "seforim.lucene").apply { mkdirs() }
        File(lucene, "segments_1").writeBytes(ByteArray(4))
        val unrelated = file("user_settings.txt")

        val result = assertIs<DatabaseCleanupUseCase.CleanupResult.Success>(useCase.removeArtifacts(listOf(dir), keep = emptyList()))

        artifacts.forEach { assertFalse(it.exists(), "${it.name} should be deleted") }
        assertFalse(lucene.exists())
        assertTrue(unrelated.exists())
        assertEquals(10L + 5 + 3 + 1 + 1 + 1 + 1 + 1 + 4, result.freedBytes)
    }

    @Test
    fun `keeps the offline bundle the user picked`() {
        val part01 = file("zayit.tar.zst.part01")
        val part02 = file("zayit.tar.zst.part02")
        val oldDb = file("seforim.db")

        // The keep-list is matched by canonical path, so a non-normalized path must still match.
        val keep = listOf(File(dir, "./zayit.tar.zst.part01"), File(dir, "sub/../zayit.tar.zst.part02"))
        assertIs<DatabaseCleanupUseCase.CleanupResult.Success>(useCase.removeArtifacts(listOf(dir), keep))

        assertTrue(part01.exists())
        assertTrue(part02.exists())
        assertFalse(oldDb.exists())
    }

    @Test
    fun `user settings database in its subdirectory survives`() {
        val settings = File(dir, "settings").apply { mkdirs() }
        val userDb = File(settings, "user_settings.db").apply { writeBytes(ByteArray(1)) }
        file("seforim.db")

        useCase.removeArtifacts(listOf(dir), keep = emptyList())

        assertTrue(userDb.exists())
    }

    @Test
    fun `missing directories are skipped`() {
        val result = useCase.removeArtifacts(listOf(File(dir, "does-not-exist")), keep = emptyList())
        assertEquals(DatabaseCleanupUseCase.CleanupResult.Success(0), result)
    }
}

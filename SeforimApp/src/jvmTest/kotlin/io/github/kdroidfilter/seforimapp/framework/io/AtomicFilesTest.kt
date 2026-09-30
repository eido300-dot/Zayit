package io.github.kdroidfilter.seforimapp.framework.io

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AtomicFilesTest {
    private val dir: File = Files.createTempDirectory("atomic").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes new content and leaves no temp file`() {
        val target = File(dir, "session.pb")
        target.writeText("old")

        target.writeAtomically { it.write("new".toByteArray()) }

        assertEquals("new", target.readText())
        assertEquals(listOf("session.pb"), dir.list()!!.toList())
    }

    @Test
    fun `a link left at the temp name is replaced, not written through`() {
        val elsewhere = File(dir, "elsewhere.txt").apply { writeText("not ours") }
        val target = File(dir, "session.pb")
        Files.createSymbolicLink(File(dir, "session.pb.tmp").toPath(), elsewhere.toPath())

        target.writeAtomically { it.write("new".toByteArray()) }

        assertEquals("not ours", elsewhere.readText())
        assertEquals("new", target.readText())
        assertFalse(Files.isSymbolicLink(target.toPath()))
    }

    @Test
    fun `failed write keeps previous content`() {
        val target = File(dir, "seforim.db")
        target.writeText("complete")

        assertFailsWith<IOException> {
            target.writeAtomically {
                it.write("partial".toByteArray())
                throw IOException("connection reset")
            }
        }

        assertEquals("complete", target.readText())
        assertFalse(File(dir, "seforim.db.tmp").exists())
    }

    @Test
    fun `failed first write leaves no target`() {
        val target = File(dir, "new.db")

        assertFailsWith<IllegalStateException> { target.writeAtomically { error("boom") } }

        assertFalse(target.exists())
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `chunked sync runs once per whole chunk written`() {
        var syncs = 0
        val chunks = ChunkedSync(chunkBytes = 10) { syncs++ }

        repeat(7) { chunks.wrote(4) }

        assertEquals(2, syncs)
    }
}

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
}

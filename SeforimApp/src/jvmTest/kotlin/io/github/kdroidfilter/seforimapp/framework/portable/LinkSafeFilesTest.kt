package io.github.kdroidfilter.seforimapp.framework.portable

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkSafeFilesTest {
    private val root: Path = createTempDirectory("zayit-link-safe")
    private val drive: Path = Files.createDirectories(root.resolve("drive"))
    private val computer: Path = Files.createDirectories(root.resolve("computer"))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a folder tree is deleted`() {
        val tree = Files.createDirectories(drive.resolve("seforim.db.lucene/sub"))
        Files.write(tree.resolve("a"), byteArrayOf(1))

        assertTrue(deleteTree(drive.resolve("seforim.db.lucene")))
        assertFalse(Files.exists(drive.resolve("seforim.db.lucene")))
    }

    @Test
    fun `a link to a folder is removed without touching what it points to`() {
        val document = Files.write(computer.resolve("document.txt"), byteArrayOf(1))
        val link = Files.createSymbolicLink(drive.resolve("seforim.db.lucene"), computer)

        assertTrue(deleteTree(link))

        assertFalse(Files.exists(link, NOFOLLOW_LINKS))
        assertTrue(Files.exists(document))
    }

    @Test
    fun `a link inside a folder is not entered`() {
        val document = Files.write(computer.resolve("document.txt"), byteArrayOf(1))
        val tree = Files.createDirectories(drive.resolve("delta-cache"))
        Files.createSymbolicLink(tree.resolve("elsewhere"), computer)

        assertTrue(deleteTree(tree))

        assertTrue(Files.exists(document))
    }

    @Test
    fun `a missing path counts as deleted`() {
        assertTrue(deleteTree(drive.resolve("missing")))
    }

    @Test
    fun `a folder that is a link off the drive is not inside it`() {
        val databases = Files.createSymbolicLink(drive.resolve("databases"), computer)

        assertFalse(isReallyInside(databases, drive))
        assertTrue(isReallyInside(Files.createDirectories(drive.resolve("cache")), drive))
        assertFalse(isReallyInside(drive.resolve("missing"), drive))
    }

    @Test
    fun `text written over a planted link replaces the link and leaves its target alone`() {
        val target = Files.write(computer.resolve("important.txt"), byteArrayOf(9, 9))
        val marker = Files.createSymbolicLink(drive.resolve("marker.txt"), target)

        writeTextNoFollow(marker, "a\nb")

        assertContentEquals(byteArrayOf(9, 9), Files.readAllBytes(target))
        assertFalse(Files.isSymbolicLink(marker))
        assertEquals(listOf("a", "b"), readLinesNoFollow(marker))
    }

    @Test
    fun `a link is read as no lines`() {
        val target = Files.write(computer.resolve("secret.txt"), "hunter2".toByteArray())
        val marker = Files.createSymbolicLink(drive.resolve("marker.txt"), target)

        assertEquals(emptyList(), readLinesNoFollow(marker))
        assertEquals(emptyList(), readLinesNoFollow(drive.resolve("missing.txt")))
    }

    @Test
    fun `tree size counts files and does not follow links`() {
        Files.write(computer.resolve("big.bin"), ByteArray(1000))
        val tree = Files.createDirectories(drive.resolve("index"))
        Files.write(tree.resolve("a"), ByteArray(10))
        Files.write(Files.createDirectories(tree.resolve("sub")).resolve("b"), ByteArray(5))
        Files.createSymbolicLink(tree.resolve("elsewhere"), computer)

        assertEquals(15L, treeSize(tree))
        assertEquals(10L, treeSize(tree.resolve("a")))
        assertEquals(0L, treeSize(drive.resolve("missing")))
    }
}

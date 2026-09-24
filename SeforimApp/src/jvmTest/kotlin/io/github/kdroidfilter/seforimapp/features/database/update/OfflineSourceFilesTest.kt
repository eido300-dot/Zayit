package io.github.kdroidfilter.seforimapp.features.database.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineSourceFilesTest {
    @Test
    fun `either part keeps both halves of a split bundle`() {
        val expected = listOf(File("/d/zayit.tar.zst.part01"), File("/d/zayit.tar.zst.part02"))
        assertEquals(expected, offlineSourceFiles("/d/zayit.tar.zst.part01"))
        assertEquals(expected, offlineSourceFiles("/d/zayit.tar.zst.part02"))
    }

    @Test
    fun `single archive keeps only itself`() {
        assertEquals(listOf(File("/d/zayit.tar.zst")), offlineSourceFiles("/d/zayit.tar.zst"))
    }
}

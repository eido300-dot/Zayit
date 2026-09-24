package io.github.kdroidfilter.seforimapp.framework.search

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LuceneLookupNormalizeTest {
    // The index directory is opened lazily, so normalization needs no index on disk.
    private val service = LuceneLookupSearchService(Path.of("unused-index"))

    @Test
    fun `curly quotes are stripped like ASCII ones`() {
        val expected = service.normalizeHebrew("רמב\"ם")
        assertEquals("רמבמ", expected)
        assertEquals(expected, service.normalizeHebrew("רמב”ם"))
        assertEquals(expected, service.normalizeHebrew("רמב“ם"))
        assertEquals(expected, service.normalizeHebrew("רמב״ם"))
    }

    @Test
    fun `curly apostrophes are stripped like ASCII ones`() {
        val expected = service.normalizeHebrew("ר'")
        assertEquals("ר", expected)
        assertEquals(expected, service.normalizeHebrew("ר’"))
        assertEquals(expected, service.normalizeHebrew("ר‘"))
        assertEquals(expected, service.normalizeHebrew("ר׳"))
    }

    @Test
    fun `nikud and final letters are normalized`() {
        assertEquals("שלומ", service.normalizeHebrew("  שָׁלוֹם  "))
    }
}

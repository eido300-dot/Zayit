package io.github.kdroidfilter.seforimapp.features.onboarding.download

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DownloadCompletenessTest {
    @Test
    fun `complete download passes`() {
        checkDownloadComplete(received = 1_000, expected = 1_000)
    }

    @Test
    fun `unknown size passes`() {
        checkDownloadComplete(received = 1_000, expected = null)
    }

    @Test
    fun `truncated download fails`() {
        assertFailsWith<IllegalStateException> { checkDownloadComplete(received = 600, expected = 1_000) }
    }

    @Test
    fun `oversized download fails`() {
        assertFailsWith<IllegalStateException> { checkDownloadComplete(received = 1_200, expected = 1_000) }
    }
}

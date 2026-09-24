package io.github.kdroidfilter.seforimapp.core.deeplink

import io.github.kdroidfilter.seforim.tabs.TabsDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ZayitDeepLinkTest {
    @Test
    fun `search link round-trips through share and parse`() {
        val destination = parseZayitDeepLink(searchShareLink("בראשית 100%"))
        assertIs<TabsDestination.Search>(destination)
        assertEquals("בראשית 100%", destination.searchQuery)
    }

    @Test
    fun `malformed percent escape returns null instead of throwing`() {
        assertNull(parseZayitDeepLink("zayit://search/100%"))
        assertNull(parseZayitDeepLink("zayit://search/%zz"))
    }
}

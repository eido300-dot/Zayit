package io.github.kdroidfilter.seforimapp.core.presentation.tabs

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveTabsTest {
    private val all = ('a'..'h').map { it.toString() }.toSet()

    @Test
    fun `selected tab moves to the front`() {
        assertEquals(listOf("c", "a", "b"), nextLiveTabIds(listOf("a", "b", "c"), all, "c"))
    }

    @Test
    fun `least recently selected tab is released past the limit`() {
        val live = nextLiveTabIds(listOf("a", "b", "c", "d", "e"), all, "f", max = 5)
        assertEquals(listOf("f", "a", "b", "c", "d"), live)
    }

    @Test
    fun `closed tabs are dropped`() {
        assertEquals(listOf("a", "c"), nextLiveTabIds(listOf("a", "b", "c"), setOf("a", "c"), "a"))
    }

    @Test
    fun `the tabs composed before the update are the ones kept after it`() {
        val recent = listOf("a", "b", "c", "d", "e")
        // Frame where "f" was just selected: it is composed at once and "e" is no longer composed.
        val composed = composedTabIds(recent, "f", max = 5)
        assertEquals(setOf("f", "a", "b", "c", "d"), composed)
        // The update that follows keeps exactly those, so no composed tab loses its ViewModels.
        assertEquals(composed, nextLiveTabIds(recent, all, "f", max = 5).toSet())
    }

    @Test
    fun `reselecting a live tab changes nothing about which tabs are composed`() {
        val recent = listOf("a", "b", "c", "d", "e")
        assertEquals(recent.toSet(), composedTabIds(recent, "c", max = 5))
    }
}

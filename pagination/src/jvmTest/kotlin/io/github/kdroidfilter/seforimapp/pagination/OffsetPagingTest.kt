package io.github.kdroidfilter.seforimapp.pagination

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OffsetPagingTest {
    /** Serves [rows] through the offset helpers, like the repository-backed sources do. */
    private class ListPagingSource(
        private val rows: List<Int>,
    ) : PagingSource<Int, Int>() {
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = state.offsetRefreshKey()

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            val window = params.offsetWindow()
            val data = rows.drop(window.offset).take(window.limit)
            return window.toPage(data)
        }
    }

    private suspend fun ListPagingSource.page(params: LoadParams<Int>): LoadResult.Page<Int, Int> =
        assertIs<LoadResult.Page<Int, Int>>(load(params))

    @Test
    fun `append after a larger initial load neither repeats nor skips rows`() =
        runTest {
            val rows = (0 until 500).toList()
            val source = ListPagingSource(rows)
            val loaded = mutableListOf<Int>()

            // Same sizes as PagingDefaults: a 256-row first page followed by 64-row pages.
            var page = source.page(LoadParams.Refresh(key = null, loadSize = 256, placeholdersEnabled = false))
            loaded += page.data
            while (true) {
                val next = page.nextKey ?: break
                page = source.page(LoadParams.Append(key = next, loadSize = 64, placeholdersEnabled = false))
                loaded += page.data
            }

            assertEquals(rows, loaded)
        }

    @Test
    fun `prepend loads the rows right before the shown page`() =
        runTest {
            val rows = (0 until 500).toList()
            val source = ListPagingSource(rows)
            val loaded = ArrayDeque<Int>()

            var page = source.page(LoadParams.Refresh(key = 300, loadSize = 64, placeholdersEnabled = false))
            loaded.addAll(page.data)
            while (true) {
                val prev = page.prevKey ?: break
                page = source.page(LoadParams.Prepend(key = prev, loadSize = 64, placeholdersEnabled = false))
                loaded.addAll(0, page.data)
            }

            assertEquals(rows.take(364), loaded.toList())
        }

    @Test
    fun `prepend near the start is clamped to row zero`() {
        val window = LoadParams.Prepend(key = 20, loadSize = 64, placeholdersEnabled = false).offsetWindow()
        assertEquals(OffsetWindow(offset = 0, limit = 20), window)
    }

    @Test
    fun `negative refresh key starts at row zero`() {
        val window = LoadParams.Refresh(key = -5, loadSize = 64, placeholdersEnabled = false).offsetWindow()
        assertEquals(OffsetWindow(offset = 0, limit = 64), window)
    }

    @Test
    fun `page keys mark the ends of the data`() {
        val first = OffsetWindow(offset = 0, limit = 64).toPage(listOf(1, 2, 3))
        assertNull(first.prevKey)
        assertEquals(3, first.nextKey)

        val pastEnd = OffsetWindow(offset = 128, limit = 64).toPage(emptyList<Int>())
        assertEquals(128, pastEnd.prevKey)
        assertNull(pastEnd.nextKey)
    }

    @Test
    fun `refresh key is the start offset of the page at the anchor`() {
        val pages =
            listOf(
                OffsetWindow(offset = 0, limit = 256).toPage((0 until 256).toList()),
                OffsetWindow(offset = 256, limit = 64).toPage((256 until 320).toList()),
            )
        val config = PagingConfig(pageSize = 64, initialLoadSize = 256)

        assertEquals(0, PagingState(pages, anchorPosition = 10, config = config, leadingPlaceholderCount = 0).offsetRefreshKey())
        assertEquals(256, PagingState(pages, anchorPosition = 300, config = config, leadingPlaceholderCount = 0).offsetRefreshKey())
        assertNull(PagingState(pages, anchorPosition = null, config = config, leadingPlaceholderCount = 0).offsetRefreshKey())
    }
}

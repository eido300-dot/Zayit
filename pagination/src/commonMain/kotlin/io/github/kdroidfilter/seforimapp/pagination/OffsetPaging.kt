package io.github.kdroidfilter.seforimapp.pagination

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.math.max

/**
 * Helpers for paging sources keyed by absolute row offset.
 *
 * Keys must be offsets rather than page numbers because Paging loads a larger first page
 * (`initialLoadSize`) than subsequent ones (`pageSize`): with `offset = page * loadSize`,
 * the second page would start inside the first one and repeat its rows.
 *
 * Key semantics: an append/refresh key is the offset of the first row to load; a prepend
 * key is the offset *after* the last row to load (i.e. the start of the page already shown).
 */
internal data class OffsetWindow(
    val offset: Int,
    val limit: Int,
)

internal fun PagingSource.LoadParams<Int>.offsetWindow(): OffsetWindow =
    when (this) {
        is PagingSource.LoadParams.Prepend -> {
            val end = key
            val start = max(0, end - loadSize)
            OffsetWindow(offset = start, limit = end - start)
        }
        else -> OffsetWindow(offset = max(0, key ?: 0), limit = loadSize)
    }

internal fun <T : Any> OffsetWindow.toPage(data: List<T>): PagingSource.LoadResult.Page<Int, T> =
    PagingSource.LoadResult.Page(
        data = data,
        prevKey = if (offset == 0) null else offset,
        nextKey = if (data.isEmpty()) null else offset + data.size,
    )

/** Refreshes from the start offset of the page closest to the anchor. */
internal fun <T : Any> PagingState<Int, T>.offsetRefreshKey(): Int? =
    anchorPosition?.let { anchorPosition ->
        closestPageToPosition(anchorPosition)?.let { page -> page.prevKey ?: 0 }
    }

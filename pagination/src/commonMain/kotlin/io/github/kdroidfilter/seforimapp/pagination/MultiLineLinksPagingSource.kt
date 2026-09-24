package io.github.kdroidfilter.seforimapp.pagination

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import io.github.kdroidfilter.seforimlibrary.dao.repository.CommentaryWithText
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository

/**
 * Paging source for links (TARGUM, SOURCE) attached to multiple lines.
 * Used for multi-line selection to aggregate links from all selected lines.
 */
class MultiLineLinksPagingSource(
    private val repository: SeforimRepository,
    private val lineIds: List<Long>,
    private val sourceBookIds: Set<Long> = emptySet(),
    private val connectionTypes: Set<ConnectionType> = setOf(ConnectionType.TARGUM),
) : PagingSource<Int, CommentaryWithText>() {
    override fun getRefreshKey(state: PagingState<Int, CommentaryWithText>): Int? = state.offsetRefreshKey()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CommentaryWithText> =
        try {
            val window = params.offsetWindow()

            val links =
                repository.getCommentariesForLineRange(
                    lineIds = lineIds,
                    activeCommentatorIds = sourceBookIds,
                    connectionTypes = connectionTypes,
                    offset = window.offset,
                    limit = window.limit,
                    // Dedup source lines that cite multiple target lines in the
                    // selection. Otherwise a single sugya referenced by multiple
                    // halakhot in a TOC heading appears N times in the panel.
                    distinctByTargetLine = lineIds.size > 1,
                )

            window.toPage(links)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
}

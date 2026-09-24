package io.github.kdroidfilter.seforimapp.pagination

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.kdroidfilter.seforimlibrary.dao.repository.CommentaryWithText
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository

class LineCommentsPagingSource(
    private val repository: SeforimRepository,
    private val lineId: Long,
    private val commentatorIds: Set<Long> = emptySet(),
) : PagingSource<Int, CommentaryWithText>() {
    override fun getRefreshKey(state: PagingState<Int, CommentaryWithText>): Int? = state.offsetRefreshKey()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CommentaryWithText> =
        try {
            val window = params.offsetWindow()

            val commentaries =
                repository.getCommentariesForLineRange(
                    lineIds = listOf(lineId),
                    activeCommentatorIds = commentatorIds,
                    connectionTypes = setOf(io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType.COMMENTARY),
                    offset = window.offset,
                    limit = window.limit,
                )

            window.toPage(commentaries)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
}

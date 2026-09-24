package io.github.kdroidfilter.seforimapp.pagination

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.kdroidfilter.seforimlibrary.core.models.ConnectionType
import io.github.kdroidfilter.seforimlibrary.dao.repository.CommentaryWithText
import io.github.kdroidfilter.seforimlibrary.dao.repository.SeforimRepository
import kotlin.math.max
import kotlin.math.min

/**
 * PagingSource that loads commentaries for a single line, or if the line is a TOC heading,
 * for all lines that belong to that TOC entry (section).
 */
class CommentsForLineOrTocPagingSource(
    private val repository: SeforimRepository,
    private val baseLineId: Long,
    private val commentatorIds: Set<Long> = emptySet(),
) : PagingSource<Int, CommentaryWithText>() {
    // Resolved set of lineIds to fetch (single line or section lines). Lazy initialized.
    private var resolvedLineIds: List<Long>? = null

    override fun getRefreshKey(state: PagingState<Int, CommentaryWithText>): Int? = state.offsetRefreshKey()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CommentaryWithText> =
        try {
            val window = params.offsetWindow()

            if (resolvedLineIds == null) {
                val headingToc = repository.getHeadingTocEntryByLineId(baseLineId)
                resolvedLineIds =
                    if (headingToc != null) {
                        sectionWindow(repository.getLineIdsForTocEntry(headingToc.id))
                    } else {
                        listOf(baseLineId)
                    }
            }

            val ids = resolvedLineIds ?: listOf(baseLineId)

            val commentaries =
                repository.getCommentariesForLineRange(
                    lineIds = ids,
                    activeCommentatorIds = commentatorIds,
                    connectionTypes = setOf(ConnectionType.COMMENTARY),
                    offset = window.offset,
                    limit = window.limit,
                )

            window.toPage(commentaries)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }

    /**
     * Same window as the section selection in ContentUseCase (up to [MAX_SECTION_LINES] lines
     * around the heading), so the commentaries match the lines highlighted in the text.
     * The heading line itself is excluded.
     */
    private fun sectionWindow(sectionLineIds: List<Long>): List<Long> {
        val limited =
            if (sectionLineIds.size > MAX_SECTION_LINES) {
                val idx = max(0, sectionLineIds.indexOf(baseLineId))
                val start = max(0, idx - MAX_SECTION_LINES / 2)
                val end = min(sectionLineIds.size, start + MAX_SECTION_LINES)
                sectionLineIds.subList(start, end)
            } else {
                sectionLineIds
            }
        return limited.filter { it != baseLineId }
    }

    companion object {
        const val MAX_SECTION_LINES = 128
    }
}

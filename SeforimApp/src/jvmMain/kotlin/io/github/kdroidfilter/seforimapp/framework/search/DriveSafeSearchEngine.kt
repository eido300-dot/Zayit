package io.github.kdroidfilter.seforimapp.framework.search

import io.github.kdroidfilter.seforimlibrary.search.SearchEngine
import io.github.kdroidfilter.seforimlibrary.search.SearchFacets
import io.github.kdroidfilter.seforimlibrary.search.SearchPage
import io.github.kdroidfilter.seforimlibrary.search.SearchSession
import java.io.IOException
import java.io.UncheckedIOException

/**
 * A [SearchEngine] for a portable copy whose index is read from a drive that can be unplugged.
 *
 * The library opens the index memory-mapped on the JVM. Reading a mapped page after the drive is
 * gone does not fail as an [IOException]: the JVM reports it as an [InternalError] ("a fault
 * occurred in an unsafe memory access operation"), which nothing above would catch. This turns
 * exactly that error into an ordinary I/O failure of the search, so the app shows a failed search
 * instead of closing. It is a safety net, not a guarantee: the JVM delivers the error
 * asynchronously, so a few reads may run on unreadable memory before it arrives. The native builds
 * do not map the index at all.
 */
internal class DriveSafeSearchEngine(
    private val delegate: SearchEngine,
) : SearchEngine by delegate {
    override fun openSession(
        query: String,
        near: Int,
        bookFilter: Long?,
        categoryFilter: Long?,
        bookIds: Collection<Long>?,
        lineIds: Collection<Long>?,
        baseBookOnly: Boolean,
    ): SearchSession? =
        guarded { delegate.openSession(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly) }
            ?.let(::DriveSafeSearchSession)

    override fun searchBooksByTitlePrefix(
        query: String,
        limit: Int,
    ): List<Long> = guarded { delegate.searchBooksByTitlePrefix(query, limit) }

    override fun computeFacets(
        query: String,
        near: Int,
        bookFilter: Long?,
        categoryFilter: Long?,
        bookIds: Collection<Long>?,
        lineIds: Collection<Long>?,
        baseBookOnly: Boolean,
    ): SearchFacets? = guarded { delegate.computeFacets(query, near, bookFilter, categoryFilter, bookIds, lineIds, baseBookOnly) }
}

private class DriveSafeSearchSession(
    private val delegate: SearchSession,
) : SearchSession by delegate {
    override suspend fun nextPage(limit: Int): SearchPage? = guarded { delegate.nextPage(limit) }
}

/** Runs [block], turning a fault on unreadable mapped memory into an I/O failure; other errors pass. */
private inline fun <T> guarded(block: () -> T): T =
    try {
        block()
    } catch (e: InternalError) {
        if (!e.isUnsafeMemoryAccess()) throw e
        throw UncheckedIOException(IOException("the search index could not be read; the drive may have been disconnected", e))
    }

private fun Throwable.isUnsafeMemoryAccess(): Boolean =
    generateSequence(this) { it.cause }.any { it is InternalError && it.message.orEmpty().startsWith("a fault occurred") }

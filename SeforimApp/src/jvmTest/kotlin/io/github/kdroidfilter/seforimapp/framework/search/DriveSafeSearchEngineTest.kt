package io.github.kdroidfilter.seforimapp.framework.search

import io.github.kdroidfilter.seforimlibrary.search.SearchEngine
import io.github.kdroidfilter.seforimlibrary.search.SearchFacets
import io.github.kdroidfilter.seforimlibrary.search.SearchPage
import io.github.kdroidfilter.seforimlibrary.search.SearchSession
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.UncheckedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DriveSafeSearchEngineTest {
    private val unsafe = InternalError("a fault occurred in an unsafe memory access operation")

    private fun fault(): Nothing = throw unsafe

    private class FakeSession(
        private val onNextPage: () -> SearchPage?,
    ) : SearchSession {
        override suspend fun nextPage(limit: Int): SearchPage? = onNextPage()

        override fun close() = Unit
    }

    private class FakeEngine(
        private val onOpen: () -> SearchSession?,
        private val onTitles: () -> List<Long> = { emptyList() },
        private val onFacets: () -> SearchFacets? = { null },
        private val onSemantic: () -> Unit = {},
    ) : SearchEngine {
        var closed = false

        override fun openSession(
            query: String,
            near: Int,
            bookFilter: Long?,
            categoryFilter: Long?,
            bookIds: Collection<Long>?,
            lineIds: Collection<Long>?,
            baseBookOnly: Boolean,
        ): SearchSession? = onOpen()

        override fun searchBooksByTitlePrefix(
            query: String,
            limit: Int,
        ): List<Long> = onTitles()

        override fun buildSnippet(
            rawText: String,
            query: String,
            near: Int,
        ): String = "snippet:$rawText"

        override suspend fun semanticSpan(
            query: String,
            text: String,
        ): String? {
            onSemantic()
            return null
        }

        override suspend fun semanticFind(
            query: String,
            bookId: Long,
            limit: Int,
        ): List<Long> {
            onSemantic()
            return emptyList()
        }

        override suspend fun denseReady(): Boolean {
            onSemantic()
            return false
        }

        override fun computeFacets(
            query: String,
            near: Int,
            bookFilter: Long?,
            categoryFilter: Long?,
            bookIds: Collection<Long>?,
            lineIds: Collection<Long>?,
            baseBookOnly: Boolean,
        ): SearchFacets? = onFacets()

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a fault on the unplugged drive becomes an I O failure in every search call`() {
        val engine = DriveSafeSearchEngine(FakeEngine(onOpen = { fault() }, onTitles = { fault() }, onFacets = { fault() }))

        val failure = assertFailsWith<UncheckedIOException> { engine.openSession("q") }
        assertIs<IOException>(failure.cause)
        assertSame(unsafe, failure.cause?.cause)
        assertFailsWith<UncheckedIOException> { engine.searchBooksByTitlePrefix("q") }
        assertFailsWith<UncheckedIOException> { engine.computeFacets("q") }
    }

    @Test
    fun `a fault while reading a page of an open session is handled too`() =
        runTest {
            val engine = DriveSafeSearchEngine(FakeEngine(onOpen = { FakeSession { throw unsafe } }))
            val session = assertNotNull(engine.openSession("q"))

            assertFailsWith<UncheckedIOException> { session.nextPage(20) }
        }

    @Test
    fun `results and null pass through untouched`() =
        runTest {
            val page = SearchPage(emptyList(), 0, isLastPage = true)
            val engine = DriveSafeSearchEngine(FakeEngine(onOpen = { FakeSession { page } }, onTitles = { listOf(1L, 2L) }))

            assertEquals(page, assertNotNull(engine.openSession("q")).nextPage(20))
            assertEquals(listOf(1L, 2L), engine.searchBooksByTitlePrefix("q"))
            assertNull(engine.computeFacets("q"))
            assertNull(DriveSafeSearchEngine(FakeEngine(onOpen = { null })).openSession("q"))
        }

    @Test
    fun `other errors are not disguised, and the rest of the engine is delegated`() {
        val other = InternalError("something else")
        val inner = FakeEngine(onOpen = { throw other })
        val engine = DriveSafeSearchEngine(inner)

        assertSame(other, assertFailsWith<InternalError> { engine.openSession("q") })
        assertEquals("snippet:x", engine.buildSnippet("x", "q", 5))
        engine.close()
        assertEquals(true, inner.closed)
    }

    @Test
    fun `a fault in the dense vectors of the same index is handled too`() =
        runTest {
            val engine = DriveSafeSearchEngine(FakeEngine(onOpen = { null }, onSemantic = { fault() }))

            assertFailsWith<UncheckedIOException> { engine.semanticSpan("q", "text") }
            assertFailsWith<UncheckedIOException> { engine.semanticFind("q", bookId = 1, limit = 5) }
            assertFailsWith<UncheckedIOException> { engine.denseReady() }
        }
}

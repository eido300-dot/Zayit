package io.github.kdroidfilter.seforimapp.framework.portable

import java.io.IOException
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsWriterTest {
    /** Runs submitted tasks only when the test asks, so coalescing is deterministic. */
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val realExecutors = mutableListOf<ExecutorService>()

    @AfterTest
    fun shutDown() {
        realExecutors.forEach { it.shutdownNow() }
    }

    private fun realExecutor(): ExecutorService = Executors.newSingleThreadExecutor().also { realExecutors += it }

    private fun snapshot(value: Int): Properties = Properties().apply { setProperty("v", value.toString()) }

    @Test
    fun `a burst of changes is coalesced into one write of the latest values`() {
        val executor = ManualExecutor()
        val written = mutableListOf<String>()
        val writer = SettingsWriter(save = { written += it.getProperty("v") }, executor = executor)

        repeat(100) { writer.markDirty(snapshot(it)) }

        assertEquals(1, executor.queue.size)
        executor.runAll()
        assertEquals(listOf("99"), written)
    }

    @Test
    fun `a single change is written without waiting for more changes`() {
        val written = CopyOnWriteArrayList<String>()
        val writer = SettingsWriter(save = { written += it.getProperty("v") }, executor = realExecutor())

        writer.markDirty(snapshot(1))

        assertTrue(writer.flush(timeoutMillis = 5_000))
        assertEquals(listOf("1"), written)
    }

    @Test
    fun `the snapshot is copied so later mutations do not leak into the write`() {
        val executor = ManualExecutor()
        val written = mutableListOf<String>()
        val writer = SettingsWriter(save = { written += it.getProperty("v") }, executor = executor)
        val live = snapshot(1)

        writer.markDirty(live)
        live.setProperty("v", "mutated")
        executor.runAll()

        assertEquals(listOf("1"), written)
    }

    @Test
    fun `a failed write is retried on the next flush`() {
        val executor = ManualExecutor()
        var failNext = true
        val written = mutableListOf<String>()
        val writer =
            SettingsWriter(
                save = {
                    if (failNext) {
                        failNext = false
                        throw IOException("device not ready")
                    }
                    written += it.getProperty("v")
                },
                executor = executor,
            )

        writer.markDirty(snapshot(1))
        executor.runAll()
        assertTrue(written.isEmpty())

        writer.retryPending()
        executor.runAll()
        assertEquals(listOf("1"), written)
    }

    @Test
    fun `a newer change replaces a failed older one`() {
        val executor = ManualExecutor()
        var failNext = true
        val written = mutableListOf<String>()
        val writer =
            SettingsWriter(
                save = {
                    if (failNext) {
                        failNext = false
                        throw IOException("device not ready")
                    }
                    written += it.getProperty("v")
                },
                executor = executor,
            )

        writer.markDirty(snapshot(1))
        executor.runAll()
        writer.markDirty(snapshot(2))
        executor.runAll()

        assertEquals(listOf("2"), written)
    }

    @Test
    fun `flush gives up after the timeout instead of hanging`() {
        val release = CountDownLatch(1)
        val writer =
            SettingsWriter(
                save = { release.await(10, TimeUnit.SECONDS) },
                executor = realExecutor(),
            )
        writer.markDirty(snapshot(1))

        assertFalse(writer.flush(timeoutMillis = 100))
        release.countDown()
    }
}

package io.github.kdroidfilter.seforimapp.framework.portable

import io.github.kdroidfilter.seforimapp.logger.errorln
import io.github.kdroidfilter.seforimapp.logger.warnln
import java.io.IOException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How long an explicit flush (on exit, before a restart) waits before giving up. */
const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 3_000L

/**
 * Writes settings snapshots off the calling thread.
 *
 * Settings setters run on the UI thread (on the Tao backend that is the window's event loop), and
 * a durable save on a USB stick takes tens of milliseconds or more, so saving inline would make
 * typing stutter. A change is written right away, without a debounce delay; changes arriving
 * while a write runs are coalesced into one follow-up write of the latest snapshot. A plain
 * single-thread executor is used instead of a coroutine scope because this runs before any
 * structured scope exists and must outlive none of them.
 *
 * A failed write keeps its snapshot pending and is retried on the next change or flush.
 */
class SettingsWriter(
    private val save: (Properties) -> Unit,
    private val executor: Executor = newWriterExecutor(),
) {
    private val lock = Any()
    private var pending: Properties? = null
    private var queued = false
    private val failureReported = AtomicBoolean(false)

    /** Schedules a write of a copy of [current]; the caller may keep mutating [current]. */
    fun markDirty(current: Properties) {
        val snapshot = current.clone() as Properties
        synchronized(lock) {
            pending = snapshot
            scheduleLocked()
        }
    }

    /** Schedules another attempt for a snapshot whose write failed, if there is one. */
    fun retryPending() {
        synchronized(lock) {
            if (pending != null) scheduleLocked()
        }
    }

    /**
     * Writes anything pending and waits for it. Returns `false` if that did not finish within
     * [timeoutMillis], so a dead drive cannot hang the app on exit.
     */
    fun flush(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean = runExclusive(timeoutMillis) { writePending() }

    /**
     * Runs [block] on the writer thread after every write scheduled so far, and waits for it.
     * Used for operations that must not interleave with a save, such as deleting the files.
     */
    fun runExclusive(
        timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
        block: () -> Unit,
    ): Boolean {
        val done = CountDownLatch(1)
        executor.execute {
            try {
                block()
            } catch (e: IOException) {
                report(e)
            } finally {
                done.countDown()
            }
        }
        return try {
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun scheduleLocked() {
        if (!queued) {
            queued = true
            executor.execute(::writePending)
        }
    }

    private fun writePending() {
        val snapshot =
            synchronized(lock) {
                queued = false
                pending.also { pending = null }
            } ?: return
        try {
            save(snapshot)
        } catch (e: IOException) {
            // Keep the failed snapshot unless a newer one arrived meanwhile.
            synchronized(lock) { if (pending == null) pending = snapshot }
            report(e)
        }
    }

    private fun report(e: IOException) {
        // errorln reaches Sentry; report the first failure only, so a dead drive cannot flood it.
        if (failureReported.compareAndSet(false, true)) {
            errorln(e) { "[portable] could not save settings; will retry on the next change" }
        } else {
            warnln(e) { "[portable] could not save settings" }
        }
    }

    companion object {
        /** A single daemon thread, so pending writes never keep the process alive. */
        fun newWriterExecutor(): Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "zayit-settings-writer").apply { isDaemon = true }
            }
    }
}

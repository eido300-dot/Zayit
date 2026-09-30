package io.github.kdroidfilter.seforimapp.framework.portable

import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriveLockTest {
    private val root: Path = createTempDirectory("zayit-drive-lock")
    private val held = mutableListOf<DriveLock>()

    @AfterTest
    fun cleanUp() {
        held.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    private fun acquire(dir: Path = root): DriveLock.Result =
        DriveLock.tryAcquire(dir).also {
            (it as? DriveLock.Result.Acquired)?.let { a ->
                held +=
                    a.lock
            }
        }

    /** Starts another JVM that holds the lock on [dir], as a second running copy would. */
    private fun holdInAnotherProcess(dir: Path): Process {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .get()
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), DriveLockHolder::class.java.name, dir.toString())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader()
        while (true) {
            val line = assertNotNull(output.readLine(), "the other process ended without locking")
            if (line == "locked") return process
            assertNotEquals("failed", line)
        }
    }

    @Test
    fun `a copy in another process keeps the drive until it exits`() {
        val other = holdInAnotherProcess(root)
        try {
            assertIs<DriveLock.Result.InUse>(DriveLock.tryAcquire(root))
        } finally {
            other.outputStream.close()
            other.waitFor(10, TimeUnit.SECONDS)
            other.destroyForcibly()
        }

        assertIs<DriveLock.Result.Acquired>(acquire())
    }

    @Test
    fun `a restarted copy waits for the old one to let go`() {
        val old = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(root))
        Files.createFile(root.resolve(DriveLock.RESTART_NOTE_NAME))
        var polls = 0

        val result =
            DriveLock.acquireForProcess(root, sleeper = { if (++polls == 3) old.lock.close() })

        held += assertIs<DriveLock.Result.Acquired>(result).lock
        assertEquals(3, polls)
        assertFalse(Files.exists(root.resolve(DriveLock.RESTART_NOTE_NAME)), "the note is used up")
    }

    @Test
    fun `without a restart note a copy in use is reported at once`() {
        acquire()
        var polls = 0

        assertIs<DriveLock.Result.InUse>(DriveLock.tryAcquire(root))
        assertIs<DriveLock.Result.InUse>(acquireInUse { polls++ })
        assertEquals(0, polls)
    }

    @Test
    fun `an old restart note left by a crash does not make a start wait`() {
        acquire()
        val note = Files.createFile(root.resolve(DriveLock.RESTART_NOTE_NAME))
        Files.setLastModifiedTime(note, FileTime.fromMillis(System.currentTimeMillis() - 120_000))
        var polls = 0

        assertIs<DriveLock.Result.InUse>(acquireInUse { polls++ })
        assertEquals(0, polls)
    }

    @Test
    fun `a link planted as the lock file is not opened`() {
        val outside = Files.write(root.resolve("outside.txt"), byteArrayOf(1))
        val drive = Files.createDirectories(root.resolve("drive"))
        Files.createSymbolicLink(drive.resolve(DRIVE_LOCK_NAME), outside)

        assertIs<DriveLock.Result.Unavailable>(DriveLock.tryAcquire(drive))
    }

    private fun acquireInUse(sleeper: (Long) -> Unit): DriveLock.Result = DriveLock.acquireForProcess(root, sleeper = sleeper)

    @Test
    fun `first copy gets the lock and a second one finds the drive in use`() {
        assertIs<DriveLock.Result.Acquired>(acquire())

        assertIs<DriveLock.Result.InUse>(acquire())
    }

    @Test
    fun `lock is free again once released`() {
        val first = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(root))
        first.lock.close()

        assertIs<DriveLock.Result.Acquired>(acquire())
    }

    @Test
    fun `folder that cannot hold the lock file does not block the app`() {
        val notAFolder = Files.createFile(root.resolve("file"))

        assertIs<DriveLock.Result.Unavailable>(acquire(notAFolder))
    }

    @Test
    fun `a second attempt in the same process does not release the first lock`() {
        val first = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(root))
        held += first.lock

        assertIs<DriveLock.Result.InUse>(DriveLock.tryAcquire(root))
        assertIs<DriveLock.Result.InUse>(DriveLock.tryAcquire(root))

        // Another process still finds the drive in use: the first lock was never dropped.
        val other = holdInAnotherProcessOrNull(root)
        assertEquals(null, other, "another process must not get the lock")
    }

    @Test
    fun `a pipe planted as the lock file does not block the start`() {
        val pipe = root.resolve(DRIVE_LOCK_NAME)
        val made = runCatching { ProcessBuilder("mkfifo", pipe.toString()).start().waitFor() }.getOrNull()
        Assume.assumeTrue("mkfifo not available", made == 0)

        val result = assertTimeoutPreemptivelyResult { DriveLock.tryAcquire(root) }

        assertIs<DriveLock.Result.Unavailable>(result)
    }

    @Test
    fun `a restart note is left without releasing the lock and can be forgotten`() {
        val lock = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(root))
        held += lock.lock

        DriveLock.noteRestart(root)
        assertTrue(Files.exists(root.resolve(DriveLock.RESTART_NOTE_NAME)))
        assertIs<DriveLock.Result.InUse>(DriveLock.tryAcquire(root))

        DriveLock.forgetRestart(root)
        assertFalse(Files.exists(root.resolve(DriveLock.RESTART_NOTE_NAME)))
    }

    /** The holder process reports "failed" when it cannot lock; null then means the lock was refused. */
    private fun holdInAnotherProcessOrNull(dir: Path): Process? {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .get()
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), DriveLockHolder::class.java.name, dir.toString())
                .redirectErrorStream(true)
                .start()
        val line = process.inputStream.bufferedReader().readLine()
        if (line == "locked") return process
        process.destroyForcibly()
        return null
    }

    private fun <T> assertTimeoutPreemptivelyResult(block: () -> T): T {
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            return executor.submit(block).get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}

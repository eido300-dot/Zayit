package io.github.kdroidfilter.seforimapp.framework.portable

import org.junit.Assume
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriveLockTest {
    private val root: Path = createTempDirectory("zayit-drive-lock")
    private val argsDir: Path = createTempDirectory("zayit-drive-lock-args")
    private val held = mutableListOf<DriveLock>()

    @AfterTest
    fun cleanUp() {
        held.forEach { it.close() }
        root.toFile().deleteRecursively()
        argsDir.toFile().deleteRecursively()
    }

    private fun acquire(dir: Path = root): DriveLock.Result =
        DriveLock.tryAcquire(dir).also {
            (it as? DriveLock.Result.Acquired)?.let { a ->
                held +=
                    a.lock
            }
        }

    /**
     * Starts [DriveLockHolder] in another JVM on [dir]. The class path goes through an argument
     * file, since on Windows it is longer than a command line may be.
     */
    private fun startHolder(dir: Path): Process {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .get()
        // The launcher reads the file as bytes in the platform encoding, as it reads its command
        // line, so quotes and backslashes are escaped byte by byte: a double-byte character can
        // contain the byte of a backslash.
        val charset = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"))
        val classPath = ByteArrayOutputStream()
        for (byte in System.getProperty("java.class.path").toByteArray(charset)) {
            if (byte == '\\'.code.toByte() || byte == '"'.code.toByte()) classPath.write('\\'.code)
            classPath.write(byte.toInt())
        }
        val argFile = Files.createTempFile(argsDir, "holder", ".args")
        Files.write(argFile, "-cp \"".toByteArray(charset) + classPath.toByteArray() + "\"\n".toByteArray(charset))
        return ProcessBuilder(java, "@$argFile", DriveLockHolder::class.java.name, dir.toString())
            .redirectErrorStream(true)
            .start()
    }

    /** Starts another JVM that holds the lock on [dir], as a second running copy would. */
    private fun holdInAnotherProcess(dir: Path): Process =
        assertNotNull(holdInAnotherProcessOrNull(dir), "the other process could not lock the drive")

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
        other?.destroyForcibly()
        assertEquals(null, other, "another process must not get the lock")
    }

    @Test
    fun `a pipe planted as the lock file does not block the start`() {
        val pipe = root.resolve(DRIVE_LOCK_NAME)
        val made = runCatching { ProcessBuilder("mkfifo", pipe.toString()).start().waitFor() }.getOrNull()
        // The mkfifo of Git Bash on Windows succeeds without making a pipe Java can see.
        Assume.assumeTrue("mkfifo not available", made == 0 && isSpecialFile(pipe))

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

    /**
     * Starts [DriveLockHolder] on [dir] and waits for its answer: the process, still holding the
     * lock, or null when it reported that it could not lock. Other output (JVM notices) is skipped.
     */
    private fun holdInAnotherProcessOrNull(dir: Path): Process? {
        val process = startHolder(dir)
        var locked = false
        try {
            val seen = mutableListOf<String>()
            val answer =
                assertTimeoutPreemptivelyResult(HOLDER_ANSWER_SECONDS) {
                    process.inputStream
                        .bufferedReader()
                        .lineSequence()
                        .onEach { seen += it }
                        .firstOrNull { it == "locked" || it == "failed" }
                }
            assertNotNull(answer, "the other process ended without answering: $seen")
            locked = answer == "locked"
        } finally {
            if (!locked) process.destroyForcibly()
        }
        return if (locked) process else null
    }

    private fun <T> assertTimeoutPreemptivelyResult(
        seconds: Long = 5,
        block: () -> T,
    ): T {
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            return executor.submit(block).get(seconds, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}

/** A second JVM can start slowly on a busy CI machine. */
private const val HOLDER_ANSWER_SECONDS = 60L

private fun isSpecialFile(path: Path): Boolean =
    runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isOther }.getOrDefault(false)

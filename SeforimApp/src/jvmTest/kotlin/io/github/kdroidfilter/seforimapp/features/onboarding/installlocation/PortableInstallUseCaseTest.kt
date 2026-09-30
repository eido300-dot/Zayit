package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.features.onboarding.diskspace.AvailableDiskSpaceUseCase
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_DATA_DIR_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_MARKER_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.resolvePortableLayout
import io.github.kdroidfilter.seforimapp.framework.portable.treeSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** More than two copy chunks, so a copy reports and can stop inside the file. */
private const val LARGE_FILE_BYTES = 20 * 1024 * 1024

class PortableInstallUseCaseTest {
    private val root: Path = createTempDirectory("zayit-portable-install")
    private val programDir: Path = root.resolve("host/Programs/zayit")
    private val executable: Path = programDir.resolve("zayit.exe")
    private val drive: Path = Files.createDirectories(root.resolve("drive"))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    /** A program folder like the per-user Windows install: executable, libraries, uninstaller. */
    private fun createProgram() {
        Files.createDirectories(programDir.resolve("lib"))
        executable.writeText("exe")
        programDir.resolve("lib/a.bin").writeText("a")
        programDir.resolve("lib/b.bin").writeText("b")
        programDir.resolve("lib/c.bin").writeText("c")
        programDir.resolve("Uninstall זית.exe").writeText("uninstaller")
    }

    private fun useCase(
        exe: Path? = executable,
        copyFile: (Path, Path, (Long) -> Unit) -> Unit = ::copyFileDurably,
        canWrite: (Path) -> Boolean = { true },
        isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) },
        move: (Path, Path) -> Unit = ::moveForInstall,
    ) = PortableInstallUseCase(
        executable = { exe },
        copyFile = copyFile,
        canWrite = canWrite,
        isExecutable = isExecutable,
        move = move,
        ioDispatcher = Dispatchers.IO,
    )

    private val destination: Path get() = drive.resolve(PORTABLE_FOLDER_NAME)
    private val staging: Path get() = drive.resolve(PORTABLE_FOLDER_NAME + STAGING_SUFFIX)
    private val previous: Path get() = drive.resolve(PORTABLE_FOLDER_NAME + PREVIOUS_SUFFIX)

    private fun assertNothingLeft() {
        assertFalse(Files.exists(destination, NOFOLLOW_LINKS), "Zayit/ must not exist")
        assertFalse(Files.exists(staging, NOFOLLOW_LINKS), "Zayit.partial/ must not exist")
    }

    /** An existing portable copy on the drive, with notes in its data folder. */
    private fun createExistingCopy() {
        val data = Files.createDirectories(destination.resolve(PORTABLE_DATA_DIR_NAME))
        data.resolve(PORTABLE_MARKER_NAME).writeText("")
        data.resolve("notes.db").writeText("my notes")
        destination.resolve("zayit.exe").writeText("old exe")
        destination.resolve("old-only.bin").writeText("old")
    }

    @Test
    fun `the new copy starts in portable mode`() =
        runBlocking {
            createProgram()

            val path = useCase().install(drive) { _, _ -> }

            assertEquals(destination.toString(), path)
            assertEquals("exe", destination.resolve("zayit.exe").readText())
            assertEquals("c", destination.resolve("lib/c.bin").readText())
            val layout = assertNotNull(resolvePortableLayout(destination.resolve("zayit.exe"), override = null))
            assertEquals(destination.resolve(PORTABLE_DATA_DIR_NAME), layout.dataDir)
            assertFalse(Files.exists(staging))
        }

    @Test
    fun `the uninstaller is not copied`() =
        runBlocking {
            createProgram()

            useCase().install(drive) { _, _ -> }

            assertFalse(Files.exists(destination.resolve("Uninstall זית.exe")))
        }

    @Test
    fun `the data folder of a program that is itself portable is not copied`() =
        runBlocking {
            createProgram()
            Files.createDirectories(programDir.resolve(PORTABLE_DATA_DIR_NAME))
            programDir.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").writeText("someone else's notes")

            useCase().install(drive) { _, _ -> }

            assertFalse(Files.exists(destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db")))
            assertTrue(Files.isRegularFile(destination.resolve("$PORTABLE_DATA_DIR_NAME/$PORTABLE_MARKER_NAME")))
        }

    @Test
    fun `progress reaches the total`() =
        runBlocking {
            createProgram()
            var last = 0L to 0L

            useCase().install(drive) { copied, total -> last = copied to total }

            // 6 bytes (zayit.exe and three libraries) plus one per entry (lib/ and the four files);
            // the uninstaller is left out.
            assertEquals(11L to 11L, last)
        }

    @Test
    fun `a macOS bundle is copied with the data folder next to it`() =
        runBlocking {
            val bundle = root.resolve("host/Applications/זית.app")
            val macExe = bundle.resolve("Contents/MacOS/zayit")
            Files.createDirectories(macExe.parent)
            macExe.writeText("exe")

            useCase(exe = macExe).install(drive) { _, _ -> }

            val copiedExe = destination.resolve("זית.app/Contents/MacOS/zayit")
            assertTrue(Files.isRegularFile(copiedExe))
            val layout = assertNotNull(resolvePortableLayout(copiedExe, override = null))
            assertEquals(destination.resolve(PORTABLE_DATA_DIR_NAME), layout.dataDir)
        }

    @Test
    fun `links are copied as links`() =
        runBlocking {
            createProgram()
            Files.createSymbolicLink(programDir.resolve("lib/current"), Path.of("a.bin"))

            useCase().install(drive) { _, _ -> }

            val link = destination.resolve("lib/current")
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(Path.of("a.bin"), Files.readSymbolicLink(link))
        }

    @Test
    fun `the executable keeps its permission bits`() =
        runBlocking {
            if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return@runBlocking
            createProgram()
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"))

            useCase().install(drive) { _, _ -> }

            val permissions = Files.getPosixFilePermissions(destination.resolve("zayit.exe"))
            assertTrue(PosixFilePermission.OWNER_EXECUTE in permissions)
            assertTrue(PosixFilePermission.OTHERS_EXECUTE in permissions)
        }

    @Test
    fun `cancelling in the middle leaves nothing behind`() =
        runBlocking {
            createProgram()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocking: (Path, Path, (Long) -> Unit) -> Unit = { from, to, onCopied ->
                copyFileDurably(from, to, onCopied)
                if (from.fileName.toString() == "b.bin") {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }

            val job = launch(Dispatchers.Default) { useCase(copyFile = blocking).install(drive) { _, _ -> } }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()

            assertNothingLeft()
        }

    @Test
    fun `a large file is copied in chunks, each reported, so a copy can stop inside it`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES) { it.toByte() })
        val to = root.resolve("large-copy.bin")
        val chunks = mutableListOf<Long>()

        copyFileDurably(from, to) { chunks += it }

        assertTrue(chunks.size >= 3, chunks.toString())
        assertEquals(LARGE_FILE_BYTES.toLong(), chunks.sum())
        assertTrue(Files.readAllBytes(from).contentEquals(Files.readAllBytes(to)))
    }

    @Test
    fun `a copy stops at the first chunk whose report throws`() {
        val from = Files.write(root.resolve("large.bin"), ByteArray(LARGE_FILE_BYTES))
        var reports = 0

        assertFailsWith<CancellationException> {
            copyFileDurably(from, root.resolve("large-copy.bin")) {
                reports++
                throw CancellationException("cancelled")
            }
        }

        assertEquals(1, reports)
    }

    @Test
    fun `a read-only file is copied and keeps its permission bits`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val from = root.resolve("readonly.bin")
        from.writeText("r")
        Files.setPosixFilePermissions(from, PosixFilePermissions.fromString("r--r--r--"))
        val to = root.resolve("readonly-copy.bin")

        copyFileDurably(from, to) { }

        assertEquals("r", to.readText())
        assertEquals(PosixFilePermissions.fromString("r--r--r--"), Files.getPosixFilePermissions(to))
    }

    @Test
    fun `a file that fails to copy fails the whole copy and leaves nothing behind`() =
        runBlocking {
            createProgram()
            val failing: (Path, Path, (Long) -> Unit) -> Unit = { from, to, onCopied ->
                if (from.fileName.toString() == "b.bin") throw IOException("device error")
                copyFileDurably(from, to, onCopied)
            }

            val failure = assertFailsWith<PortableInstallException> { useCase(copyFile = failing).install(drive) { _, _ -> } }

            assertEquals(FailureReason.CopyFailed, failure.reason)
            assertNothingLeft()
        }

    @Test
    fun `an unexpected error still leaves nothing behind`() =
        runBlocking {
            createProgram()
            val failing: (Path, Path, (Long) -> Unit) -> Unit = { _, _, _ -> throw IllegalStateException("bug") }

            assertFailsWith<IllegalStateException> { useCase(copyFile = failing).install(drive) { _, _ -> } }

            assertNothingLeft()
        }

    @Test
    fun `a copy that cannot run from the drive is refused and removed`() =
        runBlocking {
            createProgram()

            val failure = assertFailsWith<PortableInstallException> { useCase(isExecutable = { false }).install(drive) { _, _ -> } }

            assertEquals(FailureReason.NotExecutable, failure.reason)
            assertNothingLeft()
        }

    @Test
    fun `a failed final rename leaves nothing behind`() =
        runBlocking {
            createProgram()

            val failure =
                assertFailsWith<PortableInstallException> {
                    useCase(move = { _, _ -> throw IOException("busy") }).install(drive) { _, _ -> }
                }

            assertEquals(FailureReason.CopyFailed, failure.reason)
            assertNothingLeft()
        }

    @Test
    fun `an existing folder is never replaced`() =
        runBlocking {
            createProgram()
            Files.createDirectories(destination)
            destination.resolve("mine.txt").writeText("keep")

            assertIs<TargetCheck.AlreadyExists>(useCase().check(drive))
            val failure = assertFailsWith<PortableInstallException> { useCase().install(drive) { _, _ -> } }

            assertEquals(FailureReason.AlreadyExists, failure.reason)
            assertEquals("keep", destination.resolve("mine.txt").readText())
        }

    @Test
    fun `an interrupted copy is reported, then replaced by the next one`() =
        runBlocking {
            createProgram()
            Files.createDirectories(staging)
            staging.resolve("half.bin").writeText("half")

            assertIs<TargetCheck.StalePartial>(useCase().check(drive))
            useCase().install(drive) { _, _ -> }

            assertFalse(Files.exists(destination.resolve("half.bin")))
            assertFalse(Files.exists(staging))
        }

    @Test
    fun `an interrupted copy can be removed on request`() =
        runBlocking {
            createProgram()
            Files.createDirectories(staging.resolve("lib"))

            assertTrue(useCase().discardStalePartial(drive))

            assertFalse(Files.exists(staging))
        }

    @Test
    fun `a leftover holding a data folder is never deleted`() =
        runBlocking {
            createProgram()
            Files.createDirectories(staging.resolve(PORTABLE_DATA_DIR_NAME))
            staging.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").writeText("my notes")

            val failure = assertFailsWith<PortableInstallException> { useCase().install(drive) { _, _ -> } }

            assertEquals(FailureReason.UpdateLeftover, failure.reason)
            assertEquals("my notes", staging.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertEquals(TargetCheck.InterruptedUpdate(staging.toString()), useCase().check(drive))
            assertFalse(useCase().discardStalePartial(drive))
        }

    @Test
    fun `an existing portable copy is offered for update`() =
        runBlocking {
            createProgram()
            createExistingCopy()

            assertEquals(TargetCheck.ExistingPortable(destination.toString()), useCase().check(drive))
        }

    @Test
    fun `updating a copy replaces the program and keeps its notes`() =
        runBlocking {
            createProgram()
            createExistingCopy()

            useCase().updateProgram(drive) { _, _ -> }

            assertEquals("exe", destination.resolve("zayit.exe").readText())
            assertFalse(Files.exists(destination.resolve("old-only.bin")))
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(previous))
            assertFalse(Files.exists(staging))
        }

    @Test
    fun `a failed swap puts the previous program and its notes back`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            val calls = AtomicInteger()
            val failingSecond: (Path, Path) -> Unit = { from, to ->
                if (calls.incrementAndGet() == 2) throw IOException("sharing violation")
                moveForInstall(from, to)
            }

            val failure = assertFailsWith<PortableInstallException> { useCase(move = failingSecond).updateProgram(drive) { _, _ -> } }

            assertEquals(FailureReason.CopyFailed, failure.reason)
            assertEquals("old exe", destination.resolve("zayit.exe").readText())
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(previous))
            assertFalse(Files.exists(staging))
        }

    @Test
    fun `a failed last rename puts the notes back with the previous program`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            val calls = AtomicInteger()
            val failingThird: (Path, Path) -> Unit = { from, to ->
                if (calls.incrementAndGet() == 3) throw IOException("sharing violation")
                moveForInstall(from, to)
            }

            assertFailsWith<PortableInstallException> { useCase(move = failingThird).updateProgram(drive) { _, _ -> } }

            assertEquals("old exe", destination.resolve("zayit.exe").readText())
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(previous))
        }

    @Test
    fun `a previous program left with notes in it is never cleared`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            Files.createDirectories(previous.resolve(PORTABLE_DATA_DIR_NAME))
            previous.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").writeText("older notes")

            assertEquals(TargetCheck.InterruptedUpdate(previous.toString()), useCase().check(drive))
            val failure = assertFailsWith<PortableInstallException> { useCase().updateProgram(drive) { _, _ -> } }

            assertEquals(FailureReason.UpdateLeftover, failure.reason)
            assertEquals("old exe", destination.resolve("zayit.exe").readText())
            assertEquals("older notes", previous.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
        }

    @Test
    fun `a previous program left without notes is removed, and the update goes on`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            Files.createDirectories(previous)
            previous.resolve("zayit.exe").writeText("older exe")

            useCase().updateProgram(drive) { _, _ -> }

            assertEquals("exe", destination.resolve("zayit.exe").readText())
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(previous))
        }

    @Test
    fun `an update that could not be undone is reported, then finished on request`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            val calls = AtomicInteger()
            // 3 is the last rename (new program to Zayit), 4 the first step that undoes it.
            val failingLastAndUndo: (Path, Path) -> Unit = { from, to ->
                if (calls.incrementAndGet() in 3..4) throw IOException("sharing violation")
                moveForInstall(from, to)
            }

            val failure = assertFailsWith<PortableInstallException> { useCase(move = failingLastAndUndo).updateProgram(drive) { _, _ -> } }
            assertEquals(FailureReason.UpdateLeftover, failure.reason)
            assertEquals("my notes", staging.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertEquals(TargetCheck.InterruptedUpdate(staging.toString()), useCase().check(drive))

            assertEquals(destination.toString(), useCase().recoverInterruptedUpdate(drive))

            assertEquals("exe", destination.resolve("zayit.exe").readText())
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(staging, NOFOLLOW_LINKS))
            assertFalse(Files.exists(previous, NOFOLLOW_LINKS))
            assertEquals(TargetCheck.ExistingPortable(destination.toString()), useCase().check(drive))
        }

    @Test
    fun `an update stopped with the notes still in the previous program is undone on request`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            val calls = AtomicInteger()
            // 3 is the last rename; its undo moves the notes back (4), then fails to put Zayit back (5).
            val failingLastAndUndo: (Path, Path) -> Unit = { from, to ->
                if (calls.incrementAndGet() in setOf(3, 5)) throw IOException("sharing violation")
                moveForInstall(from, to)
            }

            val failure = assertFailsWith<PortableInstallException> { useCase(move = failingLastAndUndo).updateProgram(drive) { _, _ -> } }
            assertEquals(FailureReason.UpdateLeftover, failure.reason)
            assertEquals(TargetCheck.InterruptedUpdate(previous.toString()), useCase().check(drive))

            useCase().recoverInterruptedUpdate(drive)

            assertEquals("old exe", destination.resolve("zayit.exe").readText())
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(staging, NOFOLLOW_LINKS))
            assertFalse(Files.exists(previous, NOFOLLOW_LINKS))
        }

    @Test
    fun `nothing is put back over a Zayit folder, or when nothing was left aside`() =
        runBlocking {
            createProgram()
            assertEquals(
                FailureReason.UpdateLeftover,
                assertFailsWith<PortableInstallException> { useCase().recoverInterruptedUpdate(drive) }.reason,
            )
            Files.createDirectories(staging.resolve(PORTABLE_DATA_DIR_NAME))
            staging.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").writeText("my notes")
            Files.createDirectories(destination)
            destination.resolve("mine.txt").writeText("keep")

            val failure = assertFailsWith<PortableInstallException> { useCase().recoverInterruptedUpdate(drive) }

            assertEquals(FailureReason.UpdateLeftover, failure.reason)
            assertEquals("keep", destination.resolve("mine.txt").readText())
            assertEquals("my notes", staging.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
        }

    @Test
    fun `a copy that is running is not updated`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            val lock = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(destination.resolve(PORTABLE_DATA_DIR_NAME))).lock

            val failure = lock.use { assertFailsWith<PortableInstallException> { useCase().updateProgram(drive) { _, _ -> } } }

            assertEquals(FailureReason.DriveInUse, failure.reason)
            assertEquals("old exe", destination.resolve("zayit.exe").readText())
            assertFalse(Files.exists(staging, NOFOLLOW_LINKS))
        }

    @Test
    fun `a copy started while its update was being copied is not swapped`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            var lock: DriveLock? = null
            val startingCopy: (Path, Path, (Long) -> Unit) -> Unit = { from, to, onCopied ->
                if (lock == null) {
                    lock = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(destination.resolve(PORTABLE_DATA_DIR_NAME))).lock
                }
                copyFileDurably(from, to, onCopied)
            }

            val failure =
                try {
                    assertFailsWith<PortableInstallException> { useCase(copyFile = startingCopy).updateProgram(drive) { _, _ -> } }
                } finally {
                    lock?.close()
                }

            assertEquals(FailureReason.DriveInUse, failure.reason)
            assertEquals("old exe", destination.resolve("zayit.exe").readText())
            assertEquals("my notes", destination.resolve("$PORTABLE_DATA_DIR_NAME/notes.db").readText())
            assertFalse(Files.exists(staging, NOFOLLOW_LINKS))
        }

    @Test
    fun `a Zayit that is a link to a portable copy is not offered for update`() =
        runBlocking {
            createProgram()
            val elsewhere = root.resolve("elsewhere/Zayit")
            Files.createDirectories(elsewhere.resolve(PORTABLE_DATA_DIR_NAME))
            elsewhere.resolve("$PORTABLE_DATA_DIR_NAME/$PORTABLE_MARKER_NAME").writeText("")
            Files.createSymbolicLink(destination, elsewhere)

            assertIs<TargetCheck.AlreadyExists>(useCase().check(drive))
            val failure = assertFailsWith<PortableInstallException> { useCase().updateProgram(drive) { _, _ -> } }

            assertEquals(FailureReason.Unavailable, failure.reason)
        }

    @Test
    fun `an existing copy in a folder that cannot be written to is refused`() =
        runBlocking {
            createProgram()
            createExistingCopy()

            assertEquals(TargetCheck.NotWritable, useCase(canWrite = { false }).check(drive))
        }

    @Test
    fun `a special file in the program fails the copy rather than being left out`() =
        runBlocking {
            createProgram()
            val fifo = programDir.resolve("lib/pipe")
            val made = runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0 }.getOrDefault(false)
            if (!made) return@runBlocking

            val failure = assertFailsWith<PortableInstallException> { useCase().install(drive) { _, _ -> } }

            assertEquals(FailureReason.CopyFailed, failure.reason)
            assertNothingLeft()
        }

    @Test
    fun `a folder inside the program is refused`() =
        runBlocking {
            createProgram()

            assertEquals(TargetCheck.InsideProgramDir, useCase().check(programDir.resolve("lib")))
        }

    @Test
    fun `a folder that cannot be written to is refused`() =
        runBlocking {
            createProgram()

            assertEquals(TargetCheck.NotWritable, useCase(canWrite = { false }).check(drive))
        }

    @Test
    fun `the space needed includes the program itself`() =
        runBlocking {
            createProgram()
            val required = treeSize(programDir) + AvailableDiskSpaceUseCase.REQUIRED_SPACE_BYTES

            when (val check = useCase().check(drive)) {
                is TargetCheck.Ok -> assertEquals(required, check.required)
                is TargetCheck.NotEnoughSpace -> assertEquals(required, check.required)
                else -> error("unexpected $check")
            }
        }

    @Test
    fun `nothing can be checked or copied without the program's location`() =
        runBlocking {
            assertEquals(TargetCheck.Unavailable, useCase(exe = null).check(drive))
            val failure = assertFailsWith<PortableInstallException> { useCase(exe = null).install(drive) { _, _ -> } }
            assertEquals(FailureReason.Unavailable, failure.reason)
        }

    @Test
    fun `a missing folder is unavailable`() =
        runBlocking {
            createProgram()

            assertEquals(TargetCheck.Unavailable, useCase().check(drive.resolve("gone")))
        }
}

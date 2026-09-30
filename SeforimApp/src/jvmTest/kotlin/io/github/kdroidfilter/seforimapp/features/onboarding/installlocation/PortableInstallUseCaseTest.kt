package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.github.kdroidfilter.seforimapp.features.onboarding.diskspace.AvailableDiskSpaceUseCase
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_DATA_DIR_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.PORTABLE_MARKER_NAME
import io.github.kdroidfilter.seforimapp.framework.portable.resolvePortableLayout
import io.github.kdroidfilter.seforimapp.framework.portable.treeSize
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
        copyFile: (Path, Path) -> Unit = ::copyFileDurably,
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
            var last = 0 to 0

            useCase().install(drive) { copied, total -> last = copied to total }

            // lib/, zayit.exe and three libraries; the uninstaller is left out.
            assertEquals(5 to 5, last)
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
            val blocking: (Path, Path) -> Unit = { from, to ->
                copyFileDurably(from, to)
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
    fun `a file that fails to copy fails the whole copy and leaves nothing behind`() =
        runBlocking {
            createProgram()
            val failing: (Path, Path) -> Unit = { from, to ->
                if (from.fileName.toString() == "b.bin") throw IOException("device error")
                copyFileDurably(from, to)
            }

            val failure = assertFailsWith<PortableInstallException> { useCase(copyFile = failing).install(drive) { _, _ -> } }

            assertEquals(FailureReason.CopyFailed, failure.reason)
            assertNothingLeft()
        }

    @Test
    fun `an unexpected error still leaves nothing behind`() =
        runBlocking {
            createProgram()
            val failing: (Path, Path) -> Unit = { _, _ -> throw IllegalStateException("bug") }

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
    fun `an update left halfway is not touched again`() =
        runBlocking {
            createProgram()
            createExistingCopy()
            Files.createDirectories(previous)

            val failure = assertFailsWith<PortableInstallException> { useCase().updateProgram(drive) { _, _ -> } }

            assertEquals(FailureReason.UpdateLeftover, failure.reason)
            assertEquals("old exe", destination.resolve("zayit.exe").readText())
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

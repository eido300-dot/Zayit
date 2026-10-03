package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstallLocationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val useCase = mockk<PortableInstallUseCase>()
    private val folder = "/media/usb"
    private val path = Path.of(folder)
    private val finalDir = "/media/usb/Zayit"
    private val ok = TargetCheck.Ok(free = 2, required = 1)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { useCase.check(path) } returns ok
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun checkedViewModel(): InstallLocationViewModel =
        InstallLocationViewModel(useCase).apply { onEvent(InstallLocationEvents.FolderPicked(folder)) }

    @Test
    fun `a picked folder is checked`() =
        runTest(dispatcher) {
            assertEquals(InstallLocationState.Checked(folder, ok), checkedViewModel().state.value)
        }

    @Test
    fun `a picked name that is not a path is unavailable, without touching the disk`() =
        runTest(dispatcher) {
            val viewModel = InstallLocationViewModel(useCase)

            viewModel.onEvent(InstallLocationEvents.FolderPicked("bad\u0000name"))

            assertEquals(InstallLocationState.Checked("bad\u0000name", TargetCheck.Unavailable), viewModel.state.value)
            coVerify(exactly = 0) { useCase.check(any()) }
        }

    @Test
    fun `progress is shown while copying, then where the copy is`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            coEvery { useCase.install(path, any()) } coAnswers {
                secondArg<(Long, Long) -> Unit>().invoke(1, 4)
                release.await()
                finalDir
            }
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.StartCopy)
            assertEquals(InstallLocationState.Copying(folder, percent = 25, kind = CopyKind.New), viewModel.state.value)

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(InstallLocationState.Done(finalDir, CopyKind.New), viewModel.state.value)
        }

    @Test
    fun `leaving the screen while copying cancels the copy and starts over`() =
        runTest(dispatcher) {
            val cancelled = CompletableDeferred<Unit>()
            coEvery { useCase.install(path, any()) } coAnswers {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
            val viewModel = checkedViewModel()
            viewModel.onEvent(InstallLocationEvents.StartCopy)
            assertIs<InstallLocationState.Copying>(viewModel.state.value)

            viewModel.onEvent(InstallLocationEvents.ScreenLeft)
            advanceUntilIdle()

            assertEquals(InstallLocationState.Choose, viewModel.state.value)
            assertTrue(cancelled.isCompleted)
        }

    @Test
    fun `a finished copy stays on screen after leaving it, until the user starts over`() =
        runTest(dispatcher) {
            coEvery { useCase.install(path, any()) } returns finalDir
            val viewModel = checkedViewModel()
            viewModel.onEvent(InstallLocationEvents.StartCopy)

            viewModel.onEvent(InstallLocationEvents.ScreenLeft)
            assertEquals(InstallLocationState.Done(finalDir, CopyKind.New), viewModel.state.value)

            viewModel.onEvent(InstallLocationEvents.ChooseAgain)
            assertEquals(InstallLocationState.Choose, viewModel.state.value)
        }

    @Test
    fun `a second start while copying is ignored`() =
        runTest(dispatcher) {
            coEvery { useCase.install(path, any()) } coAnswers { awaitCancellation() }
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.StartCopy)
            viewModel.onEvent(InstallLocationEvents.StartCopy)
            viewModel.onEvent(InstallLocationEvents.UpdateExisting)

            coVerify(exactly = 1) { useCase.install(path, any()) }
            coVerify(exactly = 0) { useCase.updateProgram(any(), any()) }
            viewModel.onEvent(InstallLocationEvents.ScreenLeft)
        }

    @Test
    fun `picking another folder while copying first cancels the copy and waits for its cleanup`() =
        runTest(dispatcher) {
            val log = mutableListOf<String>()
            coEvery { useCase.install(path, any()) } coAnswers {
                // A cleanup in a catch, not a finally: the structured-concurrency compiler plugin crashes
                // on `withContext(NonCancellable)` inside a finally with this Kotlin version.
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        yield()
                        log += "copy cleaned up"
                    }
                    throw e
                }
            }
            val other = "/media/other"
            coEvery { useCase.check(Path.of(other)) } coAnswers {
                log += "other checked"
                TargetCheck.NotWritable
            }
            val viewModel = checkedViewModel()
            viewModel.onEvent(InstallLocationEvents.StartCopy)

            viewModel.onEvent(InstallLocationEvents.FolderPicked(other))
            advanceUntilIdle()

            assertEquals(listOf("copy cleaned up", "other checked"), log)
            assertEquals(InstallLocationState.Checked(other, TargetCheck.NotWritable), viewModel.state.value)
        }

    @Test
    fun `a folder picked right after cancelling shows as being checked while the copy cleans up`() =
        runTest(dispatcher) {
            val cleanup = CompletableDeferred<Unit>()
            coEvery { useCase.install(path, any()) } coAnswers {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { cleanup.await() }
                    throw e
                }
            }
            val other = "/media/other"
            coEvery { useCase.check(Path.of(other)) } returns TargetCheck.NotWritable
            val viewModel = checkedViewModel()
            viewModel.onEvent(InstallLocationEvents.StartCopy)
            viewModel.onEvent(InstallLocationEvents.ChooseAgain)

            viewModel.onEvent(InstallLocationEvents.FolderPicked(other))
            assertEquals(InstallLocationState.Checking(other), viewModel.state.value)

            cleanup.complete(Unit)
            advanceUntilIdle()
            assertEquals(InstallLocationState.Checked(other, TargetCheck.NotWritable), viewModel.state.value)
        }

    @Test
    fun `a folder picked twice while a cancelled copy cleans up is still checked only after the cleanup`() =
        runTest(dispatcher) {
            val cleanup = CompletableDeferred<Unit>()
            val log = mutableListOf<String>()
            coEvery { useCase.install(path, any()) } coAnswers {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        cleanup.await()
                        log += "copy cleaned up"
                    }
                    throw e
                }
            }
            val first = "/media/first"
            val second = "/media/second"
            coEvery { useCase.check(Path.of(first)) } coAnswers {
                log += "first checked"
                TargetCheck.NotWritable
            }
            coEvery { useCase.check(Path.of(second)) } coAnswers {
                log += "second checked"
                TargetCheck.NotWritable
            }
            val viewModel = checkedViewModel()
            viewModel.onEvent(InstallLocationEvents.StartCopy)

            viewModel.onEvent(InstallLocationEvents.FolderPicked(first))
            viewModel.onEvent(InstallLocationEvents.FolderPicked(second))
            cleanup.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("copy cleaned up", "second checked"), log)
            assertEquals(InstallLocationState.Checked(second, TargetCheck.NotWritable), viewModel.state.value)
        }

    @Test
    fun `a cancelled copy that then fails does not replace what the screen shows now`() =
        runTest(dispatcher) {
            val cleanup = CompletableDeferred<Unit>()
            coEvery { useCase.install(path, any()) } coAnswers {
                withContext(NonCancellable) { cleanup.await() }
                throw IOException("the drive was removed")
            }
            val viewModel = checkedViewModel()
            viewModel.onEvent(InstallLocationEvents.StartCopy)
            viewModel.onEvent(InstallLocationEvents.ChooseAgain)

            cleanup.complete(Unit)
            advanceUntilIdle()

            assertEquals(InstallLocationState.Choose, viewModel.state.value)
        }

    @Test
    fun `a program that cannot run from the drive fails with that reason`() =
        runTest(dispatcher) {
            coEvery { useCase.install(path, any()) } throws PortableInstallException(FailureReason.NotExecutable)
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.StartCopy)

            assertEquals(InstallLocationState.Failed(folder, FailureReason.NotExecutable), viewModel.state.value)
        }

    @Test
    fun `any other file error fails as a failed copy`() =
        runTest(dispatcher) {
            coEvery { useCase.install(path, any()) } throws IOException("the drive was removed")
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.StartCopy)

            assertEquals(InstallLocationState.Failed(folder, FailureReason.CopyFailed), viewModel.state.value)
        }

    @Test
    fun `updating an existing copy updates its program and says so`() =
        runTest(dispatcher) {
            coEvery { useCase.check(path) } returns TargetCheck.ExistingPortable(finalDir)
            coEvery { useCase.updateProgram(path, any()) } returns finalDir
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.UpdateExisting)

            assertEquals(InstallLocationState.Done(finalDir, CopyKind.Update), viewModel.state.value)
            coVerify(exactly = 0) { useCase.install(any(), any()) }
        }

    @Test
    fun `removing a stale copy checks the folder again`() =
        runTest(dispatcher) {
            coEvery { useCase.check(path) } returnsMany listOf(TargetCheck.StalePartial("/media/usb/Zayit.partial"), ok)
            coEvery { useCase.discardStalePartial(path) } returns true
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.DeleteStalePartial)

            assertEquals(InstallLocationState.Checked(folder, ok), viewModel.state.value)
            coVerify(exactly = 1) { useCase.discardStalePartial(path) }
        }

    @Test
    fun `a stale copy that cannot be removed fails instead of being offered again`() =
        runTest(dispatcher) {
            coEvery { useCase.check(path) } returns TargetCheck.StalePartial("/media/usb/Zayit.partial")
            coEvery { useCase.discardStalePartial(path) } returns false
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.DeleteStalePartial)

            assertEquals(InstallLocationState.Failed(folder, FailureReason.UpdateLeftover), viewModel.state.value)
        }

    @Test
    fun `an interrupted update is put back on request, and says so`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            coEvery { useCase.check(path) } returns TargetCheck.InterruptedUpdate("/media/usb/Zayit.old")
            coEvery { useCase.recoverInterruptedUpdate(path) } coAnswers {
                release.await()
                finalDir
            }
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.RecoverInterruptedUpdate)
            assertEquals(InstallLocationState.Copying(folder, percent = 0, kind = CopyKind.Recovery), viewModel.state.value)

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(InstallLocationState.Done(finalDir, CopyKind.Recovery), viewModel.state.value)
            coVerify(exactly = 0) { useCase.install(any(), any()) }
            coVerify(exactly = 0) { useCase.updateProgram(any(), any()) }
        }
}

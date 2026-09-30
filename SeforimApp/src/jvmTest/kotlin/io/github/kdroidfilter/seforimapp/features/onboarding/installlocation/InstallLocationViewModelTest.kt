package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
                secondArg<(Int, Int) -> Unit>().invoke(1, 4)
                release.await()
                finalDir
            }
            val viewModel = checkedViewModel()

            viewModel.onEvent(InstallLocationEvents.StartCopy)
            assertEquals(InstallLocationState.Copying(folder, percent = 25, isUpdate = false), viewModel.state.value)

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(InstallLocationState.Done(finalDir, isUpdate = false), viewModel.state.value)
        }

    @Test
    fun `leaving the screen while copying cancels the copy and starts over`() =
        runTest(dispatcher) {
            val cancelled = CompletableDeferred<Unit>()
            coEvery { useCase.install(path, any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
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
            assertEquals(InstallLocationState.Done(finalDir, isUpdate = false), viewModel.state.value)

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
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        yield()
                        log += "copy cleaned up"
                    }
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

            assertEquals(InstallLocationState.Done(finalDir, isUpdate = true), viewModel.state.value)
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
}

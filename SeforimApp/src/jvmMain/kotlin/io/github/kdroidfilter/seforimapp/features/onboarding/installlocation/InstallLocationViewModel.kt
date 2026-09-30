package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.github.kdroidfilter.seforimapp.framework.di.AppScope
import io.github.kdroidfilter.seforimapp.logger.warnln
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class InstallLocationViewModel(
    private val useCase: PortableInstallUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<InstallLocationState>(InstallLocationState.Choose)
    val state: StateFlow<InstallLocationState> = _state.asStateFlow()

    /** The check or copy in progress. Each new one cancels and awaits the previous one first. */
    private var job: Job? = null

    fun onEvent(event: InstallLocationEvents) {
        when (event) {
            is InstallLocationEvents.FolderPicked -> checkFolder(event.path)
            InstallLocationEvents.StartCopy -> checkedFolder()?.let { copy(it, isUpdate = false) }
            InstallLocationEvents.UpdateExisting -> checkedFolder()?.let { copy(it, isUpdate = true) }
            InstallLocationEvents.DeleteStalePartial -> checkedFolder()?.let { discardStaleAndRecheck(it) }
            InstallLocationEvents.ChooseAgain -> reset()
            InstallLocationEvents.ScreenLeft -> if (_state.value !is InstallLocationState.Done) reset()
        }
    }

    private fun checkedFolder(): String? = (_state.value as? InstallLocationState.Checked)?.folder

    private fun checkFolder(folder: String) {
        // Shown at once: a copy cancelled just before may take a moment to clean up first.
        _state.value = InstallLocationState.Checking(folder)
        launchReplacing {
            val path = pathOf(folder)
            _state.value = InstallLocationState.Checked(folder, if (path == null) TargetCheck.Unavailable else useCase.check(path))
        }
    }

    private fun discardStaleAndRecheck(folder: String) =
        launchReplacing {
            val path = pathOf(folder) ?: return@launchReplacing
            _state.value = InstallLocationState.Checking(folder)
            useCase.discardStalePartial(path)
            _state.value = InstallLocationState.Checked(folder, useCase.check(path))
        }

    private fun copy(
        folder: String,
        isUpdate: Boolean,
    ) = launchReplacing {
        val path = pathOf(folder) ?: return@launchReplacing
        _state.value = InstallLocationState.Copying(folder, percent = 0, isUpdate = isUpdate)
        val onProgress = { copied: Long, total: Long ->
            _state.update { current ->
                if (current is InstallLocationState.Copying) current.copy(percent = percentOf(copied, total)) else current
            }
        }
        val outcome =
            try {
                val finalDir = if (isUpdate) useCase.updateProgram(path, onProgress) else useCase.install(path, onProgress)
                InstallLocationState.Done(finalDir, isUpdate)
            } catch (e: PortableInstallException) {
                warnln(e) { "[portable-install] copy to the drive failed: ${e.reason}" }
                InstallLocationState.Failed(folder, e.reason)
            } catch (e: IOException) {
                warnln(e) { "[portable-install] copy to the drive failed" }
                InstallLocationState.Failed(folder, FailureReason.CopyFailed)
            }
        // A copy cancelled meanwhile (the user went back or picked another folder) can still fail
        // while it cleans up; the screen has moved on, so its failure is not shown.
        currentCoroutineContext().ensureActive()
        _state.value = outcome
    }

    private fun reset() {
        job?.cancel()
        _state.value = InstallLocationState.Choose
    }

    private fun launchReplacing(block: suspend () -> Unit) {
        val previous = job
        job =
            viewModelScope.launch {
                previous?.cancelAndJoin()
                block()
            }
    }

    private fun pathOf(folder: String): Path? =
        try {
            Path.of(folder)
        } catch (e: InvalidPathException) {
            warnln(e) { "[portable-install] the picked folder is not a valid path" }
            null
        }
}

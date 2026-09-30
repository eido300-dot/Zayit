package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.kdroidfilter.seforimapp.core.presentation.components.AnimatedHorizontalProgressBar
import io.github.kdroidfilter.seforimapp.core.presentation.components.HardDriveUpload
import io.github.kdroidfilter.seforimapp.core.presentation.utils.LocalWindowViewModelStoreOwner
import io.github.kdroidfilter.seforimapp.core.presentation.utils.formatBytes
import io.github.kdroidfilter.seforimapp.features.onboarding.licence.checkInstalledLibraryReady
import io.github.kdroidfilter.seforimapp.features.onboarding.licence.nextAfterLocalInstallChoice
import io.github.kdroidfilter.seforimapp.features.onboarding.navigation.ProgressBarState
import io.github.kdroidfilter.seforimapp.features.onboarding.ui.components.OnBoardingScaffold
import io.github.kdroidfilter.seforimapp.icons.MaterialSymbolsDesktop_landscape
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineInformationBanner
import org.jetbrains.jewel.ui.component.InlineSuccessBanner
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography
import seforimapp.seforimapp.generated.resources.Res
import seforimapp.seforimapp.generated.resources.install_location_back_button
import seforimapp.seforimapp.generated.resources.install_location_cancel_button
import seforimapp.seforimapp.generated.resources.install_location_checking
import seforimapp.seforimapp.generated.resources.install_location_choose_again
import seforimapp.seforimapp.generated.resources.install_location_close_button
import seforimapp.seforimapp.generated.resources.install_location_copy_button
import seforimapp.seforimapp.generated.resources.install_location_copying
import seforimapp.seforimapp.generated.resources.install_location_done
import seforimapp.seforimapp.generated.resources.install_location_done_recovered
import seforimapp.seforimapp.generated.resources.install_location_done_title
import seforimapp.seforimapp.generated.resources.install_location_done_update
import seforimapp.seforimapp.generated.resources.install_location_drive_button
import seforimapp.seforimapp.generated.resources.install_location_drive_desc
import seforimapp.seforimapp.generated.resources.install_location_drive_title
import seforimapp.seforimapp.generated.resources.install_location_error_already_exists
import seforimapp.seforimapp.generated.resources.install_location_error_filesystem
import seforimapp.seforimapp.generated.resources.install_location_error_inside_program
import seforimapp.seforimapp.generated.resources.install_location_error_not_writable
import seforimapp.seforimapp.generated.resources.install_location_error_path_too_long
import seforimapp.seforimapp.generated.resources.install_location_error_space
import seforimapp.seforimapp.generated.resources.install_location_error_unavailable
import seforimapp.seforimapp.generated.resources.install_location_existing
import seforimapp.seforimapp.generated.resources.install_location_failed_already_exists
import seforimapp.seforimapp.generated.resources.install_location_failed_copy
import seforimapp.seforimapp.generated.resources.install_location_failed_in_use
import seforimapp.seforimapp.generated.resources.install_location_failed_leftover
import seforimapp.seforimapp.generated.resources.install_location_failed_links
import seforimapp.seforimapp.generated.resources.install_location_failed_not_executable
import seforimapp.seforimapp.generated.resources.install_location_finishing
import seforimapp.seforimapp.generated.resources.install_location_folder
import seforimapp.seforimapp.generated.resources.install_location_interrupted
import seforimapp.seforimapp.generated.resources.install_location_local_button
import seforimapp.seforimapp.generated.resources.install_location_local_desc
import seforimapp.seforimapp.generated.resources.install_location_local_title
import seforimapp.seforimapp.generated.resources.install_location_ok
import seforimapp.seforimapp.generated.resources.install_location_recheck_button
import seforimapp.seforimapp.generated.resources.install_location_recover_button
import seforimapp.seforimapp.generated.resources.install_location_recovering
import seforimapp.seforimapp.generated.resources.install_location_stale
import seforimapp.seforimapp.generated.resources.install_location_stale_button
import seforimapp.seforimapp.generated.resources.install_location_title
import seforimapp.seforimapp.generated.resources.install_location_update_button
import seforimapp.seforimapp.generated.resources.install_location_updating
import seforimapp.seforimapp.generated.resources.install_location_warning_eject
import seforimapp.seforimapp.generated.resources.install_location_warning_format
import seforimapp.seforimapp.generated.resources.install_location_warning_links
import seforimapp.seforimapp.generated.resources.install_location_warning_usb2

/** What to keep in mind with a portable copy, shown once it is ready. */
private val DONE_WARNINGS =
    listOf(
        Res.string.install_location_warning_eject,
        Res.string.install_location_warning_format,
        Res.string.install_location_warning_usb2,
        Res.string.install_location_warning_links,
    )

@Composable
fun InstallLocationScreen(
    navController: NavController,
    onExitApplication: () -> Unit,
    progressBarState: ProgressBarState = ProgressBarState,
) {
    val viewModel: InstallLocationViewModel =
        metroViewModel(viewModelStoreOwner = LocalWindowViewModelStoreOwner.current)
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var checkingLibrary by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { progressBarState.setProgress(0.15f) }
    // The view model lives as long as the window: leaving the screen (back, or on to the local
    // install) cancels a copy in progress and forgets the picked folder.
    DisposableEffect(viewModel) {
        onDispose { viewModel.onEvent(InstallLocationEvents.ScreenLeft) }
    }

    InstallLocationView(
        state = state,
        onEvent = viewModel::onEvent,
        onLocalInstall = {
            if (!checkingLibrary) {
                checkingLibrary = true
                scope.launch {
                    val isDatabaseReady =
                        try {
                            checkInstalledLibraryReady()
                        } finally {
                            checkingLibrary = false
                        }
                    navController.navigate(nextAfterLocalInstallChoice(isDatabaseReady))
                }
            }
        },
        onPickDrive = {
            scope.launch {
                // Off the Tao GTK event loop, as in DataSettingsScreen: the picker's D-Bus work blocks.
                val directory = withContext(Dispatchers.IO) { FileKit.openDirectoryPicker() }
                directory?.let { viewModel.onEvent(InstallLocationEvents.FolderPicked(it.path)) }
            }
        },
        onExitApplication = onExitApplication,
    )
}

@Composable
internal fun InstallLocationView(
    state: InstallLocationState,
    onEvent: (InstallLocationEvents) -> Unit,
    onLocalInstall: () -> Unit = {},
    onPickDrive: () -> Unit = {},
    onExitApplication: () -> Unit = {},
) {
    val title = if (state is InstallLocationState.Done) Res.string.install_location_done_title else Res.string.install_location_title
    OnBoardingScaffold(
        title = stringResource(title),
        bottomAction =
            if (state !is InstallLocationState.Choose) {
                { InstallLocationActions(state, onEvent, onPickDrive, onExitApplication) }
            } else {
                null
            },
    ) {
        when (state) {
            InstallLocationState.Choose -> ChooseView(onLocalInstall, onPickDrive)
            is InstallLocationState.Checking -> CheckingView(state)
            is InstallLocationState.Checked -> CheckedView(state)
            is InstallLocationState.Copying -> CopyingView(state)
            is InstallLocationState.Done -> DoneView(state)
            is InstallLocationState.Failed -> FailedView(state)
        }
    }
}

@Composable
private fun InstallLocationActions(
    state: InstallLocationState,
    onEvent: (InstallLocationEvents) -> Unit,
    onPickDrive: () -> Unit,
    onExitApplication: () -> Unit,
) {
    val back = { onEvent(InstallLocationEvents.ChooseAgain) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is InstallLocationState.Checking -> ActionButton(Res.string.install_location_cancel_button, onClick = back)
            is InstallLocationState.Checked -> {
                val primary = primaryActionOf(state.check)
                primary?.let { (label, event) -> ActionButton(label, isDefault = true) { onEvent(event) } }
                ActionButton(Res.string.install_location_choose_again, isDefault = primary == null, onClick = onPickDrive)
                ActionButton(Res.string.install_location_back_button, onClick = back)
            }
            // Once everything is copied only the final rename is left, and a recovery is only renames:
            // there is nothing to cancel.
            is InstallLocationState.Copying -> {
                if (state.kind != CopyKind.Recovery && state.percent < FULL_PERCENT) {
                    ActionButton(Res.string.install_location_cancel_button, onClick = back)
                }
            }
            is InstallLocationState.Done -> {
                ActionButton(Res.string.install_location_close_button, isDefault = true, onClick = onExitApplication)
                ActionButton(Res.string.install_location_back_button, onClick = back)
            }
            is InstallLocationState.Failed -> {
                ActionButton(Res.string.install_location_recheck_button, isDefault = true) {
                    onEvent(InstallLocationEvents.FolderPicked(state.folder))
                }
                ActionButton(Res.string.install_location_choose_again, onClick = onPickDrive)
                ActionButton(Res.string.install_location_back_button, onClick = back)
            }
            InstallLocationState.Choose -> Unit
        }
    }
}

@Composable
private fun ActionButton(
    label: StringResource,
    isDefault: Boolean = false,
    onClick: () -> Unit,
) {
    if (isDefault) {
        DefaultButton(onClick = onClick) { Text(stringResource(label)) }
    } else {
        OutlinedButton(onClick = onClick) { Text(stringResource(label)) }
    }
}

/** The step a checked folder allows besides picking another one, with its button label; null when none. */
internal fun primaryActionOf(check: TargetCheck): Pair<StringResource, InstallLocationEvents>? =
    when (check) {
        is TargetCheck.Ok -> Res.string.install_location_copy_button to InstallLocationEvents.StartCopy
        is TargetCheck.ExistingPortable -> Res.string.install_location_update_button to InstallLocationEvents.UpdateExisting
        is TargetCheck.StalePartial -> Res.string.install_location_stale_button to InstallLocationEvents.DeleteStalePartial
        is TargetCheck.InterruptedUpdate ->
            Res.string.install_location_recover_button to InstallLocationEvents.RecoverInterruptedUpdate
        else -> null
    }

@Composable
private fun ChooseView(
    onLocalInstall: () -> Unit,
    onPickDrive: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        LocationColumn(
            title = stringResource(Res.string.install_location_local_title),
            icon = MaterialSymbolsDesktop_landscape,
            description = stringResource(Res.string.install_location_local_desc),
            buttonText = stringResource(Res.string.install_location_local_button),
            onClick = onLocalInstall,
        )
        Divider(orientation = Orientation.Vertical, modifier = Modifier.fillMaxHeight().width(1.dp))
        LocationColumn(
            title = stringResource(Res.string.install_location_drive_title),
            icon = HardDriveUpload,
            description = stringResource(Res.string.install_location_drive_desc),
            buttonText = stringResource(Res.string.install_location_drive_button),
            onClick = onPickDrive,
        )
    }
}

@Composable
private fun RowScope.LocationColumn(
    title: String,
    icon: ImageVector,
    description: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = JewelTheme.typography.h1TextStyle.fontSize)
        Icon(icon, title, modifier = Modifier.size(72.dp), tint = JewelTheme.globalColors.text.normal)
        Text(description, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        DefaultButton(onClick) { Text(buttonText) }
    }
}

@Composable
private fun CheckingView(state: InstallLocationState.Checking) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(stringResource(Res.string.install_location_checking))
        Text(stringResource(Res.string.install_location_folder, state.folder))
    }
}

@Composable
private fun CheckedView(state: InstallLocationState.Checked) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.install_location_folder, state.folder))
        val message = checkMessage(state.check)
        when (state.check) {
            is TargetCheck.Ok -> InlineSuccessBanner(text = message, modifier = Modifier.fillMaxWidth())
            is TargetCheck.ExistingPortable -> InlineInformationBanner(text = message, modifier = Modifier.fillMaxWidth())
            is TargetCheck.StalePartial, is TargetCheck.InterruptedUpdate ->
                InlineWarningBanner(text = message, modifier = Modifier.fillMaxWidth())
            else -> InlineErrorBanner(text = message, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun checkMessage(check: TargetCheck): String =
    when (check) {
        is TargetCheck.Ok ->
            stringResource(Res.string.install_location_ok, formatBytes(check.free), formatBytes(check.required))
        is TargetCheck.UnsupportedFileSystem -> stringResource(Res.string.install_location_error_filesystem, check.type)
        is TargetCheck.NotEnoughSpace ->
            stringResource(Res.string.install_location_error_space, formatBytes(check.free), formatBytes(check.required))
        TargetCheck.NotWritable -> stringResource(Res.string.install_location_error_not_writable)
        TargetCheck.InsideProgramDir -> stringResource(Res.string.install_location_error_inside_program)
        TargetCheck.PathTooLong -> stringResource(Res.string.install_location_error_path_too_long)
        is TargetCheck.ExistingPortable -> stringResource(Res.string.install_location_existing)
        is TargetCheck.AlreadyExists -> stringResource(Res.string.install_location_error_already_exists)
        is TargetCheck.StalePartial -> stringResource(Res.string.install_location_stale)
        is TargetCheck.InterruptedUpdate -> stringResource(Res.string.install_location_interrupted, check.path)
        TargetCheck.Unavailable -> stringResource(Res.string.install_location_error_unavailable)
    }

@Composable
private fun CopyingView(state: InstallLocationState.Copying) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.install_location_folder, state.folder))
        when {
            state.kind == CopyKind.Recovery -> {
                CircularProgressIndicator()
                Text(stringResource(Res.string.install_location_recovering))
            }
            state.percent >= FULL_PERCENT -> Text(stringResource(Res.string.install_location_finishing))
            else -> {
                val action =
                    stringResource(
                        if (state.kind == CopyKind.Update) Res.string.install_location_updating else Res.string.install_location_copying,
                    )
                Text("$action ${state.percent}%")
            }
        }
        if (state.kind != CopyKind.Recovery) {
            AnimatedHorizontalProgressBar(state.percent / FULL_PERCENT.toFloat(), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DoneView(state: InstallLocationState.Done) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val message =
            when (state.kind) {
                CopyKind.New -> Res.string.install_location_done
                CopyKind.Update -> Res.string.install_location_done_update
                CopyKind.Recovery -> Res.string.install_location_done_recovered
            }
        InlineSuccessBanner(text = stringResource(message, state.finalDir), modifier = Modifier.fillMaxWidth())
        DONE_WARNINGS.forEach { warning ->
            InlineWarningBanner(text = stringResource(warning), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FailedView(state: InstallLocationState.Failed) {
    val message =
        when (state.reason) {
            FailureReason.NotExecutable -> Res.string.install_location_failed_not_executable
            FailureReason.AlreadyExists -> Res.string.install_location_failed_already_exists
            FailureReason.CopyFailed -> Res.string.install_location_failed_copy
            FailureReason.Unavailable -> Res.string.install_location_error_unavailable
            FailureReason.DriveInUse -> Res.string.install_location_failed_in_use
            FailureReason.UpdateLeftover -> Res.string.install_location_failed_leftover
            FailureReason.LinksUnsupported -> Res.string.install_location_failed_links
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.install_location_folder, state.folder))
        InlineErrorBanner(text = stringResource(message), modifier = Modifier.fillMaxWidth())
    }
}

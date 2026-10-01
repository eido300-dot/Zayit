package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.kdroidfilter.seforimapp.theme.PreviewContainer

private const val GIB = 1024L * 1024 * 1024
private const val DRIVE = "E:\\"

@Composable
@Preview
private fun InstallLocationChoosePreview() {
    PreviewContainer { InstallLocationView(InstallLocationState.Choose, onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationCheckingPreview() {
    PreviewContainer { InstallLocationView(InstallLocationState.Checking(DRIVE), onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationReadyPreview() {
    val check = TargetCheck.Ok(free = 60 * GIB, required = 11 * GIB)
    PreviewContainer { InstallLocationView(InstallLocationState.Checked(DRIVE, check), onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationFat32Preview() {
    val check = TargetCheck.UnsupportedFileSystem("FAT32")
    PreviewContainer { InstallLocationView(InstallLocationState.Checked(DRIVE, check), onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationExistingCopyPreview() {
    val check = TargetCheck.ExistingPortable(DRIVE + PORTABLE_FOLDER_NAME)
    PreviewContainer { InstallLocationView(InstallLocationState.Checked(DRIVE, check), onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationStaleCopyPreview() {
    val check = TargetCheck.StalePartial(DRIVE + PORTABLE_FOLDER_NAME + STAGING_SUFFIX)
    PreviewContainer { InstallLocationView(InstallLocationState.Checked(DRIVE, check), onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationInterruptedUpdatePreview() {
    val check = TargetCheck.InterruptedUpdate(DRIVE + PORTABLE_FOLDER_NAME + PREVIOUS_SUFFIX)
    PreviewContainer { InstallLocationView(InstallLocationState.Checked(DRIVE, check), onEvent = {}) }
}

@Composable
@Preview
private fun InstallLocationCopyingPreview() {
    PreviewContainer {
        InstallLocationView(InstallLocationState.Copying(DRIVE, percent = 42, kind = CopyKind.New), onEvent = {})
    }
}

@Composable
@Preview
private fun InstallLocationDonePreview() {
    PreviewContainer {
        InstallLocationView(InstallLocationState.Done(DRIVE + PORTABLE_FOLDER_NAME, CopyKind.New), onEvent = {})
    }
}

@Composable
@Preview
private fun InstallLocationFailedPreview() {
    PreviewContainer { InstallLocationView(InstallLocationState.Failed(DRIVE, FailureReason.DriveInUse), onEvent = {}) }
}

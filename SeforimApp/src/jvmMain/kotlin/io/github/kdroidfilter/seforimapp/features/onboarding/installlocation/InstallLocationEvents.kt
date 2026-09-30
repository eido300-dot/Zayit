package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

sealed interface InstallLocationEvents {
    /** The user picked [path] as the folder to create the portable copy in. */
    data class FolderPicked(
        val path: String,
    ) : InstallLocationEvents

    /** Create a new portable copy in the checked folder. */
    data object StartCopy : InstallLocationEvents

    /** Replace the program of the portable copy found in the checked folder, keeping its data. */
    data object UpdateExisting : InstallLocationEvents

    /** Remove what an interrupted copy left in the checked folder, then check it again. */
    data object DeleteStalePartial : InstallLocationEvents

    /** Back to the first question; a copy in progress is cancelled. */
    data object ChooseAgain : InstallLocationEvents

    /** The screen was left (back, or another step); a copy in progress is cancelled. */
    data object ScreenLeft : InstallLocationEvents
}

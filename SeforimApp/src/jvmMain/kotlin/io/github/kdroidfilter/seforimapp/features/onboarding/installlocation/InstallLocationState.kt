package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

/** What the "where to install" onboarding screen shows. */
sealed interface InstallLocationState {
    /** Asks: on this computer, or on an external drive. */
    data object Choose : InstallLocationState

    data class Checking(
        val folder: String,
    ) : InstallLocationState

    /** The picked [folder] was checked; [check] says whether and how a copy can go there. */
    data class Checked(
        val folder: String,
        val check: TargetCheck,
    ) : InstallLocationState

    data class Copying(
        val folder: String,
        val percent: Int,
        val isUpdate: Boolean,
    ) : InstallLocationState

    /** The portable copy is ready in [finalDir]; the user starts it from there. */
    data class Done(
        val finalDir: String,
        val isUpdate: Boolean,
    ) : InstallLocationState

    data class Failed(
        val folder: String,
        val reason: FailureReason,
    ) : InstallLocationState
}

/** Share of [copied] out of [total], 0 to 100; an empty program counts as done. */
internal fun percentOf(
    copied: Long,
    total: Long,
): Int = if (total <= 0) FULL_PERCENT else (copied.coerceIn(0, total) * FULL_PERCENT / total).toInt()

internal const val FULL_PERCENT = 100

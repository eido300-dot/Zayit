package io.github.kdroidfilter.seforimapp.features.database.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.seforimapp.framework.database.DamagedPart
import io.github.kdroidfilter.seforimapp.framework.database.LibraryProblem
import io.github.kdroidfilter.seforimapp.framework.di.LocalAppGraph
import io.github.kdroidfilter.seforimapp.theme.PreviewContainer
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineWarningBanner
import org.jetbrains.jewel.ui.component.Text
import seforimapp.seforimapp.generated.resources.Res
import seforimapp.seforimapp.generated.resources.library_damaged_banner
import seforimapp.seforimapp.generated.resources.library_degraded_banner
import seforimapp.seforimapp.generated.resources.library_degraded_dismiss
import seforimapp.seforimapp.generated.resources.library_degraded_reinstall
import seforimapp.seforimapp.generated.resources.library_problem_catalog_missing
import seforimapp.seforimapp.generated.resources.library_problem_database_empty
import seforimapp.seforimapp.generated.resources.library_problem_database_missing
import seforimapp.seforimapp.generated.resources.library_problem_database_not_sqlite
import seforimapp.seforimapp.generated.resources.library_problem_database_truncated
import seforimapp.seforimapp.generated.resources.library_problem_database_unreadable
import seforimapp.seforimapp.generated.resources.library_problem_dictionary_missing
import seforimapp.seforimapp.generated.resources.library_problem_lookup_index_missing
import seforimapp.seforimapp.generated.resources.library_problem_text_index_missing
import seforimapp.seforimapp.generated.resources.library_reinstall_did_not_help

/** The user-facing name of a library problem. */
fun LibraryProblem.label(): StringResource =
    when (this) {
        LibraryProblem.DatabaseMissing -> Res.string.library_problem_database_missing
        LibraryProblem.DatabaseNotSqlite -> Res.string.library_problem_database_not_sqlite
        LibraryProblem.DatabaseTruncated -> Res.string.library_problem_database_truncated
        LibraryProblem.DatabaseEmpty -> Res.string.library_problem_database_empty
        LibraryProblem.DatabaseUnreadable -> Res.string.library_problem_database_unreadable
        LibraryProblem.CatalogMissing -> Res.string.library_problem_catalog_missing
        LibraryProblem.TextIndexMissing -> Res.string.library_problem_text_index_missing
        LibraryProblem.LookupIndexMissing -> Res.string.library_problem_lookup_index_missing
        LibraryProblem.DictionaryMissing -> Res.string.library_problem_dictionary_missing
    }

/**
 * What the checks after startup found, shown above the tabs of every main window. One per app, so a
 * banner dismissed in one window is dismissed in all of them.
 */
@Stable
class LibraryBannerState {
    /** Optional parts found missing ([LibraryHealthService.checkOptionalLibraryParts]). */
    var degraded by mutableStateOf(emptyList<LibraryProblem>())

    /** Parts the read-back found damaged on the drive ([LibraryHealthService.checkLibraryAfterStart]). */
    var damaged by mutableStateOf(emptyList<DamagedPart>())

    /** False when a reinstall already ran for these problems and did not fix them. */
    var reinstallHelps by mutableStateOf(true)

    /** True while a reinstall request is restarting the app, so a second click does nothing. */
    var reinstallRequested by mutableStateOf(false)
}

/** [content] under the library banners of [state]; the reinstall offer restarts the app into the installer. */
@Composable
fun LibraryBanner(
    state: LibraryBannerState,
    content: @Composable () -> Unit,
) {
    val appGraph = LocalAppGraph.current
    val healthService = appGraph.libraryHealthService
    val scope = rememberCoroutineScope()
    LibraryDegradedLayout(
        problems = state.degraded,
        onReinstall = {
            if (!state.reinstallRequested) {
                state.reinstallRequested = true
                val problems = (state.degraded + state.damaged.map { it.asProblem() }).distinct()
                // The restart exits without disposing the windows, which is where the session is saved.
                appGraph.sessionManager.saveIfEnabled()
                scope.launch {
                    // Back to normal if the restart did not happen, so the banner still works.
                    try {
                        healthService.requestLibraryReinstall(problems)
                    } finally {
                        state.reinstallRequested = false
                    }
                }
            }
        },
        reinstallHelps = state.reinstallHelps,
        onDismiss = { state.degraded = emptyList() },
        damaged = state.damaged,
        onDismissDamage = { state.damaged = emptyList() },
        content = content,
    )
}

/**
 * Shows [content] under a warning when optional library parts are missing, and under an error when
 * the drive lost part of the library ([damaged]). The books may still open, so the app never
 * reinstalls 7.5 GB on its own: the user decides with [onReinstall]. When a reinstall already ran
 * for the same problems and did not help ([reinstallHelps] false), it is not offered again.
 */
@Composable
fun LibraryDegradedLayout(
    problems: List<LibraryProblem>,
    onReinstall: () -> Unit,
    onDismiss: () -> Unit,
    damaged: List<DamagedPart> = emptyList(),
    onDismissDamage: () -> Unit = {},
    reinstallHelps: Boolean = true,
    content: @Composable () -> Unit,
) {
    val didNotHelp = if (reinstallHelps) "" else " " + stringResource(Res.string.library_reinstall_did_not_help)
    Column(modifier = Modifier.fillMaxSize()) {
        if (damaged.isNotEmpty()) {
            val reinstallLabel = stringResource(Res.string.library_degraded_reinstall)
            val dismissLabel = stringResource(Res.string.library_degraded_dismiss)
            // The banner keeps its first actions; a new key rebuilds it when the offer changes.
            key(reinstallHelps) {
                InlineErrorBanner(
                    text = stringResource(Res.string.library_damaged_banner) + didNotHelp,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    linkActions = {
                        if (reinstallHelps) action(reinstallLabel, onClick = onReinstall)
                        action(dismissLabel, onClick = onDismissDamage)
                    },
                )
            }
        }
        if (problems.isNotEmpty()) {
            val names = problems.map { stringResource(it.label()) }.joinToString(", ")
            val reinstallLabel = stringResource(Res.string.library_degraded_reinstall)
            val dismissLabel = stringResource(Res.string.library_degraded_dismiss)
            key(reinstallHelps) {
                InlineWarningBanner(
                    text = stringResource(Res.string.library_degraded_banner, names) + didNotHelp,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    linkActions = {
                        if (reinstallHelps) action(reinstallLabel, onClick = onReinstall)
                        action(dismissLabel, onClick = onDismiss)
                    },
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
@Preview
private fun LibraryDegradedLayoutPreview() {
    PreviewContainer {
        LibraryDegradedLayout(
            problems = listOf(LibraryProblem.TextIndexMissing, LibraryProblem.DictionaryMissing),
            onReinstall = {},
            onDismiss = {},
        ) { Text("Main window") }
    }
}

@Composable
@Preview
private fun LibraryDamagedLayoutPreview() {
    PreviewContainer {
        LibraryDegradedLayout(
            problems = emptyList(),
            onReinstall = {},
            onDismiss = {},
            damaged = listOf(DamagedPart.Database),
        ) { Text("Main window") }
    }
}

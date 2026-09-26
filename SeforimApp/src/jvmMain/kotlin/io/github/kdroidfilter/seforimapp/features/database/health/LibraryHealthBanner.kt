package io.github.kdroidfilter.seforimapp.features.database.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.seforimapp.framework.database.DamagedPart
import io.github.kdroidfilter.seforimapp.framework.database.LibraryProblem
import io.github.kdroidfilter.seforimapp.theme.PreviewContainer
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
 * Shows [content] under a warning when optional library parts are missing, and under an error when
 * the drive lost part of the library ([damaged]). The books may still open, so the app never
 * reinstalls 7.5 GB on its own: the user decides with [onReinstall].
 */
@Composable
fun LibraryDegradedLayout(
    problems: List<LibraryProblem>,
    onReinstall: () -> Unit,
    onDismiss: () -> Unit,
    damaged: List<DamagedPart> = emptyList(),
    onDismissDamage: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (damaged.isNotEmpty()) {
            val reinstallLabel = stringResource(Res.string.library_degraded_reinstall)
            val dismissLabel = stringResource(Res.string.library_degraded_dismiss)
            InlineErrorBanner(
                text = stringResource(Res.string.library_damaged_banner),
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                linkActions = {
                    action(reinstallLabel, onClick = onReinstall)
                    action(dismissLabel, onClick = onDismissDamage)
                },
            )
        }
        if (problems.isNotEmpty()) {
            val names = problems.map { stringResource(it.label()) }.joinToString(", ")
            val reinstallLabel = stringResource(Res.string.library_degraded_reinstall)
            val dismissLabel = stringResource(Res.string.library_degraded_dismiss)
            InlineWarningBanner(
                text = stringResource(Res.string.library_degraded_banner, names),
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                linkActions = {
                    action(reinstallLabel, onClick = onReinstall)
                    action(dismissLabel, onClick = onDismiss)
                },
            )
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

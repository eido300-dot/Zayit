package io.github.kdroidfilter.seforimapp.features.database.health

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.nucleusframework.application.NucleusApplicationScope
import io.github.kdroidfilter.seforimapp.core.presentation.components.InstallerWindow
import io.github.kdroidfilter.seforimapp.features.onboarding.ui.components.OnBoardingScaffold
import io.github.kdroidfilter.seforimapp.icons.Deployed_code_update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import seforimapp.seforimapp.generated.resources.Res
import seforimapp.seforimapp.generated.resources.app_name
import seforimapp.seforimapp.generated.resources.library_check_close
import seforimapp.seforimapp.generated.resources.library_check_message
import seforimapp.seforimapp.generated.resources.library_check_title

/** How long the startup check may take before the window explains the wait. */
private const val SHOW_AFTER_MILLIS = 700L

/**
 * Stands in for the app while the startup check reads the library files. It stays hidden for a
 * normal check, which takes a fraction of a second, but keeps the app alive meanwhile. On a slow or
 * network drive it appears and explains the wait, with a way out if the drive does not answer.
 */
@Composable
fun NucleusApplicationScope.LibraryCheckWindow() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SHOW_AFTER_MILLIS)
        visible = true
    }
    val progress = remember { MutableStateFlow(0f) }
    InstallerWindow(
        titleBarIcon = Deployed_code_update,
        titleBarText = stringResource(Res.string.app_name),
        progress = progress,
        visible = visible,
    ) {
        OnBoardingScaffold(
            title = stringResource(Res.string.library_check_title),
            bottomAction = {
                DefaultButton(onClick = ::exitApplication) { Text(stringResource(Res.string.library_check_close)) }
            },
        ) {
            Text(
                text = stringResource(Res.string.library_check_message),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

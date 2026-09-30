package io.github.kdroidfilter.seforimapp.features.portable

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.nucleusframework.application.NucleusApplicationScope
import io.github.kdroidfilter.seforimapp.core.presentation.components.InstallerWindow
import io.github.kdroidfilter.seforimapp.features.onboarding.ui.components.OnBoardingScaffold
import io.github.kdroidfilter.seforimapp.icons.Deployed_code_update
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import seforimapp.seforimapp.generated.resources.Res
import seforimapp.seforimapp.generated.resources.app_name
import seforimapp.seforimapp.generated.resources.drive_in_use_close
import seforimapp.seforimapp.generated.resources.drive_in_use_message
import seforimapp.seforimapp.generated.resources.drive_in_use_title

/**
 * Shown instead of the app when another Zayit already has this drive's data folder open, on this
 * computer or another one. Nothing else starts, so this instance writes nothing to the drive.
 */
@Composable
fun NucleusApplicationScope.DriveInUseWindow() {
    val progress = remember { MutableStateFlow(0f) }
    InstallerWindow(
        titleBarIcon = Deployed_code_update,
        titleBarText = stringResource(Res.string.app_name),
        progress = progress,
    ) {
        OnBoardingScaffold(
            title = stringResource(Res.string.drive_in_use_title),
            bottomAction = {
                DefaultButton(onClick = ::exitApplication) { Text(stringResource(Res.string.drive_in_use_close)) }
            },
        ) {
            Text(
                text = stringResource(Res.string.drive_in_use_message),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

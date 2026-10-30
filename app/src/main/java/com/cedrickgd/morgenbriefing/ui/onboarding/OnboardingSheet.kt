package com.cedrickgd.morgenbriefing.ui.onboarding

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cedrickgd.morgenbriefing.ui.components.GlassSheet
import com.cedrickgd.morgenbriefing.util.SystemStatus
import com.cedrickgd.morgenbriefing.util.notificationsNeedSettings
import com.cedrickgd.morgenbriefing.util.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

/**
 * First-start sheet on Today: two quick steps (notifications, your place) and "Los geht's".
 * Swiping it away also counts as done — it should welcome, not nag.
 *
 * @param onDone persist `onboardingDone = true`.
 * @param onChoosePlace close the sheet and open the place picker in the settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(
    onDone: () -> Unit,
    onChoosePlace: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(SystemStatus.read(context)) }
    // Denied for good (or switched off): the button then opens the system settings – say so.
    var needsSettings by remember { mutableStateOf(notificationsNeedSettings(context, activity, status)) }
    fun recheck() {
        status = SystemStatus.read(context)
        needsSettings = notificationsNeedSettings(context, activity, status)
    }
    LifecycleResumeEffect(context) {
        recheck()
        onPauseOrDispose { }
    }
    val requestNotifications = rememberNotificationPermissionRequester { recheck() }

    // Animate the sheet out first, then leave (otherwise it would vanish abruptly).
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // Glass like every sheet (docs/DESIGN.md §2); it also keeps the navigation buttons visible
    // on Android 7, which cannot draw them dark on the light sheet.
    GlassSheet(onDismissRequest = onDone, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Willkommen beim Morgenbriefing ☀️",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Das Wetter vor deiner Tür, dein Tag auf einen Blick und die Nachrichten aus deinen " +
                    "Themen – frisch aus den Redaktionen, direkt auf dein Handy. Zwei kurze Schritte, dann läuft's.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OnboardingStep(
                number = 1,
                title = if (needsSettings) "Benachrichtigungen sind blockiert" else "Benachrichtigungen erlauben",
                text = if (needsSettings) {
                    "Schalte sie in den Systemeinstellungen ein – sonst erreicht dich dein Briefing morgens nicht."
                } else {
                    "Damit dich dein Briefing morgens auch wirklich erreicht."
                },
                done = status.canNotify,
                doneLabel = "Erlaubt",
                actionLabel = if (needsSettings) "Einstellungen öffnen" else "Erlauben",
                onAction = requestNotifications,
            )
            OnboardingStep(
                number = 2,
                title = "Deinen Ort festlegen",
                text = "Wetter und Regionalnachrichten richten sich nach deinem Ort. Suche ihn oder nimm deinen Standort.",
                done = false,
                doneLabel = "",
                actionLabel = "Ort wählen",
                onAction = { closeThen(onChoosePlace) },
            )

            Button(
                onClick = { closeThen(onDone) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text("Los geht's")
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    number: Int,
    title: String,
    text: String,
    done: Boolean,
    doneLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepBadge(number = number, done = done)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (done) {
                    Text(
                        text = "✓ $doneLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    FilledTonalButton(
                        onClick = onAction,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = "Erledigt", modifier = Modifier.size(18.dp))
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

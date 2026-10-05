package com.cedrickgd.morgenbriefing.ui.onboarding

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
import androidx.compose.material3.ModalBottomSheet
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
import com.cedrickgd.morgenbriefing.util.SystemStatus
import com.cedrickgd.morgenbriefing.util.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

/**
 * First-start sheet on Today: two quick steps (notifications, GitHub token) and "Los geht's".
 * Swiping it away also counts as done — it should welcome, not nag.
 *
 * @param hasToken step 2 is shown as done when a token is already stored.
 * @param onDone persist `onboardingDone = true`.
 * @param onOpenSettings close the sheet and go to the settings (token entry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(
    hasToken: Boolean,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(SystemStatus.read(context)) }
    LifecycleResumeEffect(context) {
        status = SystemStatus.read(context)
        onPauseOrDispose { }
    }
    val requestNotifications = rememberNotificationPermissionRequester {
        status = SystemStatus.read(context)
    }

    // Animate the sheet out first, then leave (otherwise it would vanish abruptly).
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Willkommen beim Morgenbriefing ☀️",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Jeden Morgen um halb sechs: das Wetter vor der Tür, dein Tagesverlauf, was du " +
                    "anziehen solltest – und frisch recherchierte News. Zwei kurze Schritte, dann läuft's.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OnboardingStep(
                number = 1,
                title = "Benachrichtigungen erlauben",
                text = "Damit dich dein Briefing morgens auch wirklich erreicht.",
                done = status.canNotify,
                doneLabel = "Erlaubt",
                actionLabel = "Erlauben",
                onAction = requestNotifications,
            )
            OnboardingStep(
                number = 2,
                title = "GitHub-Token eintragen",
                text = "Dann landen hier jeden Morgen deine echten News statt des Demo-Briefings. " +
                    "Dauert zwei Minuten.",
                done = hasToken,
                doneLabel = "Token gespeichert",
                actionLabel = "Zu den Einstellungen",
                onAction = { closeThen(onOpenSettings) },
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

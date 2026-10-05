package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.notify.AlarmScheduler
import com.cedrickgd.morgenbriefing.notify.MorningWorker
import com.cedrickgd.morgenbriefing.util.LinkActions
import com.cedrickgd.morgenbriefing.util.Links
import com.cedrickgd.morgenbriefing.util.SystemIntents
import com.cedrickgd.morgenbriefing.util.SystemStatus
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Dialogs of the settings screen; saveable so they survive rotation. */
private enum class SettingsDialog { TIME, HOME_PLACE, DAY_PLACE, TOKEN, REMOVE_TOKEN }

/**
 * Tab "Einstellungen": appearance, notification (time + everything the system must allow),
 * places and day window, the GitHub token, and "Über". Every change is saved immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tokenCheck by viewModel.tokenCheck.collectAsStateWithLifecycle()
    val placeSearch by viewModel.placeSearch.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val links = rememberLinkActions()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    // The user fixes permissions in the system settings and comes back: re-check on resume.
    var status by remember { mutableStateOf(SystemStatus.read(context)) }
    LifecycleResumeEffect(context) {
        status = SystemStatus.read(context)
        onPauseOrDispose { }
    }
    val requestNotifications = rememberNotificationPermissionRequester {
        status = SystemStatus.read(context)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val actions = SettingsActions(
        onThemeMode = viewModel::setThemeMode,
        onAccent = viewModel::setAccent,
        onNotificationsToggle = { enabled ->
            viewModel.setNotificationsEnabled(enabled)
            if (enabled && !status.canNotify) requestNotifications()
        },
        onEditTime = { dialog = SettingsDialog.TIME },
        onFixNotifications = requestNotifications,
        onFixExactAlarms = { SystemIntents.requestExactAlarms(context) },
        onFixBattery = { SystemIntents.requestIgnoreBatteryOptimizations(context) },
        onOpenAppDetails = { SystemIntents.openAppDetails(context) },
        onTestNotification = {
            if (!status.canNotify) {
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "Erlaube zuerst Benachrichtigungen.",
                        actionLabel = "Erlauben",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) requestNotifications()
                }
            } else {
                val queued = try {
                    MorningWorker.enqueue(context.applicationContext, force = true)
                    true
                } catch (e: IllegalStateException) {
                    false
                }
                scope.launch {
                    snackbar.showSnackbar(
                        if (queued) {
                            "Kommt gleich – die Test-Benachrichtigung ist unterwegs."
                        } else {
                            "Die Test-Benachrichtigung konnte nicht gestartet werden."
                        },
                    )
                }
            }
        },
        onEditPlace = { target ->
            viewModel.resetPlaceSearch()
            dialog = if (target == PlaceTarget.HOME) SettingsDialog.HOME_PLACE else SettingsDialog.DAY_PLACE
        },
        onDayWindow = viewModel::setDayWindow,
        onEditToken = {
            viewModel.resetTokenCheck()
            dialog = SettingsDialog.TOKEN
        },
        onRemoveToken = { dialog = SettingsDialog.REMOVE_TOKEN },
    )

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            LargeTopAppBar(
                title = { Text("Einstellungen") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            val current = settings
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                SettingsContent(
                    settings = current,
                    status = status,
                    links = links,
                    actions = actions,
                    bottomPadding = bottomContentInset(contentPadding),
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .horizontalSafeDrawing()
                .padding(bottom = bottomContentInset(contentPadding)),
        )
    }

    val current = settings ?: return
    when (dialog) {
        SettingsDialog.TIME -> NotifyTimeDialog(
            hour = current.notifyHour,
            minute = current.notifyMinute,
            onDismiss = { dialog = null },
            onConfirm = { hour, minute ->
                dialog = null
                viewModel.setNotifyTime(hour, minute)
            },
        )

        SettingsDialog.HOME_PLACE, SettingsDialog.DAY_PLACE -> {
            val target = if (dialog == SettingsDialog.HOME_PLACE) PlaceTarget.HOME else PlaceTarget.DAY
            PlacePickerDialog(
                target = target,
                current = if (target == PlaceTarget.HOME) current.home else current.day,
                search = placeSearch,
                onQueryChange = viewModel::onPlaceQueryChange,
                onPick = { place ->
                    dialog = null
                    viewModel.setPlace(target, place)
                },
                onDismiss = { dialog = null },
            )
        }

        SettingsDialog.TOKEN -> TokenDialog(
            hasToken = current.hasToken,
            check = tokenCheck,
            onSaveAndTest = viewModel::saveAndTestToken,
            onOpenTokenPage = { links.open(Links.NEW_GITHUB_TOKEN) },
            onDismiss = {
                dialog = null
                viewModel.resetTokenCheck()
            },
        )

        SettingsDialog.REMOVE_TOKEN -> RemoveTokenDialog(
            onConfirm = {
                dialog = null
                viewModel.removeToken()
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

/** Callbacks of [SettingsContent], bundled to keep the composable signature readable. */
private class SettingsActions(
    val onThemeMode: (ThemeMode) -> Unit,
    val onAccent: (AccentId) -> Unit,
    val onNotificationsToggle: (Boolean) -> Unit,
    val onEditTime: () -> Unit,
    val onFixNotifications: () -> Unit,
    val onFixExactAlarms: () -> Unit,
    val onFixBattery: () -> Unit,
    val onOpenAppDetails: () -> Unit,
    val onTestNotification: () -> Unit,
    val onEditPlace: (PlaceTarget) -> Unit,
    val onDayWindow: (start: Int, end: Int) -> Unit,
    val onEditToken: () -> Unit,
    val onRemoveToken: () -> Unit,
)

@Composable
private fun SettingsContent(
    settings: AppSettings,
    status: SystemStatus,
    links: LinkActions,
    actions: SettingsActions,
    bottomPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalSafeDrawing()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomPadding + 24.dp),
    ) {
        SettingsSectionHeader("Darstellung", top = 4.dp)
        AppearanceCard(settings, actions)

        SettingsSectionHeader("Benachrichtigung")
        NotificationCard(settings, status, actions)

        SettingsSectionHeader("Orte")
        PlacesCard(settings, actions)

        SettingsSectionHeader("News-Quelle")
        NewsSourceCard(settings, actions)

        SettingsSectionHeader("Über")
        AboutCard(links)
    }
}

@Composable
private fun AppearanceCard(settings: AppSettings, actions: SettingsActions) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SettingsCard {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Design", style = MaterialTheme.typography.labelLarge)
            ThemeModeSelector(selected = settings.themeMode, onSelect = actions.onThemeMode)
            Text("Akzentfarbe", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            AccentPicker(selected = settings.accent, darkTheme = darkTheme, onSelect = actions.onAccent)
        }
    }
}

@Composable
private fun NotificationCard(settings: AppSettings, status: SystemStatus, actions: SettingsActions) {
    val enabled = settings.notificationsEnabled
    SettingsCard {
        SettingsRow(
            icon = if (enabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
            title = "Morgenbriefing",
            subtitle = "Jeden Morgen Wetter & News als Benachrichtigung",
            modifier = Modifier.toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = actions.onNotificationsToggle,
            ),
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
        SettingsDivider()
        SettingsRow(
            icon = Icons.Rounded.Schedule,
            title = "Uhrzeit",
            subtitle = if (enabled) nextBriefingText(settings) else "Aus – es kommt keine Benachrichtigung",
            enabled = enabled,
            onClickLabel = "Uhrzeit ändern",
            onClick = actions.onEditTime,
            trailing = {
                Text(
                    text = clockLabel(settings.notifyHour, settings.notifyMinute),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        SettingsDivider()
        StatusRow(
            icon = if (status.canNotify) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff,
            title = "Benachrichtigungen",
            ok = status.canNotify,
            okText = "Erlaubt",
            problemText = if (status.permissionGranted && status.appNotificationsEnabled) {
                "Der Kanal „Morgenbriefing“ ist ausgeschaltet."
            } else {
                "Nicht erlaubt – morgens kommt nichts an."
            },
            actionLabel = "Erlauben",
            onAction = actions.onFixNotifications,
        )
        if (status.exactAlarmRelevant) {
            SettingsDivider()
            StatusRow(
                icon = Icons.Rounded.Alarm,
                title = "Exakte Uhrzeit",
                ok = status.exactAlarmsAllowed,
                okText = "Erlaubt – kommt auf die Minute",
                problemText = "Nicht erlaubt – das Briefing kann sich um ein paar Minuten verspäten.",
                actionLabel = "Erlauben",
                onAction = actions.onFixExactAlarms,
            )
        }
        SettingsDivider()
        StatusRow(
            icon = Icons.Rounded.BatteryChargingFull,
            title = "Akku-Optimierung",
            ok = status.batteryUnrestricted,
            okText = "Aus – Android bremst dein Briefing nicht",
            problemText = "An – Android kann die Benachrichtigung verzögern.",
            actionLabel = "Akku-Optimierung aus",
            onAction = actions.onFixBattery,
            hint = "Bei Samsung zusätzlich: App-Akku auf „Nicht eingeschränkt“.",
            secondaryActionLabel = "App-Info öffnen",
            onSecondaryAction = actions.onOpenAppDetails,
        )
        FilledTonalButton(
            onClick = actions.onTestNotification,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Test-Benachrichtigung senden")
        }
    }
}

@Composable
private fun PlacesCard(settings: AppSettings, actions: SettingsActions) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Rounded.Home,
            title = "Vor der Tür",
            subtitle = settings.home.displayLabel(),
            onClickLabel = "Ort ändern",
            onClick = { actions.onEditPlace(PlaceTarget.HOME) },
            trailing = { ChevronIcon() },
        )
        SettingsDivider()
        SettingsRow(
            icon = Icons.Rounded.LocationCity,
            title = "Tagsüber",
            subtitle = settings.day.displayLabel(),
            onClickLabel = "Ort ändern",
            onClick = { actions.onEditPlace(PlaceTarget.DAY) },
            trailing = { ChevronIcon() },
        )
        SettingsDivider()
        DayWindowRow(
            start = settings.dayStartHour,
            end = settings.dayEndHour,
            placeName = settings.day.name,
            onCommit = actions.onDayWindow,
        )
    }
}

@Composable
private fun DayWindowRow(start: Int, end: Int, placeName: String, onCommit: (Int, Int) -> Unit) {
    // Local while dragging, written once the finger lifts; resets when the stored value changes.
    var range by remember(start, end) { mutableStateOf(DayWindow.normalize(start, end)) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text("Zeitfenster", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Tagesverlauf in $placeName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = DayWindow.label(range.first, range.second),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        RangeSlider(
            value = range.first.toFloat()..range.second.toFloat(),
            onValueChange = { value ->
                range = DayWindow.constrain(
                    previousStart = range.first,
                    previousEnd = range.second,
                    start = value.start.roundToInt(),
                    end = value.endInclusive.roundToInt(),
                )
            },
            onValueChangeFinished = { onCommit(range.first, range.second) },
            valueRange = DayWindow.MIN_HOUR.toFloat()..DayWindow.MAX_HOUR.toFloat(),
            steps = DayWindow.SLIDER_STEPS,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HourCaption("${DayWindow.MIN_HOUR} Uhr")
            HourCaption("${DayWindow.MAX_HOUR} Uhr")
        }
    }
}

@Composable
private fun HourCaption(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NewsSourceCard(settings: AppSettings, actions: SettingsActions) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Rounded.Key,
            title = "GitHub-Token",
            subtitle = if (settings.hasToken) "Token gespeichert ✓" else "Kein Token – Demo-Modus",
        )
        Text(
            text = "Die News kommen jeden Morgen aus deinem privaten Repo " +
                "${BuildConfig.BRIEF_OWNER}/${BuildConfig.BRIEF_REPO}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = actions.onEditToken, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (settings.hasToken) "Token ändern" else "Token eintragen")
            }
            if (settings.hasToken) {
                OutlinedButton(onClick = actions.onRemoveToken, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Token entfernen")
                }
            }
        }
    }
}

@Composable
private fun AboutCard(links: LinkActions) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Rounded.Info,
            title = "Version",
            subtitle = BuildConfig.VERSION_NAME,
        )
        SettingsDivider()
        SettingsRow(
            icon = Icons.Rounded.Cloud,
            title = "Wetterdaten",
            subtitle = "Open-Meteo.com (CC BY 4.0)",
            onClickLabel = "Website öffnen",
            onClick = { links.open(Links.OPEN_METEO) },
            trailing = { ExternalIcon() },
        )
        SettingsDivider()
        SettingsRow(
            icon = Icons.Rounded.Code,
            title = "Quellcode",
            subtitle = "github.com/CedrickGD/Morgenbriefing",
            onClickLabel = "Repository öffnen",
            onClick = { links.open(Links.SOURCE_CODE) },
            trailing = { ExternalIcon() },
        )
    }
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExternalIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** "Nächstes Briefing: morgen um 05:30" based on the alarm scheduler's next trigger. */
private fun nextBriefingText(settings: AppSettings): String {
    val now = ZonedDateTime.now(BERLIN)
    val next = AlarmScheduler.nextTrigger(now, settings.notifyHour, settings.notifyMinute)
    return nextBriefingLabel(now, next)
}

package com.cedrickgd.morgenbriefing.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyListState
import com.cedrickgd.morgenbriefing.ai.AiSupport
import com.cedrickgd.morgenbriefing.ai.UsageSnapshot
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.ThemeMode
import com.cedrickgd.morgenbriefing.data.model.WeatherSnapshot
import com.cedrickgd.morgenbriefing.notify.AlarmScheduler
import com.cedrickgd.morgenbriefing.notify.MorningWorker
import com.cedrickgd.morgenbriefing.ui.glass.PageTitle
import com.cedrickgd.morgenbriefing.ui.glass.TopGlassBar
import com.cedrickgd.morgenbriefing.ui.shell.reportChromeScroll
import com.cedrickgd.morgenbriefing.ui.theme.rememberReduceMotion
import com.cedrickgd.morgenbriefing.util.Links
import com.cedrickgd.morgenbriefing.util.SystemIntents
import com.cedrickgd.morgenbriefing.util.SystemStatus
import com.cedrickgd.morgenbriefing.util.bottomContentInset
import com.cedrickgd.morgenbriefing.util.horizontalSafeDrawing
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import com.cedrickgd.morgenbriefing.util.rememberNotificationPermissionRequester
import com.cedrickgd.morgenbriefing.util.statusBarTop
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Dialogs of the settings page; saveable so they survive rotation. */
private enum class SettingsDialog { TIME, HOME_PLACE, DAY_PLACE, TOKEN, REMOVE_TOKEN, AI_KEY, REMOVE_AI_KEY }

/** Lazy-list keys of the page, in display order. */
private object Block {
    const val TITLE = "title"
    const val LOADING = "loading"
    const val HERO = "hero"
    const val H_BRIEFING = "h-briefing"
    const val BRIEFING = "briefing"
    const val H_LOOK = "h-look"
    const val LOOK = "look"
    const val H_TOPICS = "h-topics"
    const val TOPICS = "topics"
    const val H_PLACES = "h-places"
    const val PLACES = "places"
    const val H_READING = "h-reading"
    const val READING = "reading"
    const val H_AI = "h-ai"
    const val AI = "ai"
    const val H_SOURCE = "h-source"
    const val SOURCE = "source"
    const val H_APP = "h-app"
    const val APP = "app"
    const val UPDATE = "update"
    const val LINKS = "links"
    const val FOOTER = "footer"

    val ALL = listOf(
        TITLE, HERO, H_BRIEFING, BRIEFING, H_LOOK, LOOK, H_TOPICS, TOPICS, H_PLACES, PLACES,
        H_READING, READING, H_AI, AI, H_SOURCE, SOURCE, H_APP, APP, UPDATE, LINKS, FOOTER,
    )
}

/**
 * Page "Einstellungen" (docs/DESIGN.md §4, §7): transparent over the sky, page title under the
 * status bar and a frosted top bar once it scrolled away. Sections: status hero, Morgenbriefing,
 * Darstellung, Themen, Orte & Zeitfenster, Lesen, KI, News-Quelle, App. Every change is saved
 * immediately.
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tokenCheck by viewModel.tokenCheck.collectAsStateWithLifecycle()
    val placeSearch by viewModel.placeSearch.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val aiKey by viewModel.aiKey.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val update by updateViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val links = rememberLinkActions()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val reduceMotion = rememberReduceMotion()
    val stagger = rememberStagger(enabled = !reduceMotion)
    val now = rememberMinuteClock()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    val density = LocalDensity.current
    val topBarPx = with(density) { (statusBarTop() + TOP_BAR_HEIGHT).toPx() }
    val titleThresholdPx = with(density) { TITLE_SCROLL_THRESHOLD.toPx() }
    val showTopBar by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > titleThresholdPx }
    }

    // The user fixes permissions in the system settings and comes back: re-check on resume.
    var status by remember { mutableStateOf(SystemStatus.read(context)) }
    LifecycleResumeEffect(context, updateViewModel, viewModel) {
        status = SystemStatus.read(context)
        updateViewModel.onResume()
        viewModel.onResume()
        onPauseOrDispose { updateViewModel.onPause() }
    }
    val requestNotifications = rememberNotificationPermissionRequester {
        status = SystemStatus.read(context)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    fun scrollTo(key: String) {
        val index = Block.ALL.indexOf(key)
        if (index < 0) return
        scope.launch {
            val offset = -topBarPx.toInt()
            if (reduceMotion) listState.scrollToItem(index, offset) else listState.animateScrollToItem(index, offset)
        }
    }

    fun openTokenDialog() {
        viewModel.resetTokenCheck()
        dialog = SettingsDialog.TOKEN
    }

    fun openAiKeyDialog() {
        viewModel.resetAiKeyState()
        dialog = SettingsDialog.AI_KEY
    }

    val onTestNotification: () -> Unit = {
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
                    if (queued) "Kommt gleich – die Test-Benachrichtigung ist unterwegs." else "Die Test-Benachrichtigung konnte nicht gestartet werden.",
                )
            }
        }
    }

    val onPill: (StatusItem) -> Unit = { item ->
        when (item.level) {
            StatusLevel.ATTENTION -> when (item.kind) {
                StatusKind.NOTIFICATIONS -> requestNotifications()
                StatusKind.EXACT_ALARM -> SystemIntents.requestExactAlarms(context)
                StatusKind.BATTERY -> SystemIntents.requestIgnoreBatteryOptimizations(context)
                StatusKind.NEWS -> {
                    val failed = source as? SourceStatus.Failed
                    if (failed != null && failed.kind == BriefErrorKind.NETWORK) {
                        viewModel.checkSource(force = true)
                        scrollTo(Block.H_SOURCE)
                    } else {
                        openTokenDialog()
                    }
                }
                StatusKind.AI -> openAiKeyDialog()
            }
            StatusLevel.NEUTRAL -> if (item.kind == StatusKind.AI) openAiKeyDialog() else scrollTo(Block.H_SOURCE)
            StatusLevel.OK -> scrollTo(
                when (item.kind) {
                    StatusKind.NEWS -> Block.H_SOURCE
                    StatusKind.AI -> Block.H_AI
                    else -> Block.H_BRIEFING
                },
            )
        }
    }

    val updateActions = UpdateCardActions(
        onCheck = updateViewModel::check,
        onDownload = updateViewModel::downloadAndInstall,
        onCancel = updateViewModel::cancel,
        onInstall = { ready -> updateViewModel.install(ready.release, ready.apk) },
        onAllowInstall = {
            if (!updateViewModel.openInstallPermission()) {
                scope.launch { snackbar.showSnackbar("Die Einstellung ließ sich nicht öffnen.") }
            }
        },
        onDismissError = updateViewModel::dismissError,
        onGoToToken = { scrollTo(Block.H_SOURCE) },
    )

    val aiActions = AiActions(
        onEditKey = ::openAiKeyDialog,
        onModel = viewModel::setAiModel,
        onLimit = viewModel::setAiMonthlyLimit,
        onLiveAuto = viewModel::setLiveAutoRefresh,
    )

    val current = settings
    val keys = if (current == null) listOf(Block.TITLE, Block.LOADING) else Block.ALL

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .reportChromeScroll()
                .horizontalSafeDrawing(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomContentInset(contentPadding) + 32.dp),
        ) {
            items(keys, key = { it }, contentType = { if (it.startsWith("h-")) "heading" else it }) { key ->
                val index = keys.indexOf(key)
                val enter = Modifier
                    .fillMaxWidth()
                    .staggerIn(stagger, key, index)
                if (key == Block.TITLE) {
                    PageTitle(overline = "Dein Morgen", title = "Einstellungen", modifier = enter.padding(horizontal = 4.dp))
                    return@items
                }
                if (current == null) {
                    HeroPlaceholder(enter.padding(top = 16.dp))
                    return@items
                }
                SettingsBlock(
                    key = key,
                    settings = current,
                    modifier = enter,
                    content = BlockContent(
                        status = status,
                        source = source,
                        now = now,
                        weather = weather,
                        usage = usage,
                        update = update,
                        reduceMotion = reduceMotion,
                    ),
                    callbacks = BlockCallbacks(
                        onPill = onPill,
                        onOpenAppDetails = { SystemIntents.openAppDetails(context) },
                        onNotificationsToggle = { enabled ->
                            viewModel.setNotificationsEnabled(enabled)
                            if (enabled && !status.canNotify) requestNotifications()
                        },
                        onEditTime = { dialog = SettingsDialog.TIME },
                        onTestNotification = onTestNotification,
                        onThemeMode = viewModel::setThemeMode,
                        onAccent = viewModel::setAccent,
                        onTopicVisible = viewModel::setTopicVisible,
                        onTopicOrder = viewModel::setTopicOrder,
                        onResetTopics = viewModel::resetTopics,
                        onEditPlace = { target ->
                            viewModel.resetPlaceSearch()
                            dialog = if (target == PlaceTarget.HOME) SettingsDialog.HOME_PLACE else SettingsDialog.DAY_PLACE
                        },
                        onDayWindow = viewModel::setDayWindow,
                        onReaderScale = viewModel::setReaderTextScale,
                        ai = aiActions,
                        onEditToken = ::openTokenDialog,
                        onRemoveToken = { dialog = SettingsDialog.REMOVE_TOKEN },
                        onRecheckSource = { viewModel.checkSource(force = true) },
                        update = updateActions,
                        onOpenLink = links::open,
                    ),
                    listState = listState,
                    topObstruction = topBarPx,
                )
            }
        }
        TopGlassBar(title = "Einstellungen", visible = showTopBar)
        GlassSnackbarHost(
            state = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .horizontalSafeDrawing()
                .padding(bottom = bottomContentInset(contentPadding)),
        )
    }

    current ?: return
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

        SettingsDialog.AI_KEY -> AiKeyDialog(
            hasKey = current.hasAiKey,
            state = aiKey,
            onSaveAndTest = viewModel::saveAndTestAiKey,
            onOpenConsole = { links.open(ANTHROPIC_KEYS_URL) },
            onRemove = { dialog = SettingsDialog.REMOVE_AI_KEY },
            onDismiss = {
                dialog = null
                viewModel.resetAiKeyState()
            },
        )

        SettingsDialog.REMOVE_AI_KEY -> RemoveAiKeyDialog(
            onConfirm = {
                dialog = null
                viewModel.resetAiKeyState()
                viewModel.removeAiKey()
            },
            onDismiss = { dialog = SettingsDialog.AI_KEY },
        )

        null -> Unit
    }
}

/** Values every block may need, bundled to keep [SettingsBlock]'s signature readable. */
private class BlockContent(
    val status: SystemStatus,
    val source: SourceStatus,
    val now: ZonedDateTime,
    val weather: WeatherSnapshot?,
    val usage: UsageSnapshot,
    val update: UpdatePanelState,
    val reduceMotion: Boolean,
)

/** Callbacks of the blocks. */
private class BlockCallbacks(
    val onPill: (StatusItem) -> Unit,
    val onOpenAppDetails: () -> Unit,
    val onNotificationsToggle: (Boolean) -> Unit,
    val onEditTime: () -> Unit,
    val onTestNotification: () -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onAccent: (AccentId) -> Unit,
    val onTopicVisible: (String, Boolean) -> Unit,
    val onTopicOrder: (List<String>) -> Unit,
    val onResetTopics: () -> Unit,
    val onEditPlace: (PlaceTarget) -> Unit,
    val onDayWindow: (Int, Int) -> Unit,
    val onReaderScale: (Float) -> Unit,
    val ai: AiActions,
    val onEditToken: () -> Unit,
    val onRemoveToken: () -> Unit,
    val onRecheckSource: () -> Unit,
    val update: UpdateCardActions,
    val onOpenLink: (String) -> Unit,
)

/** One entry of the settings list. */
@Composable
private fun SettingsBlock(
    key: String,
    settings: AppSettings,
    modifier: Modifier,
    content: BlockContent,
    callbacks: BlockCallbacks,
    listState: LazyListState,
    topObstruction: Float,
) {
    when (key) {
        Block.HERO -> {
            val items = statusItems(content.status, content.source, AiSupport.isSupported, settings.hasAiKey)
            val next = AlarmScheduler.nextTrigger(content.now, settings.notifyHour, settings.notifyMinute)
            val hero = heroText(
                enabled = settings.notificationsEnabled,
                items = items,
                day = dayWord(content.now, next),
                time = clockLabel(settings.notifyHour, settings.notifyMinute),
            )
            StatusHero(
                hero = hero,
                enabled = settings.notificationsEnabled,
                items = items,
                reduceMotion = content.reduceMotion,
                onPill = callbacks.onPill,
                onOpenAppDetails = callbacks.onOpenAppDetails,
                modifier = modifier.padding(top = 16.dp),
            )
        }
        Block.H_BRIEFING -> SectionHeading("Dein Wecker", "Morgenbriefing", modifier)
        Block.BRIEFING -> BriefingCard(
            settings = settings,
            now = content.now,
            reduceMotion = content.reduceMotion,
            onToggle = callbacks.onNotificationsToggle,
            onEditTime = callbacks.onEditTime,
            onTest = callbacks.onTestNotification,
            modifier = modifier,
        )
        Block.H_LOOK -> SectionHeading("Dein Look", "Darstellung", modifier)
        Block.LOOK -> AppearanceCard(
            themeMode = settings.themeMode,
            accent = settings.accent,
            onThemeMode = callbacks.onThemeMode,
            onAccent = callbacks.onAccent,
            modifier = modifier,
        )
        Block.H_TOPICS -> SectionHeading("Dein Feed", "Themen", modifier)
        Block.TOPICS -> TopicGrid(
            storedOrder = remember(settings.topicOrder) { TopicList.normalize(settings.topicOrder) },
            hidden = settings.hiddenTopics,
            reduceMotion = content.reduceMotion,
            listState = listState,
            listItemKey = Block.TOPICS,
            topObstruction = topObstruction,
            onToggle = callbacks.onTopicVisible,
            onOrder = callbacks.onTopicOrder,
            onReset = callbacks.onResetTopics,
            modifier = modifier,
        )
        Block.H_PLACES -> SectionHeading("Dein Wetter", "Orte & Zeitfenster", modifier)
        Block.PLACES -> {
            val (home, day) = remember(content.weather, settings.home, settings.day, content.now) {
                placeWeather(content.weather, settings.home, settings.day, content.now)
            }
            PlacesSection(
                settings = settings,
                homeWeather = home,
                dayWeather = day,
                onEditPlace = callbacks.onEditPlace,
                onDayWindow = callbacks.onDayWindow,
                modifier = modifier,
            )
        }
        Block.H_READING -> SectionHeading("Artikel", "Lesen", modifier)
        Block.READING -> ReadingCard(
            scale = settings.readerTextScale,
            onCommit = callbacks.onReaderScale,
            reduceMotion = content.reduceMotion,
            modifier = modifier,
        )
        Block.H_AI -> SectionHeading("Claude", "KI", modifier)
        Block.AI -> AiCard(
            settings = settings,
            supported = AiSupport.isSupported,
            usage = content.usage,
            reduceMotion = content.reduceMotion,
            actions = callbacks.ai,
            modifier = modifier,
        )
        Block.H_SOURCE -> SectionHeading("Deine News", "News-Quelle", modifier)
        Block.SOURCE -> NewsSourceCard(
            hasToken = settings.hasToken,
            status = content.source,
            now = content.now,
            onEditToken = callbacks.onEditToken,
            onRemoveToken = callbacks.onRemoveToken,
            onRecheck = callbacks.onRecheckSource,
            modifier = modifier,
        )
        Block.H_APP -> SectionHeading("Über", "App", modifier)
        Block.APP -> AppHeaderCard(installedVersion = content.update.installedVersion, modifier = modifier)
        Block.UPDATE -> UpdateCard(
            state = content.update,
            hasToken = settings.hasToken,
            reduceMotion = content.reduceMotion,
            actions = callbacks.update,
            modifier = modifier.padding(top = 14.dp),
        )
        Block.LINKS -> LinksCard(onOpen = callbacks.onOpenLink, modifier = modifier.padding(top = 14.dp))
        Block.FOOTER -> SettingsFooter(modifier)
    }
}

private val TOP_BAR_HEIGHT = 52.dp
private val TITLE_SCROLL_THRESHOLD = 72.dp

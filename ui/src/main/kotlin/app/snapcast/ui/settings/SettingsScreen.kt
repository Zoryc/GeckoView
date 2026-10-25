package app.snapcast.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.SearchEngine
import app.snapcast.core.model.ThemeMode
import app.snapcast.core.util.Formatters
import app.snapcast.ui.R
import app.snapcast.ui.shared.ConfirmDialog
import app.snapcast.ui.shared.ScreenScaffold
import app.snapcast.ui.shared.SectionHeader
import app.snapcast.ui.shared.SnackbarMessages

/** Which settings dialog is open (saved across configuration changes). */
private enum class SettingsDialog {
    SEARCH_ENGINE,
    HOME_PAGE,
    THEME,
    MIN_SIZE,
    MIN_DURATION,
    IGNORED_DOMAINS,
    PROXY_PORT,
    CLEAR_COOKIES,
    CLEAR_CACHE,
    CLEAR_SITE_DATA,
    CLEAR_HISTORY,
    LICENSES,
}

/** App settings: general, video detection, casting, privacy and about. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clearing by viewModel.clearInProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    SnackbarMessages(viewModel.messages, snackbarHostState)

    ScreenScaffold(
        title = stringResource(R.string.ui_screens_settings_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            GeneralSection(settings, viewModel, onOpenDialog = { dialog = it })
            HorizontalDivider()
            DetectionSection(settings, onOpenDialog = { dialog = it })
            HorizontalDivider()
            CastingSection(settings, viewModel, onOpenDialog = { dialog = it })
            HorizontalDivider()
            PrivacySection(clearing, onOpenDialog = { dialog = it })
            HorizontalDivider()
            AboutSection(viewModel.about, onOpenDialog = { dialog = it })
            Spacer(Modifier.height(24.dp))
        }
    }

    SettingsDialogHost(
        dialog = dialog,
        settings = settings,
        viewModel = viewModel,
        onDismiss = { dialog = null },
    )
}

@Composable
private fun GeneralSection(settings: AppSettings, viewModel: SettingsViewModel, onOpenDialog: (SettingsDialog) -> Unit) {
    SectionHeader(stringResource(R.string.ui_screens_settings_section_general))
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_search_engine),
        summary = settings.searchEngine.displayName,
        icon = Icons.Default.Search,
        onClick = { onOpenDialog(SettingsDialog.SEARCH_ENGINE) },
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_home_page),
        summary = if (settings.homePage == AppSettings.HOME_PAGE_BUILT_IN) {
            stringResource(R.string.ui_screens_settings_home_builtin)
        } else {
            settings.homePage
        },
        icon = Icons.Default.Home,
        onClick = { onOpenDialog(SettingsDialog.HOME_PAGE) },
    )
    SwitchSetting(
        title = stringResource(R.string.ui_screens_settings_desktop_mode),
        summary = stringResource(R.string.ui_screens_settings_desktop_mode_summary),
        icon = Icons.Default.DesktopWindows,
        checked = settings.desktopModeDefault,
        onCheckedChange = viewModel::setDesktopModeDefault,
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_theme),
        summary = themeLabel(settings.themeMode),
        icon = Icons.Default.DarkMode,
        onClick = { onOpenDialog(SettingsDialog.THEME) },
    )
}

@Composable
private fun DetectionSection(settings: AppSettings, onOpenDialog: (SettingsDialog) -> Unit) {
    SectionHeader(stringResource(R.string.ui_screens_settings_section_detection))
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_min_size),
        summary = stringResource(
            R.string.ui_screens_settings_min_size_summary,
            sizeLabel(DetectionOptions.sizeOptionFor(settings.minVideoSizeBytes)),
        ),
        icon = Icons.Default.Storage,
        onClick = { onOpenDialog(SettingsDialog.MIN_SIZE) },
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_min_duration),
        summary = stringResource(
            R.string.ui_screens_settings_min_duration_summary,
            durationLabel(DetectionOptions.durationOptionFor(settings.minVideoDurationSec)),
        ),
        icon = Icons.Default.Timer,
        onClick = { onOpenDialog(SettingsDialog.MIN_DURATION) },
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_ignored_domains),
        summary = stringResource(R.string.ui_screens_settings_ignored_domains_summary, settings.ignoredDomains.size),
        icon = Icons.Default.Block,
        onClick = { onOpenDialog(SettingsDialog.IGNORED_DOMAINS) },
    )
}

@Composable
private fun CastingSection(settings: AppSettings, viewModel: SettingsViewModel, onOpenDialog: (SettingsDialog) -> Unit) {
    SectionHeader(stringResource(R.string.ui_screens_settings_section_casting))
    SwitchSetting(
        title = stringResource(R.string.ui_screens_settings_proxy),
        summary = stringResource(R.string.ui_screens_settings_proxy_summary),
        icon = Icons.Default.Router,
        checked = settings.proxyEnabled,
        onCheckedChange = viewModel::setProxyEnabled,
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_proxy_port),
        summary = settings.proxyPort.toString(),
        icon = Icons.Default.SettingsEthernet,
        enabled = settings.proxyEnabled,
        onClick = { onOpenDialog(SettingsDialog.PROXY_PORT) },
    )
    SwitchSetting(
        title = stringResource(R.string.ui_screens_settings_resume),
        summary = stringResource(R.string.ui_screens_settings_resume_summary),
        icon = Icons.Default.Replay,
        checked = settings.resumePlayback,
        onCheckedChange = viewModel::setResumePlayback,
    )
}

@Composable
private fun PrivacySection(clearing: Set<ClearAction>, onOpenDialog: (SettingsDialog) -> Unit) {
    SectionHeader(stringResource(R.string.ui_screens_settings_section_privacy))
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_clear_cookies),
        summary = stringResource(R.string.ui_screens_settings_clear_cookies_summary),
        icon = Icons.Default.Cookie,
        enabled = ClearAction.COOKIES !in clearing,
        onClick = { onOpenDialog(SettingsDialog.CLEAR_COOKIES) },
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_clear_cache),
        summary = stringResource(R.string.ui_screens_settings_clear_cache_summary),
        icon = Icons.Default.Cached,
        enabled = ClearAction.CACHE !in clearing,
        onClick = { onOpenDialog(SettingsDialog.CLEAR_CACHE) },
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_clear_site_data),
        summary = stringResource(R.string.ui_screens_settings_clear_site_data_summary),
        icon = Icons.Default.Web,
        enabled = ClearAction.SITE_DATA !in clearing,
        onClick = { onOpenDialog(SettingsDialog.CLEAR_SITE_DATA) },
    )
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_clear_history),
        summary = stringResource(R.string.ui_screens_settings_clear_history_summary),
        icon = Icons.Default.History,
        enabled = ClearAction.HISTORY !in clearing,
        onClick = { onOpenDialog(SettingsDialog.CLEAR_HISTORY) },
    )
}

@Composable
private fun AboutSection(about: AboutInfo, onOpenDialog: (SettingsDialog) -> Unit) {
    SectionHeader(stringResource(R.string.ui_screens_settings_section_about))
    InfoSetting(
        title = stringResource(R.string.ui_screens_settings_app_version),
        summary = about.appVersion ?: stringResource(R.string.ui_screens_settings_version_unknown),
        icon = Icons.Default.Info,
    )
    about.engineVersion?.let { version ->
        InfoSetting(
            title = stringResource(R.string.ui_screens_settings_engine_version),
            summary = stringResource(R.string.ui_screens_settings_engine_version_value, version),
            icon = Icons.Default.Language,
        )
    }
    ClickableSetting(
        title = stringResource(R.string.ui_screens_settings_licenses),
        summary = stringResource(R.string.ui_screens_settings_licenses_summary),
        icon = Icons.Default.Code,
        onClick = { onOpenDialog(SettingsDialog.LICENSES) },
    )
}

@Composable
private fun SettingsDialogHost(
    dialog: SettingsDialog?,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        SettingsDialog.SEARCH_ENGINE -> ChoiceDialog(
            title = stringResource(R.string.ui_screens_settings_search_engine),
            options = SearchEngine.entries,
            selected = settings.searchEngine,
            label = { it.displayName },
            onSelect = viewModel::setSearchEngine,
            onDismiss = onDismiss,
        )
        SettingsDialog.HOME_PAGE -> HomePageDialog(
            currentHomePage = settings.homePage,
            onSave = { url ->
                if (url == null) {
                    viewModel.useBuiltInHomePage()
                    true
                } else {
                    viewModel.setCustomHomePage(url)
                }
            },
            onDismiss = onDismiss,
        )
        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.ui_screens_settings_theme),
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { themeLabel(it) },
            onSelect = viewModel::setThemeMode,
            onDismiss = onDismiss,
        )
        SettingsDialog.MIN_SIZE -> ChoiceDialog(
            title = stringResource(R.string.ui_screens_settings_min_size),
            options = DetectionOptions.MIN_SIZE_BYTES,
            selected = DetectionOptions.sizeOptionFor(settings.minVideoSizeBytes),
            label = { sizeLabel(it) },
            onSelect = viewModel::setMinVideoSize,
            onDismiss = onDismiss,
        )
        SettingsDialog.MIN_DURATION -> ChoiceDialog(
            title = stringResource(R.string.ui_screens_settings_min_duration),
            options = DetectionOptions.MIN_DURATION_SEC,
            selected = DetectionOptions.durationOptionFor(settings.minVideoDurationSec),
            label = { durationLabel(it) },
            onSelect = viewModel::setMinVideoDuration,
            onDismiss = onDismiss,
        )
        SettingsDialog.IGNORED_DOMAINS -> IgnoredDomainsDialog(
            domains = settings.ignoredDomains,
            onAdd = viewModel::addIgnoredDomain,
            onRemove = viewModel::removeIgnoredDomain,
            onRestoreDefaults = viewModel::restoreDefaultIgnoredDomains,
            onDismiss = onDismiss,
        )
        SettingsDialog.PROXY_PORT -> ProxyPortDialog(
            currentPort = settings.proxyPort,
            onSave = viewModel::setProxyPort,
            onDismiss = onDismiss,
        )
        SettingsDialog.CLEAR_COOKIES -> ClearConfirmDialog(
            ClearAction.COOKIES,
            R.string.ui_screens_settings_clear_cookies,
            R.string.ui_screens_settings_clear_cookies_confirm,
            viewModel,
            onDismiss,
        )
        SettingsDialog.CLEAR_CACHE -> ClearConfirmDialog(
            ClearAction.CACHE,
            R.string.ui_screens_settings_clear_cache,
            R.string.ui_screens_settings_clear_cache_confirm,
            viewModel,
            onDismiss,
        )
        SettingsDialog.CLEAR_SITE_DATA -> ClearConfirmDialog(
            ClearAction.SITE_DATA,
            R.string.ui_screens_settings_clear_site_data,
            R.string.ui_screens_settings_clear_site_data_confirm,
            viewModel,
            onDismiss,
        )
        SettingsDialog.CLEAR_HISTORY -> ClearConfirmDialog(
            ClearAction.HISTORY,
            R.string.ui_screens_settings_clear_history,
            R.string.ui_screens_settings_clear_history_confirm,
            viewModel,
            onDismiss,
        )
        SettingsDialog.LICENSES -> LicensesDialog(onDismiss = onDismiss)
    }
}

@Composable
private fun ClearConfirmDialog(
    action: ClearAction,
    titleRes: Int,
    bodyRes: Int,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(titleRes),
        text = stringResource(bodyRes),
        confirmLabel = stringResource(R.string.ui_screens_clear),
        onConfirm = { viewModel.clear(action) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.DARK -> R.string.ui_screens_settings_theme_dark
        ThemeMode.LIGHT -> R.string.ui_screens_settings_theme_light
        ThemeMode.SYSTEM -> R.string.ui_screens_settings_theme_system
    },
)

@Composable
private fun sizeLabel(bytes: Long): String =
    if (bytes <= 0) stringResource(R.string.ui_screens_settings_off) else Formatters.fileSize(bytes)

@Composable
private fun durationLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.ui_screens_settings_off)
    } else {
        stringResource(R.string.ui_screens_settings_seconds, seconds)
    }

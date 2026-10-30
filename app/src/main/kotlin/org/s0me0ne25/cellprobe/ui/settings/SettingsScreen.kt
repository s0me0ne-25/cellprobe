// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringSettings
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.engine.MonitoringService
import org.s0me0ne25.cellprobe.system.InstalledApps
import org.s0me0ne25.cellprobe.ui.appContainer
import org.s0me0ne25.cellprobe.ui.apps.AppIcon
import org.s0me0ne25.cellprobe.ui.apps.AppPickerDialog
import org.s0me0ne25.cellprobe.ui.apps.titleRes
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.descriptionRes
import org.s0me0ne25.cellprobe.ui.listSummary
import org.s0me0ne25.cellprobe.ui.main.RoleApp
import org.s0me0ne25.cellprobe.ui.titleRes
import kotlin.time.Duration

data class SettingsUiState(
    val resourceLists: Map<ResourceListId, ResourceListState>,
    val vlessCount: Int,
    val roles: Map<AppRole, RoleApp>,
    val monitoring: MonitoringSettings,
    val detailedJournal: Boolean,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    installedApps: InstalledApps,
    /** Starts or stops the monitoring service. */
    private val syncMonitoring: (Boolean) -> Unit,
) : ViewModel() {

    /** Null until the settings are loaded. */
    val state: StateFlow<SettingsUiState?> = combine(
        repository.resourceLists,
        repository.vlessEntries,
        repository.appRoles,
        repository.monitoring,
        repository.detailedJournal,
    ) { lists, vless, roles, monitoring, detailedJournal ->
        SettingsUiState(
            resourceLists = lists.associateBy { it.id },
            vlessCount = vless.size,
            roles = roles.mapValues { (_, packageName) -> RoleApp(packageName, installedApps.label(packageName)) },
            monitoring = monitoring,
            detailedJournal = detailedJournal,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Saves the switch first: the service stops itself while it reads "off". */
    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateMonitoring { it.copy(enabled = enabled) }
            syncMonitoring(enabled)
        }
    }

    fun setDetailedJournal(detailed: Boolean) {
        viewModelScope.launch { repository.setDetailedJournal(detailed) }
    }

    fun setInterval(screenOn: Boolean, interval: Duration) {
        viewModelScope.launch {
            repository.updateMonitoring { monitoring ->
                if (screenOn) {
                    monitoring.copy(screenOnIntervalSeconds = interval.inWholeSeconds)
                } else {
                    monitoring.copy(screenOffIntervalSeconds = interval.inWholeSeconds)
                }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                val application = checkNotNull(this[APPLICATION_KEY])
                SettingsViewModel(container.settingsRepository, container.installedApps) { enabled ->
                    MonitoringService.sync(application, enabled)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenList: (ResourceListId) -> Unit,
    onOpenVless: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerRole by rememberSaveable { mutableStateOf<AppRole?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
        ) {
            val loaded = state ?: return@LazyColumn
            item(key = "monitoring") {
                MonitoringSection(
                    settings = loaded.monitoring,
                    onEnabledChange = viewModel::setMonitoringEnabled,
                    onIntervalChange = viewModel::setInterval,
                )
            }
            item(key = "journal") {
                JournalSection(detailed = loaded.detailedJournal, onDetailedChange = viewModel::setDetailedJournal)
            }
            item {
                Text(
                    text = stringResource(R.string.settings_section_lists),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            val current = loaded

            // Lists in the order of the spec: 1, 2, VLESS (3), 4, provider pages.
            fun resourceList(id: ResourceListId) {
                val list = current.resourceLists.getValue(id)
                item(key = id) {
                    SettingsListItem(
                        title = stringResource(id.titleRes()),
                        description = stringResource(id.descriptionRes()),
                        summary = listSummary(list),
                        onClick = { onOpenList(id) },
                    )
                }
            }

            resourceList(ResourceListId.DOMESTIC)
            resourceList(ResourceListId.FOREIGN)
            item(key = "vless") {
                SettingsListItem(
                    title = stringResource(R.string.vless_title),
                    description = stringResource(R.string.vless_description_short),
                    summary = if (current.vlessCount == 0) {
                        stringResource(R.string.list_summary_empty)
                    } else {
                        pluralStringResource(R.plurals.list_entries_count, current.vlessCount, current.vlessCount)
                    },
                    onClick = onOpenVless,
                )
            }
            resourceList(ResourceListId.BANNED)
            resourceList(ResourceListId.PROVIDER)

            item(key = "apps") {
                Text(
                    text = stringResource(R.string.settings_section_apps),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }
            for (role in AppRole.entries) {
                item(key = role) {
                    RoleItem(role, current.roles[role], onClick = { pickerRole = role })
                }
            }
        }
    }

    pickerRole?.let { role ->
        AppPickerDialog(role = role, current = state?.roles?.get(role)?.packageName, onDismiss = { pickerRole = null })
    }
}

/**
 * An unblocking app role:
 * ```
 * [icon] VPN client — Hiddify
 * [icon] Opened under standard blocking.
 * What the role is for, across the full width.
 * ```
 * The icon is as tall as the two lines next to it; a question mark in a
 * circle of the same size stands in when no app is chosen or it is gone.
 */
@Composable
private fun RoleItem(role: AppRole, app: RoleApp?, onClick: () -> Unit) {
    val label = app?.label
    val typography = MaterialTheme.typography
    val iconSize = with(LocalDensity.current) { typography.titleMedium.lineHeight.toDp() + typography.bodyMedium.lineHeight.toDp() }
    val value = when {
        app == null -> stringResource(R.string.role_not_set)
        label == null -> stringResource(R.string.role_app_missing_short)
        else -> label
    }
    val valueColor = if (app != null && label == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val title = stringResource(role.titleRes())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app != null && label != null) {
                AppIcon(app.packageName, iconSize)
            } else {
                QuestionMark(iconSize)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = buildAnnotatedString {
                        append(title)
                        append(" — ")
                        withStyle(SpanStyle(color = valueColor)) { append(value) }
                    },
                    style = typography.titleMedium,
                )
                Text(
                    text = stringResource(role.descriptionRes()),
                    style = typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(role.aboutRes()),
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

/** Placeholder of an app icon: a question mark in a circle of [size]. */
@Composable
private fun QuestionMark(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .border(width = 2.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AppRole.descriptionRes(): Int = when (this) {
    AppRole.VPN_CLIENT -> R.string.role_vpn_client_description
    AppRole.OLCRTC -> R.string.role_olcrtc_description
    AppRole.I2P -> R.string.role_i2p_description
}

private fun AppRole.aboutRes(): Int = when (this) {
    AppRole.VPN_CLIENT -> R.string.role_vpn_client_about
    AppRole.OLCRTC -> R.string.role_olcrtc_about
    AppRole.I2P -> R.string.role_i2p_about
}

@Composable
private fun SettingsListItem(
    title: String,
    description: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description)
                Text(text = summary, color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

/** Display mode of the journal (spec §9.2, §9.4). */
@Composable
private fun JournalSection(detailed: Boolean, onDetailedChange: (Boolean) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_section_journal),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.journal_detailed)) },
            supportingContent = { Text(stringResource(R.string.journal_detailed_description)) },
            trailingContent = { Switch(checked = detailed, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = detailed, role = Role.Switch, onValueChange = onDetailedChange),
        )
        HorizontalDivider()
    }
}

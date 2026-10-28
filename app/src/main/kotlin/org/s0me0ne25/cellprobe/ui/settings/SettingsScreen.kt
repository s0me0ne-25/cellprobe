// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.ui.appContainer
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.descriptionRes
import org.s0me0ne25.cellprobe.ui.listSummary
import org.s0me0ne25.cellprobe.ui.titleRes

data class SettingsUiState(
    val resourceLists: Map<ResourceListId, ResourceListState>,
    val vlessCount: Int,
)

class SettingsViewModel(repository: SettingsRepository) : ViewModel() {

    /** Null until the settings are loaded. */
    val state: StateFlow<SettingsUiState?> = combine(
        repository.resourceLists,
        repository.vlessEntries,
    ) { lists, vless ->
        SettingsUiState(lists.associateBy { it.id }, vless.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel(appContainer().settingsRepository) }
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
            item {
                Text(
                    text = stringResource(R.string.settings_section_lists),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            val current = state ?: return@LazyColumn

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
        }
    }
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

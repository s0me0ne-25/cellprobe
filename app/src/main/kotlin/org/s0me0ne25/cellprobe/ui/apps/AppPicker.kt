// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.apps

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.system.InstalledApps
import org.s0me0ne25.cellprobe.system.LaunchableApp
import org.s0me0ne25.cellprobe.ui.appContainer
import org.s0me0ne25.cellprobe.ui.localAppContainer

class AppPickerViewModel(
    private val installedApps: InstalledApps,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** Launchable apps; null while they are being listed. Read once per screen visit. */
    val apps: StateFlow<List<LaunchableApp>?> = flow { emit(installedApps.launchable()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun assign(role: AppRole, packageName: String?) {
        viewModelScope.launch { settings.setAppRole(role, packageName) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                AppPickerViewModel(container.installedApps, container.settingsRepository)
            }
        }
    }
}

/**
 * Chooses the app for [role] from the launchable apps, with search.
 * [current] is the package assigned now; it can be cleared.
 */
@Composable
fun AppPickerDialog(
    role: AppRole,
    current: String?,
    onDismiss: () -> Unit,
    viewModel: AppPickerViewModel = viewModel(factory = AppPickerViewModel.Factory),
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val query = rememberTextFieldState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(role.titleRes())) },
        text = {
            Column {
                OutlinedTextField(
                    state = query,
                    label = { Text(stringResource(R.string.app_picker_search)) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth(),
                )
                val all = apps
                if (all == null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val text = query.text.toString().trim()
                    val shown = remember(all, text) {
                        if (text.isEmpty()) all else all.filter { it.label.contains(text, ignoreCase = true) }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp).padding(top = 8.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            ListItem(
                                headlineContent = { Text(app.label) },
                                leadingContent = { AppIcon(app.packageName, 40.dp) },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (app.packageName == current) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                ),
                                modifier = Modifier.clickable {
                                    viewModel.assign(role, app.packageName)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = {
            if (current != null) {
                TextButton(
                    onClick = {
                        viewModel.assign(role, null)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.app_picker_clear)) }
            }
        },
    )
}

/** Launcher icon of [packageName], loaded off the main thread; empty space until then. */
@Composable
fun AppIcon(packageName: String, size: Dp) {
    val installedApps = localAppContainer().installedApps
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) { installedApps.icon(packageName, sizePx) }
    }
    val image = bitmap
    if (image != null) {
        Image(bitmap = image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(modifier = Modifier.size(size))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.ProviderPage
import org.s0me0ne25.cellprobe.engine.ProviderPages
import org.s0me0ne25.cellprobe.ui.appContainer
import org.s0me0ne25.cellprobe.ui.apps.openUrl
import org.s0me0ne25.cellprobe.ui.theme.CellprobeTheme

/**
 * The small Cellprobe window over the current screen with the provider
 * account pages (spec §7.4): the full list, pages that responded in the
 * last check marked. A tap opens the page in the browser.
 */
class ProviderPagesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CellprobeTheme {
                ProviderPagesDialog(
                    onOpen = { page ->
                        openUrl(page.url)
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ProviderPagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

class ProviderPagesViewModel(settings: SettingsRepository, controller: CheckController) : ViewModel() {

    /** Null while the list is being read. */
    val pages: StateFlow<List<ProviderPage>?> = combine(
        settings.resourceList(ResourceListId.PROVIDER).map { list -> list.entries.map { it.url } },
        controller.state.map { it.sections },
    ) { urls, sections -> ProviderPages.of(urls, sections) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                ProviderPagesViewModel(container.settingsRepository, container.checkController)
            }
        }
    }
}

@Composable
private fun ProviderPagesDialog(
    onOpen: (ProviderPage) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ProviderPagesViewModel = viewModel(factory = ProviderPagesViewModel.Factory),
) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val loaded = pages ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_provider_title)) },
        text = {
            if (loaded.isEmpty()) {
                Text(stringResource(R.string.main_provider_pages_empty))
            } else {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    for (page in loaded) {
                        ListItem(
                            headlineContent = { Text(if (page.responded) "✅ ${page.label}" else page.label) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onOpen(page) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

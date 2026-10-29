// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.ui.diagnostics.DiagnosticsScreen
import org.s0me0ne25.cellprobe.ui.journal.JournalScreen
import org.s0me0ne25.cellprobe.ui.lists.ResourceListEditorScreen
import org.s0me0ne25.cellprobe.ui.main.MainScreen
import org.s0me0ne25.cellprobe.ui.settings.SettingsScreen
import org.s0me0ne25.cellprobe.ui.vless.VlessEditorScreen

/** Screens of the single activity. */
sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data class ResourceListEditor(val listId: ResourceListId) : Screen
    data object VlessEditor : Screen
    data object Diagnostics : Screen
    data object Journal : Screen
}

private fun Screen.toRoute(): String = when (this) {
    Screen.Main -> "main"
    Screen.Settings -> "settings"
    is Screen.ResourceListEditor -> "list/${listId.name}"
    Screen.VlessEditor -> "vless"
    Screen.Diagnostics -> "check"
    Screen.Journal -> "journal"
}

private fun screenFromRoute(route: String): Screen = when {
    route == "settings" -> Screen.Settings
    route == "vless" -> Screen.VlessEditor
    route == "check" -> Screen.Diagnostics
    route == "journal" -> Screen.Journal
    route.startsWith("list/") ->
        ResourceListId.entries.firstOrNull { it.name == route.removePrefix("list/") }
            ?.let { Screen.ResourceListEditor(it) }
            ?: Screen.Main
    else -> Screen.Main
}

private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.toRoute() } },
    restore = { routes -> routes.map(::screenFromRoute).toMutableStateList() },
)

/** Minimal back stack navigation: a list of screens, the last one is shown. */
@Composable
fun CellprobeRoot() {
    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Screen>(Screen.Main) }
    // removeAt instead of removeLast: the latter is a Java 21 API missing on older Android.
    val navigateBack: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    BackHandler(enabled = backStack.size > 1, onBack = navigateBack)

    when (val screen = backStack.last()) {
        Screen.Main -> MainScreen(
            onOpenDetails = { backStack.add(Screen.Diagnostics) },
            onOpenJournal = { backStack.add(Screen.Journal) },
            onOpenSettings = { backStack.add(Screen.Settings) },
        )
        Screen.Settings -> SettingsScreen(
            onBack = navigateBack,
            onOpenList = { backStack.add(Screen.ResourceListEditor(it)) },
            onOpenVless = { backStack.add(Screen.VlessEditor) },
        )
        is Screen.ResourceListEditor -> ResourceListEditorScreen(
            listId = screen.listId,
            onBack = navigateBack,
        )
        Screen.VlessEditor -> VlessEditorScreen(onBack = navigateBack)
        Screen.Diagnostics -> DiagnosticsScreen(onBack = navigateBack)
        Screen.Journal -> JournalScreen(onBack = navigateBack)
    }
}

package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.flint.term.BuildConfig

/**
 * The index of settings: one row per section, each its own screen.
 *
 * The alternative — every control on one page — meant scrolling past a dozen
 * groups to reach the one you came for, and the list only ever grows. The
 * summary under each title is what makes the right row findable without
 * opening three of them first; the search field is for when it is not.
 */
@Composable
fun SettingsScreen(nav: NavController) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // A tab, not a page: the bottom bar is how you got here.
        topBar = { AppHeader(title = stringResource(R.string.settingsscreen_settings), onBack = null) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScreenScroll("settings")).padding(bottom = 40.dp)) {
            val sections = listOf(
                Section("Appearance", stringResource(R.string.settingsscreen_app_theme_font_ligatures_terminal_colors), Icons.Rounded.Palette, MaterialTheme.colorScheme.tertiary, Routes.SETTINGS_APPEARANCE),
                Section(stringResource(R.string.settingsscreen_keyboard_input), stringResource(R.string.settingsscreen_the_extra_key_bar_the_volume_keys_and_the_gestur), Icons.Rounded.Keyboard, MaterialTheme.colorScheme.primary, Routes.SETTINGS_KEYBOARD),
                Section("Terminal", stringResource(R.string.settingsscreen_scrollback_predictive_echo_completion_recordings), Icons.Rounded.Terminal, MaterialTheme.colorScheme.secondary, Routes.SETTINGS_TERMINAL),
                Section(stringResource(R.string.settingsscreen_sessions_alerts), stringResource(R.string.settingsscreen_tabs_the_bell_and_being_told_a_command_finished), Icons.Rounded.Tab, MaterialTheme.colorScheme.tertiary, Routes.SETTINGS_SESSIONS),
                Section("Connections", stringResource(R.string.settingsscreen_keepalive_data_saver_agent_signing_vpn_resolver), Icons.Rounded.Link, MaterialTheme.colorScheme.primary, Routes.SETTINGS_CONNECTIONS),
                Section("Files", stringResource(R.string.settingsscreen_hidden_files_and_where_a_dropped_file_lands), Icons.Rounded.Folder, MaterialTheme.colorScheme.tertiary, Routes.SETTINGS_FILES),
                Section("Backup", stringResource(R.string.settingsscreen_everything_in_one_file_sealed_with_a_passphrase), Icons.Rounded.Archive, MaterialTheme.colorScheme.primary, Routes.SETTINGS_BACKUP),
                Section("Security", stringResource(R.string.settingsscreen_app_lock_before_hosts_and_keys_open), Icons.Rounded.Lock, MaterialTheme.colorScheme.error, Routes.SETTINGS_SECURITY),
                Section("Automation", stringResource(R.string.settingsscreen_whether_other_apps_may_drive_sessions), Icons.Rounded.Bolt, MaterialTheme.colorScheme.error, Routes.SETTINGS_AUTOMATION),
                Section("About", stringResource(R.string.settingsscreen_flintterm_and_what_it_is_built_from, BuildConfig.VERSION_NAME), Icons.Rounded.Info, MaterialTheme.colorScheme.onSurfaceVariant, Routes.SETTINGS_ABOUT),
            )

            SearchField(query, { query = it }, stringResource(R.string.settingsscreen_search_settings), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

            val hits = remember(query) {
                if (query.isBlank()) emptyList() else PaletteSearch.rank(PaletteCatalog.settings(), query, limit = 30)
            }
            if (query.isBlank()) {
                Group(modifier = Modifier.padding(top = 6.dp)) {
                    sections.forEachIndexed { i, section ->
                        if (i > 0) RowDivider()
                        GroupRow(
                            title = section.title,
                            subtitle = section.summary,
                            icon = section.icon,
                            iconTint = section.tint,
                            onClick = { nav.navigate(section.route) },
                        )
                    }
                }
            } else if (hits.isEmpty()) {
                Text(
                    stringResource(R.string.settingsscreen_nothing_called_that),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
            } else {
                // Where a match lives is as useful as the match: the row on the
                // right says which section opens, since the search cannot scroll
                // to the row itself.
                Group(modifier = Modifier.padding(top = 6.dp)) {
                    hits.forEachIndexed { i, hit ->
                        if (i > 0) RowDivider()
                        val home = sections.firstOrNull { hit.entry.route.startsWith(it.route) }
                            ?: sections.first { it.route == sectionFor(hit.entry.route) }
                        GroupRow(
                            title = hit.entry.title,
                            subtitle = hit.entry.subtitle,
                            icon = home.icon,
                            iconTint = home.tint,
                            onClick = { nav.navigate(hit.entry.route) },
                            trailing = {
                                Text(home.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The section a settings route that is not itself a section belongs under —
 * the screens that hang off one, like the extra-key bar off Keyboard.
 */
private fun sectionFor(route: String): String = when (route) {
    Routes.EXTRA_KEYS, Routes.CHORDS -> Routes.SETTINGS_KEYBOARD
    Routes.THEME, Routes.HIGHLIGHTS -> Routes.SETTINGS_APPEARANCE
    Routes.RECORDINGS -> Routes.SETTINGS_TERMINAL
    Routes.KNOWN_HOSTS -> Routes.SETTINGS_SECURITY
    else -> Routes.SETTINGS_ABOUT
}

private data class Section(
    val title: String,
    /** One line saying what is inside, so the right row is picked first time. */
    val summary: String,
    val icon: ImageVector,
    val tint: Color,
    val route: String,
)

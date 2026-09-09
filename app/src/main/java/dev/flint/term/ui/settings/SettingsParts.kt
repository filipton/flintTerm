package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.flint.term.ui.AppHeader
import dev.flint.term.ui.rememberScreenScroll

/**
 * The frame every settings section shares: a title, a way back, and a scroll.
 *
 * Settings is an index now, so each section is a screen like any other rather
 * than a stretch of one very long page — which is what made a row findable
 * only by scrolling past every row that came before it.
 */
@Composable
fun SettingsSection(
    nav: NavController,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = title, subtitle = subtitle, onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScreenScroll("settings/$title"))
                .padding(bottom = 40.dp),
            content = content,
        )
    }
}

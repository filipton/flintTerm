package dev.flint.term.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
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
    // Taken once, on the way in: the row that matches scrolls itself into view
    // and lights up, and going back to this screen later leaves it alone.
    val focus = remember { dev.flint.term.ui.SettingsFocus.take() }
    DisposableEffect(Unit) { onDispose { dev.flint.term.ui.SettingsFocus.clear() } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(title = title, subtitle = subtitle, onBack = { nav.popBackStack() }) },
    ) { padding ->
        val scroll = rememberScreenScroll("settings/$title")
        val column = remember(scroll) { dev.flint.term.ui.SettingsScroll(scroll) }
        CompositionLocalProvider(
            dev.flint.term.ui.LocalSettingsFocus provides focus,
            dev.flint.term.ui.LocalSettingsScroll provides column,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .onGloballyPositioned { column.topInWindow = it.positionInWindow().y }
                    .verticalScroll(scroll)
                    .padding(bottom = 40.dp),
                content = content,
            )
        }
    }
}

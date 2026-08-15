package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.AppContainer
import com.campuslink.mobile.core.settings.AppLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, text: UiStrings, onBack: () -> Unit, onClear: () -> Unit) {
    val language by container.settings.language.collectAsStateWithLifecycle()
    val dark by container.settings.dark.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text.settings) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ListItem(
                headlineContent = { Text(text.darkMode) },
                trailingContent = { Switch(checked = dark, onCheckedChange = container.settings::setDark) },
            )
            ListItem(
                headlineContent = { Text(text.language) },
                trailingContent = {
                    Row {
                        TextButton(onClick = { container.settings.setLanguage(AppLanguage.ENGLISH) }) {
                            Text(if (language == AppLanguage.ENGLISH) "✓ English" else "English")
                        }
                        TextButton(onClick = { container.settings.setLanguage(AppLanguage.CHINESE) }) {
                            Text(if (language == AppLanguage.CHINESE) "✓ 中文" else "中文")
                        }
                    }
                },
            )
            Button(onClick = onClear, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Text(text.clearHistory)
            }
            Button(onClick = container.sessionStore::clear, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(text.logout)
            }
        }
    }
}

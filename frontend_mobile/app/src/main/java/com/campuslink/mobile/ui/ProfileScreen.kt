package com.campuslink.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campuslink.mobile.core.settings.AppLanguage

internal data class ProfileUiState(
    val email: String,
    val role: String,
    val versionName: String,
    val language: AppLanguage,
    val dark: Boolean,
)

internal data class ProfileActions(
    val changeLanguage: (AppLanguage) -> Unit,
    val changeDark: (Boolean) -> Unit,
    val clearHistory: () -> Unit,
    val logout: () -> Unit,
)

internal const val PROFILE_LIST_TAG = "profile-list"

@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    actions: ProfileActions,
) {
    LazyColumn(
        modifier = Modifier.testTag(PROFILE_LIST_TAG),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Profile", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Account and app preferences",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { ProfileHeaderCard(email = state.email, role = state.role) }
        item { ProfileSectionTitle("Account") }
        item {
            ProfileCard {
                ListItem(
                    headlineContent = { Text("Email") },
                    supportingContent = { Text(state.email) },
                )
                ListItem(
                    headlineContent = { Text("Role") },
                    supportingContent = { Text(state.role) },
                )
            }
        }
        item { ProfileSectionTitle("Preferences") }
        item {
            ProfileCard {
                ListItem(
                    headlineContent = { Text("Dark mode") },
                    trailingContent = { Switch(checked = state.dark, onCheckedChange = actions.changeDark) },
                )
                ListItem(
                    headlineContent = { Text("Language") },
                    supportingContent = {
                        Row {
                            TextButton(onClick = { actions.changeLanguage(AppLanguage.ENGLISH) }) {
                                Text(if (state.language == AppLanguage.ENGLISH) "✓ English" else "English")
                            }
                            TextButton(onClick = { actions.changeLanguage(AppLanguage.CHINESE) }) {
                                Text(if (state.language == AppLanguage.CHINESE) "✓ 中文" else "中文")
                            }
                        }
                    },
                )
            }
        }
        item { ProfileSectionTitle("Data") }
        item {
            OutlinedButton(onClick = actions.clearHistory, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Text("Clear Chat History", modifier = Modifier.padding(start = 8.dp))
            }
        }
        item { ProfileSectionTitle("App") }
        item {
            ProfileCard {
                ListItem(
                    headlineContent = { Text("About CampusLink") },
                    supportingContent = { Text("Campus services and AI assistance in one app") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                )
                ListItem(
                    headlineContent = { Text("App Version") },
                    supportingContent = { Text(state.versionName) },
                )
            }
        }
        item {
            Button(onClick = actions.logout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Text("Log Out", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(email: String, role: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = email.firstOrNull()?.uppercase() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text(email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(role, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ProfileCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth(), content = { content() })
    }
}

@Composable
private fun ProfileSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

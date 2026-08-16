package com.campuslink.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
    text: ProfileStrings,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    if (confirmClear) {
        ConfirmationDialog(
            copy = ConfirmationCopy(
                title = text.clearHistoryTitle,
                message = text.clearHistoryMessage,
                confirmLabel = text.clear,
                dismissLabel = text.cancel,
            ),
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                actions.clearHistory()
            },
        )
    }
    if (confirmLogout) {
        ConfirmationDialog(
            copy = ConfirmationCopy(
                title = text.logOutTitle,
                message = text.logOutMessage,
                confirmLabel = text.confirmLogOut,
                dismissLabel = text.cancel,
            ),
            onDismiss = { confirmLogout = false },
            onConfirm = {
                confirmLogout = false
                actions.logout()
            },
        )
    }

    ProfileContent(
        state = state,
        actions = actions,
        text = text,
        requestClear = { confirmClear = true },
        requestLogout = { confirmLogout = true },
    )
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    actions: ProfileActions,
    text: ProfileStrings,
    requestClear: () -> Unit,
    requestLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(PROFILE_LIST_TAG),
        contentPadding = PaddingValues(
            start = CampusSpacing.ExtraLarge,
            top = CampusSpacing.Huge,
            end = CampusSpacing.ExtraLarge,
            bottom = CampusSpacing.Huge,
        ),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall)) {
                Text(text.title, style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = text.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { ProfileHeaderCard(email = state.email, role = state.role, roleLabel = text.role) }
        item { CampusSectionHeader(text.preferences, Modifier.padding(top = CampusSpacing.Small)) }
        item { ProfilePreferences(state, actions, text) }
        item { CampusSectionHeader(text.dataPrivacy, Modifier.padding(top = CampusSpacing.Small)) }
        item { ProfileDataPrivacy(text, requestClear) }
        item { CampusSectionHeader(text.about, Modifier.padding(top = CampusSpacing.Small)) }
        item { ProfileAbout(state.versionName, text) }
        item { ProfileLogout(text.logOut, requestLogout) }
    }
}

@Composable
private fun ProfilePreferences(state: ProfileUiState, actions: ProfileActions, text: ProfileStrings) {
    ProfileCard {
        SettingsRow(
            icon = Icons.Default.DarkMode,
            title = text.darkMode,
            onClick = { actions.changeDark(!state.dark) },
        ) {
            Switch(checked = state.dark, onCheckedChange = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(
            icon = Icons.Default.Language,
            title = text.language,
            onClick = {
                val language = if (state.language == AppLanguage.ENGLISH) {
                    AppLanguage.CHINESE
                } else {
                    AppLanguage.ENGLISH
                }
                actions.changeLanguage(language)
            },
        ) {
            Text(
                text = if (state.language == AppLanguage.ENGLISH) text.english else text.chinese,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileDataPrivacy(text: ProfileStrings, onClear: () -> Unit) {
    ProfileCard {
        SettingsRow(
            icon = Icons.Default.DeleteOutline,
            title = text.clearHistory,
            onClick = onClear,
            iconColors = SettingsIconColors(
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileAbout(versionName: String, text: ProfileStrings) {
    ProfileCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            CampusIconContainer(
                icon = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(text.aboutCampusLink, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = text.aboutSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text.version, style = MaterialTheme.typography.titleMedium)
            Text(
                text = versionName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileLogout(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = CampusSpacing.Small),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = CampusSpacing.Small))
    }
}

@Composable
private fun ProfileHeaderCard(email: String, role: String, roleLabel: String) {
    Card(
        shape = RoundedCornerShape(CampusCorners.ExtraLarge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.ExtraLarge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialsFromEmail(email),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
            ) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(CampusCorners.ExtraLarge),
                ) {
                    Text(
                        text = "$roleLabel · $role",
                        modifier = Modifier.padding(
                            horizontal = CampusSpacing.Medium,
                            vertical = CampusSpacing.ExtraSmall,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(CampusCorners.Large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.fillMaxWidth(), content = { content() })
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)? = null,
    iconColors: SettingsIconColors? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = iconColors ?: SettingsIconColors(
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.primary,
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth().then(clickModifier).padding(CampusSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
    ) {
        CampusIconContainer(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            containerColor = colors.container,
            contentColor = colors.content,
        )
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing?.invoke(this)
    }
}

private data class SettingsIconColors(val container: Color, val content: Color)

private data class ConfirmationCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
)

@Composable
private fun ConfirmationDialog(
    copy: ConfirmationCopy,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = { Text(copy.message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(copy.confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(copy.dismissLabel) }
        },
    )
}

internal fun initialsFromEmail(email: String): String = email
    .substringBefore('@')
    .filter(Char::isLetterOrDigit)
    .take(2)
    .uppercase()
    .ifBlank { "?" }

@Preview(name = "Profile - small phone", widthDp = 320, heightDp = 640)
@Composable
@Suppress("UnusedPrivateMember")
private fun ProfileSmallPreview() {
    val text = strings(AppLanguage.ENGLISH)
    CampusLinkTheme(darkTheme = false) {
        ProfileScreen(
            state = ProfileUiState(
                email = "student@nus.edu.sg",
                role = "STUDENT",
                versionName = "0.1.0-local",
                language = AppLanguage.ENGLISH,
                dark = false,
            ),
            actions = ProfileActions({}, {}, {}, {}),
            text = text.profile,
        )
    }
}

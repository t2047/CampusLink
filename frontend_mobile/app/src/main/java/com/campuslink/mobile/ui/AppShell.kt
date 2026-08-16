package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

internal enum class AppTab {
    HOME,
    AGENT_CORE,
    PROFILE,
}

@Composable
internal fun CampusLinkShell(
    selectedTab: AppTab,
    text: ShellStrings,
    onTabSelected: (AppTab) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            CampusLinkBottomNavigation(
                selectedTab = selectedTab,
                text = text,
                onTabSelected = onTabSelected,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            content()
        }
    }
}

@Composable
internal fun CampusLinkBottomNavigation(
    selectedTab: AppTab,
    text: ShellStrings,
    onTabSelected: (AppTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = CampusSpacing.ExtraSmall,
        windowInsets = NavigationBarDefaults.windowInsets,
    ) {
        AppTab.entries.forEach { tab ->
            val label = tab.label(text)
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            AppTab.HOME -> Icons.Default.Home
                            AppTab.AGENT_CORE -> Icons.Default.SmartToy
                            AppTab.PROFILE -> Icons.Default.Person
                        },
                        contentDescription = label,
                    )
                },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun AppTab.label(text: ShellStrings): String = when (this) {
    AppTab.HOME -> text.home
    AppTab.AGENT_CORE -> text.agentCore
    AppTab.PROFILE -> text.profile
}

internal fun AppTab.screen(): Screen = when (this) {
    AppTab.HOME -> Screen.Home
    AppTab.AGENT_CORE -> Screen.Conversations
    AppTab.PROFILE -> Screen.Profile
}

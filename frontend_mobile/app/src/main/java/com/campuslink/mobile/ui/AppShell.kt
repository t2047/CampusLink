package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal enum class AppTab(val label: String) {
    HOME("Home"),
    AGENT_CORE("Agent Core"),
    PROFILE("Profile"),
}

@Composable
internal fun CampusLinkShell(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            CampusLinkBottomNavigation(
                selectedTab = selectedTab,
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
internal fun CampusLinkBottomNavigation(selectedTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    NavigationBar {
        AppTab.entries.forEach { tab ->
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
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

internal fun AppTab.screen(): Screen = when (this) {
    AppTab.HOME -> Screen.Home
    AppTab.AGENT_CORE -> Screen.Conversations
    AppTab.PROFILE -> Screen.Profile
}

package com.campuslink.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(onBack: () -> Unit, onFacilities: () -> Unit, onLostFound: () -> Unit, onMail: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Services") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text("Facilities") },
                supportingContent = { Text("Find campus spaces and check availability") },
                leadingContent = { Icon(Icons.Default.MeetingRoom, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onFacilities),
            )
            ListItem(
                headlineContent = { Text("Lost & Found") },
                supportingContent = { Text("Browse, report, and claim campus items") },
                leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLostFound),
            )
            ListItem(
                headlineContent = { Text("Mail") },
                supportingContent = { Text("Read and manage your Gmail account") },
                leadingContent = { Icon(Icons.Default.Email, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onMail),
            )
        }
    }
}

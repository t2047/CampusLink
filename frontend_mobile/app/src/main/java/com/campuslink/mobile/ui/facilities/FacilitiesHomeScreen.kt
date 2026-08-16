package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacilitiesHomeScreen(
    onBack: () -> Unit,
    onSearchSpaces: () -> Unit,
    onMyBookings: () -> Unit,
    onReportMaintenance: () -> Unit,
    onMyMaintenance: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Facilities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to services")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp)) {
            Text(
                "Find campus spaces that fit your needs.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Search Spaces") },
                supportingContent = { Text("Browse rooms and check their availability") },
                leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onSearchSpaces),
            )
            ListItem(
                headlineContent = { Text("My Bookings") },
                supportingContent = { Text("View and manage your space bookings") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onMyBookings),
            )
            ListItem(
                headlineContent = { Text("Report Maintenance") },
                supportingContent = { Text("Report an issue with a campus space") },
                leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onReportMaintenance),
            )
            ListItem(
                headlineContent = { Text("My Maintenance Requests") },
                supportingContent = { Text("Track the status of submitted requests") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onMyMaintenance),
            )
        }
    }
}

package com.campuslink.mobile.ui.lostfound

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.campuslink.mobile.core.model.ReportType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LostFoundHomeScreen(
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onCreate: (ReportType) -> Unit,
    onClaims: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lost & Found") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Home")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Browse reports") },
                supportingContent = { Text("Search open lost and found records") },
                leadingContent = { Icon(Icons.Default.FindInPage, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onBrowse),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Report a lost item") },
                supportingContent = { Text("Publish details and up to five images") },
                leadingContent = { Icon(Icons.Default.AddCircle, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable { onCreate(ReportType.LOST) },
            )
            ListItem(
                headlineContent = { Text("Report a found item") },
                supportingContent = { Text("Help an owner find an item you picked up") },
                leadingContent = { Icon(Icons.Default.Inventory, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable { onCreate(ReportType.FOUND) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Claims") },
                supportingContent = { Text("Track your claims and review received claims") },
                leadingContent = { Icon(Icons.Default.AssignmentTurnedIn, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClaims),
            )
        }
    }
}

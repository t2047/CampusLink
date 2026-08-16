package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.MaintenanceResponse
import com.campuslink.mobile.core.model.MaintenanceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyMaintenanceScreen(
    viewModel: MyMaintenanceViewModel,
    onBack: () -> Unit,
    onOpenRequest: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onScreenVisible() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Maintenance Requests") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to facilities")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            MyMaintenanceUiState.Loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            MyMaintenanceUiState.Empty -> MessageState(
                "No maintenance requests",
                "Requests you submit will appear here.",
                Modifier.padding(padding),
            )
            is MyMaintenanceUiState.Error -> MessageState(
                "Unable to load requests",
                current.message,
                Modifier.padding(padding),
                retry = viewModel::refresh,
            )
            is MyMaintenanceUiState.Success -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(current.requests, key = MaintenanceResponse::ticketId) { request ->
                    MaintenanceCard(request, onOpenRequest)
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCard(request: MaintenanceResponse, onOpen: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(request.ticketId) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ticket #${request.ticketId}", fontWeight = FontWeight.Bold)
                MaintenanceStatusLabel(request.status)
            }
            Text(request.spaceName ?: "${request.building} / ${request.roomNumber}")
            Text(request.facilityType, style = MaterialTheme.typography.titleMedium)
            Text(request.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Priority: ${request.priority.displayName()}")
            Text(
                "Updated ${formatMaintenanceDateTime(request.updatedAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MaintenanceStatusLabel(status: MaintenanceStatus) {
    Text(
        status.displayName(),
        color = when (status) {
            MaintenanceStatus.RESOLVED -> MaterialTheme.colorScheme.primary
            MaintenanceStatus.CANCELLED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.tertiary
        },
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MessageState(title: String, message: String, modifier: Modifier, retry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(message, modifier = Modifier.padding(vertical = 8.dp))
        retry?.let { OutlinedButton(onClick = it) { Text("Retry") } }
    }
}

internal fun MaintenanceStatus.displayName(): String = name.lowercase().split('_')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

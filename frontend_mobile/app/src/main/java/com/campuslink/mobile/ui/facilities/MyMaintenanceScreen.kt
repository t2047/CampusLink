package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.MaintenanceResponse
import com.campuslink.mobile.core.model.MaintenanceStatus
import com.campuslink.mobile.ui.CampusEmptyState
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun MyMaintenanceScreen(
    viewModel: MyMaintenanceViewModel,
    onBack: () -> Unit,
    onOpenRequest: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onScreenVisible() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("My Maintenance", onBack, "Back to Facilities") },
    ) { padding ->
        when (val current = state) {
            MyMaintenanceUiState.Loading -> CampusLoadingState("Loading requests…", Modifier.padding(padding))
            MyMaintenanceUiState.Empty -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusEmptyState(
                    title = "No maintenance requests",
                    message = "Requests you submit will appear here.",
                    icon = Icons.AutoMirrored.Filled.Assignment,
                )
            }
            is MyMaintenanceUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusErrorState(
                    title = "Unable to load requests",
                    message = current.message,
                    retryLabel = "Retry",
                    onRetry = viewModel::refresh,
                )
            }
            is MyMaintenanceUiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = CampusSpacing.ExtraLarge,
                    top = CampusSpacing.Small,
                    end = CampusSpacing.ExtraLarge,
                    bottom = CampusSpacing.Huge,
                ),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
            ) {
                item {
                    CampusPageHeader(
                        title = "Maintenance requests",
                        subtitle = "Track issue status and Facilities updates.",
                    )
                }
                items(current.requests, key = MaintenanceResponse::ticketId) { request ->
                    MaintenanceCard(request) { onOpenRequest(request.ticketId) }
                }
            }
        }
    }
}

@Composable
internal fun MaintenanceCard(request: MaintenanceResponse, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(request.facilityType, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        request.spaceName ?: "${request.building} / ${request.roomNumber}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MaintenanceStatusLabel(request.status)
            }
            Text(request.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Priority · ${request.priority.displayName()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Updated ${formatMaintenanceDateTime(request.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Ticket #${request.ticketId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MaintenanceStatusLabel(status: MaintenanceStatus) {
    CampusStatusChip(status.displayName(), maintenanceStatusTone(status))
}

internal fun MaintenanceStatus.displayName(): String = name.lowercase().split('_')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

internal fun maintenanceStatusTone(status: MaintenanceStatus): CampusStatusTone = when (status) {
    MaintenanceStatus.SUBMITTED -> CampusStatusTone.INFO
    MaintenanceStatus.IN_PROGRESS -> CampusStatusTone.WARNING
    MaintenanceStatus.RESOLVED -> CampusStatusTone.SUCCESS
    MaintenanceStatus.CANCELLED -> CampusStatusTone.ERROR
}

package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.MaintenanceResponse
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusInfoRow
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusSectionHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun MaintenanceDetailsScreen(viewModel: MaintenanceDetailsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Maintenance Details", onBack, "Back to My Maintenance") },
    ) { padding ->
        when (val current = state) {
            MaintenanceDetailsUiState.Loading -> CampusLoadingState("Loading request…", Modifier.padding(padding))
            is MaintenanceDetailsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusErrorState(
                    title = if (current.notFound) {
                        "Maintenance request not found"
                    } else {
                        "Unable to load request"
                    },
                    message = current.message,
                    retryLabel = "Retry",
                    onRetry = viewModel::retry,
                )
            }
            is MaintenanceDetailsUiState.Success -> MaintenanceDetails(
                current.maintenance,
                Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun MaintenanceDetails(request: MaintenanceResponse, modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(CampusSpacing.ExtraLarge),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
    ) {
        CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.ExtraLarge),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
            ) {
                CampusStatusChip(request.status.displayName(), maintenanceStatusTone(request.status))
                Text(request.facilityType, style = MaterialTheme.typography.headlineSmall)
                Text(
                    request.spaceName ?: "${request.building} · ${request.roomNumber}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Ticket #${request.ticketId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CampusSectionHeader("Issue")
        CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
            ) {
                CampusInfoRow("Description", request.description)
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Large)) {
                    CampusInfoRow("Priority", request.priority.displayName(), Modifier.weight(1f))
                    CampusInfoRow("Room", request.roomNumber, Modifier.weight(1f))
                }
                CampusInfoRow("Building", request.building)
            }
        }
        CampusSectionHeader("Timeline")
        CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
            ) {
                CampusInfoRow("Created", formatMaintenanceDateTime(request.createdAt))
                CampusInfoRow("Updated", formatMaintenanceDateTime(request.updatedAt))
            }
        }
        Text(
            "Status updates are managed by Facilities staff.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

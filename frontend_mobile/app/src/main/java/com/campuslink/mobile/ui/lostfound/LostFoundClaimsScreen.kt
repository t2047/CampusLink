package com.campuslink.mobile.ui.lostfound

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
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.ClaimStatus
import com.campuslink.mobile.core.model.LostFoundClaim
import com.campuslink.mobile.ui.CampusEmptyState
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun LostFoundClaimsScreen(
    viewModel: LostFoundClaimsViewModel,
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Lost & Found Claims", onBack, "Back to Lost & Found") },
    ) { padding ->
        LazyColumn(
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
                    title = "Claims",
                    subtitle = "Track ownership requests and claims on your found items.",
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                    FilterChip(
                        selected = mode == ClaimsMode.MINE,
                        onClick = { viewModel.changeMode(ClaimsMode.MINE) },
                        label = { Text("My claims") },
                    )
                    FilterChip(
                        selected = mode == ClaimsMode.RECEIVED,
                        onClick = { viewModel.changeMode(ClaimsMode.RECEIVED) },
                        label = { Text("Received claims") },
                    )
                }
            }
            when (val current = state) {
                ClaimsUiState.Loading -> item { CampusLoadingState("Loading claims…") }
                ClaimsUiState.Empty -> item {
                    CampusEmptyState(
                        title = if (mode == ClaimsMode.MINE) "No claims submitted" else "No claims received",
                        message = if (mode == ClaimsMode.MINE) {
                            "Claims you submit for found items will appear here."
                        } else {
                            "Claims on your found-item reports will appear here."
                        },
                        icon = Icons.Default.AssignmentTurnedIn,
                    )
                }
                is ClaimsUiState.Error -> item {
                    CampusErrorState(
                        title = "Unable to load claims",
                        message = current.message,
                        retryLabel = "Retry",
                        onRetry = viewModel::retry,
                    )
                }
                is ClaimsUiState.Success -> items(current.claims, key = LostFoundClaim::id) { claim ->
                    ClaimCard(
                        claim = claim,
                        received = mode == ClaimsMode.RECEIVED,
                        onOpenReport = { onOpenReport(claim.report.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ClaimCard(
    claim: LostFoundClaim,
    received: Boolean,
    onOpenReport: () -> Unit,
) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
            ) {
                Text(
                    claim.report.itemName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                CampusStatusChip(claim.status.name, claimStatusTone(claim.status))
            }
            Text(
                "${claim.report.category.displayName()} · ${claim.report.location}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (received) "Received claim" else "Submitted by you",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Ownership proof", style = MaterialTheme.typography.labelMedium)
            Text(claim.proofDescription)
            claim.decisionNote?.takeIf(String::isNotBlank)?.let {
                Text("Decision note · $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Submitted ${claim.createdAt.replace('T', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenReport) { Text("View report") }
            if (received && claim.status == ClaimStatus.SUBMITTED) {
                CampusStatusChip("ADMIN REVIEW", CampusStatusTone.INFO)
                Text(
                    "Only administrators can approve or reject claims.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun claimStatusTone(status: ClaimStatus): CampusStatusTone = when (status) {
    ClaimStatus.SUBMITTED -> CampusStatusTone.INFO
    ClaimStatus.APPROVED -> CampusStatusTone.SUCCESS
    ClaimStatus.REJECTED -> CampusStatusTone.ERROR
}

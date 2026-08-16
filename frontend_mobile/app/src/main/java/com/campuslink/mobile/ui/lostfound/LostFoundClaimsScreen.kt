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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val decisionState by viewModel.decisionState.collectAsStateWithLifecycle()
    var decision by remember { mutableStateOf<PendingDecision?>(null) }
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
                    subtitle = "Track ownership requests and review claims on your found items.",
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
                        submitting = (decisionState as? ClaimDecisionUiState.Submitting)?.claimId == claim.id,
                        onOpenReport = { onOpenReport(claim.report.id) },
                        onDecision = { approve -> decision = PendingDecision(claim.id, approve) },
                    )
                }
            }
            when (val feedback = decisionState) {
                ClaimDecisionUiState.Idle, is ClaimDecisionUiState.Submitting -> Unit
                is ClaimDecisionUiState.Success -> item {
                    DecisionFeedback(feedback.message, viewModel::clearDecisionFeedback)
                }
                is ClaimDecisionUiState.Error -> item {
                    DecisionFeedback(feedback.message, viewModel::clearDecisionFeedback, error = true)
                }
            }
        }
    }
    decision?.let { pending ->
        ClaimDecisionDialog(
            approve = pending.approve,
            onDismiss = { decision = null },
            onConfirm = { note ->
                viewModel.decide(pending.claimId, pending.approve, note)
                decision = null
            },
        )
    }
}

@Composable
internal fun ClaimCard(
    claim: LostFoundClaim,
    received: Boolean,
    submitting: Boolean,
    onOpenReport: () -> Unit,
    onDecision: (Boolean) -> Unit,
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
                if (submitting) {
                    CircularProgressIndicator()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                        Button(onClick = { onDecision(true) }) { Text("Approve") }
                        OutlinedButton(
                            onClick = { onDecision(false) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Reject") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimDecisionDialog(approve: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (approve) "Approve this claim?" else "Reject this claim?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                Text(
                    if (approve) {
                        "The report will become CLAIMED and other pending claims will be rejected."
                    } else {
                        "The claimant will see your decision note."
                    },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Decision note (optional)") },
                    supportingText = { Text("${note.length}/500") },
                    isError = note.length > 500,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(note) },
                enabled = note.length <= 500,
                colors = if (approve) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                },
            ) { Text(if (approve) "Approve" else "Reject") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DecisionFeedback(message: String, onDismiss: () -> Unit, error: Boolean = false) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            CampusStatusChip(
                if (error) "NOT COMPLETED" else "UPDATED",
                if (error) CampusStatusTone.ERROR else CampusStatusTone.SUCCESS,
            )
            Text(message)
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun claimStatusTone(status: ClaimStatus): CampusStatusTone = when (status) {
    ClaimStatus.SUBMITTED -> CampusStatusTone.INFO
    ClaimStatus.APPROVED -> CampusStatusTone.SUCCESS
    ClaimStatus.REJECTED -> CampusStatusTone.ERROR
}

private data class PendingDecision(val claimId: Long, val approve: Boolean)

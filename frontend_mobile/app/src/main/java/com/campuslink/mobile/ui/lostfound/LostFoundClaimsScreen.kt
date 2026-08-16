package com.campuslink.mobile.ui.lostfound

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.ClaimStatus
import com.campuslink.mobile.core.model.LostFoundClaim

@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                title = { Text("Lost & Found claims") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Lost & Found")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ClaimsMode.MINE,
                        onClick = { viewModel.changeMode(ClaimsMode.MINE) },
                        label = { Text("My claims") },
                    )
                    FilterChip(
                        selected = mode == ClaimsMode.RECEIVED,
                        onClick = { viewModel.changeMode(ClaimsMode.RECEIVED) },
                        label = { Text("Received") },
                    )
                }
            }
            when (val current = state) {
                ClaimsUiState.Loading -> item { LoadingRow() }
                ClaimsUiState.Empty -> item {
                    Text(
                        if (mode == ClaimsMode.MINE) "You have not submitted any claims."
                        else "No one has submitted a claim for your found items.",
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                is ClaimsUiState.Error -> item { ErrorBlock(current.message, viewModel::retry) }
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
            item { Text("") }
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
private fun ClaimCard(
    claim: LostFoundClaim,
    received: Boolean,
    submitting: Boolean,
    onOpenReport: () -> Unit,
    onDecision: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(claim.report.itemName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(claim.status.name, color = claim.status.statusColour())
            }
            Text("${claim.report.category.displayName()} · ${claim.report.location}")
            Text("Proof: ${claim.proofDescription}")
            claim.decisionNote?.takeIf(String::isNotBlank)?.let { Text("Decision note: $it") }
            OutlinedButton(onClick = onOpenReport) { Text("View report") }
            if (received && claim.status == ClaimStatus.SUBMITTED) {
                if (submitting) {
                    CircularProgressIndicator()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDecision(true) }) { Text("Approve") }
                        OutlinedButton(onClick = { onDecision(false) }) { Text("Reject") }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (approve) "The report will become CLAIMED and other pending claims will be rejected."
                    else "The claimant will see your decision note.",
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Decision note (optional)") },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(note) }) { Text(if (approve) "Approve" else "Reject") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DecisionFeedback(message: String, dismiss: () -> Unit, error: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = dismiss) { Text("Dismiss") }
    }
}

private data class PendingDecision(val claimId: Long, val approve: Boolean)

@Composable
private fun ClaimStatus.statusColour() = when (this) {
    ClaimStatus.APPROVED -> MaterialTheme.colorScheme.primary
    ClaimStatus.REJECTED -> MaterialTheme.colorScheme.error
    ClaimStatus.SUBMITTED -> MaterialTheme.colorScheme.tertiary
}

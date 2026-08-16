package com.campuslink.mobile.ui.lostfound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.core.model.ReportType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LostFoundDetailsScreen(viewModel: LostFoundDetailsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val claimState by viewModel.claimState.collectAsStateWithLifecycle()
    var claimDialogVisible by remember { mutableStateOf(false) }
    var proof by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to reports")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            LostFoundDetailsUiState.Loading -> Column(Modifier.padding(padding)) { LoadingRow() }
            is LostFoundDetailsUiState.Error -> Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorBlock(current.message, viewModel::retry)
            }
            is LostFoundDetailsUiState.Success -> ReportDetails(
                report = current.report,
                claimState = claimState,
                onClaim = { claimDialogVisible = true },
                onClearFeedback = viewModel::clearClaimFeedback,
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (claimDialogVisible) {
        AlertDialog(
            onDismissRequest = { claimDialogVisible = false },
            title = { Text("Submit ownership proof") },
            text = {
                OutlinedTextField(
                    value = proof,
                    onValueChange = { proof = it },
                    label = { Text("Describe a detail only the owner would know") },
                    minLines = 4,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitClaim(proof)
                        claimDialogVisible = false
                    },
                ) { Text("Submit") }
            },
            dismissButton = { TextButton(onClick = { claimDialogVisible = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReportDetails(
    report: LostFoundReport,
    claimState: ClaimSubmissionUiState,
    onClaim: () -> Unit,
    onClearFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (report.images.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(report.images, key = { it.id }) { image ->
                        AsyncImage(
                            model = resolveLostFoundImageUrl(image.url),
                            contentDescription = report.itemName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillParentMaxWidth(0.86f).height(240.dp),
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(report.itemName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(report.status.name, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("${report.reportType.name} · ${report.category.displayName()}")
                    DetailLine("Description", report.description)
                    report.colour?.let { DetailLine("Colour", it) }
                    DetailLine("Location", report.location)
                    DetailLine("Date", report.eventDate)
                    report.timeDescription?.let { DetailLine("Time", it) }
                    if (report.createdByMe) Text("Published by you", fontWeight = FontWeight.Medium)
                }
            }
        }
        item {
            when (claimState) {
                ClaimSubmissionUiState.Idle -> if (
                    report.reportType == ReportType.FOUND &&
                    report.status == ReportStatus.OPEN &&
                    !report.createdByMe
                ) {
                    Button(onClick = onClaim, modifier = Modifier.fillMaxWidth()) { Text("Submit claim") }
                }
                ClaimSubmissionUiState.Submitting -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                is ClaimSubmissionUiState.Success -> FeedbackCard(
                    "Claim submitted. The person who found this item can now review your proof.",
                    dismiss = null,
                )
                is ClaimSubmissionUiState.Error -> FeedbackCard(
                    claimState.message,
                    dismiss = onClearFeedback,
                    error = true,
                )
            }
        }
        item { Text("") }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun FeedbackCard(message: String, dismiss: (() -> Unit)?, error: Boolean = false) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            dismiss?.let { OutlinedButton(onClick = it) { Text("Dismiss") } }
        }
    }
}

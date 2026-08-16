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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusInfoRow
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusSectionHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar

internal const val REPORT_DETAILS_LIST_TAG = "report-details-list"

@Composable
fun LostFoundDetailsScreen(viewModel: LostFoundDetailsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val claimState by viewModel.claimState.collectAsStateWithLifecycle()
    var claimDialogVisible by remember { mutableStateOf(false) }
    var proof by remember { mutableStateOf("") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Report Details", onBack, "Back to reports") },
    ) { padding ->
        when (val current = state) {
            LostFoundDetailsUiState.Loading -> CampusLoadingState("Loading report…", Modifier.padding(padding))
            is LostFoundDetailsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusErrorState(
                    title = if (current.notFound) "Report not found" else "Unable to load report",
                    message = current.message,
                    retryLabel = "Retry",
                    onRetry = viewModel::retry,
                )
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
                    supportingText = { Text("10–1000 characters") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitClaim(proof)
                        claimDialogVisible = false
                    },
                    enabled = proof.trim().length in 10..1000,
                ) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = { claimDialogVisible = false }) { Text("Cancel") }
            },
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
        modifier = modifier.fillMaxSize().testTag(REPORT_DETAILS_LIST_TAG),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = CampusSpacing.ExtraLarge,
            top = CampusSpacing.Small,
            end = CampusSpacing.ExtraLarge,
            bottom = CampusSpacing.Huge,
        ),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
    ) {
        if (report.images.isNotEmpty()) {
            item { ReportImageGallery(report) }
        }
        item { ReportHeaderCard(report) }
        item { ReportInformationCard(report) }
        item { ReportDescriptionCard(report.description) }
        item { ClaimAction(report, claimState, onClaim, onClearFeedback) }
    }
}

@Composable
private fun ReportImageGallery(report: LostFoundReport) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
        items(report.images, key = { it.id }) { image ->
            AsyncImage(
                model = resolveLostFoundImageUrl(image.url),
                contentDescription = "Photo of ${report.itemName}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillParentMaxWidth(0.9f)
                    .height(240.dp)
                    .clip(RoundedCornerShape(CampusSpacing.Large)),
            )
        }
    }
}

@Composable
private fun ReportHeaderCard(report: LostFoundReport) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.ExtraLarge),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
            ) {
                CampusStatusChip(report.reportType.name, CampusStatusTone.INFO)
                CampusStatusChip(report.status.name, reportStatusTone(report.status))
            }
            Text(report.itemName, style = MaterialTheme.typography.headlineSmall)
            Text(
                report.category.displayName(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.createdByMe) Text("Published by you", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ReportInformationCard(report: LostFoundReport) {
    CampusSectionHeader("Report information")
    CampusSurfaceCard(Modifier.fillMaxWidth().padding(top = CampusSpacing.Medium)) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
        ) {
            CampusInfoRow("Location", report.location)
            CampusInfoRow("Date", report.eventDate)
            report.timeDescription?.let { CampusInfoRow("Time", it) }
            report.colour?.let { CampusInfoRow("Colour", it) }
        }
    }
}

@Composable
private fun ReportDescriptionCard(description: String) {
    CampusSectionHeader("Description")
    CampusSurfaceCard(Modifier.fillMaxWidth().padding(top = CampusSpacing.Medium)) {
        Text(
            description,
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ClaimAction(
    report: LostFoundReport,
    state: ClaimSubmissionUiState,
    onClaim: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    when (state) {
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
            "Claim submitted",
            "The person who found this item can now review your proof.",
            CampusStatusTone.SUCCESS,
        )
        is ClaimSubmissionUiState.Error -> FeedbackCard(
            "Claim not submitted",
            state.message,
            CampusStatusTone.ERROR,
            onClearFeedback,
        )
    }
}

@Composable
private fun FeedbackCard(
    title: String,
    message: String,
    tone: CampusStatusTone,
    dismiss: (() -> Unit)? = null,
) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            CampusStatusChip(title.uppercase(), tone)
            Text(message)
            dismiss?.let { OutlinedButton(onClick = it) { Text("Dismiss") } }
        }
    }
}

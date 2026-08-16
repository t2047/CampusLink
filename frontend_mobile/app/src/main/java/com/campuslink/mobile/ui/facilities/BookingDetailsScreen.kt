package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusInfoRow
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusSectionHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun BookingDetailsScreen(viewModel: BookingDetailsViewModel, onBack: () -> Unit) {
    val details by viewModel.detailsState.collectAsStateWithLifecycle()
    val cancel by viewModel.cancelState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Booking Details", onBack, "Back to My Bookings") },
    ) { padding ->
        when (val current = details) {
            BookingDetailsUiState.Loading -> CampusLoadingState("Loading booking…", Modifier.padding(padding))
            is BookingDetailsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusErrorState(
                    title = if (current.notFound) "Booking not found" else "Unable to load booking",
                    message = current.message,
                    retryLabel = "Retry",
                    onRetry = viewModel::retry,
                )
            }
            is BookingDetailsUiState.Success -> BookingDetailsContent(
                booking = current.booking,
                canCancel = viewModel.canCancel(current.booking),
                cancelState = cancel,
                actions = BookingDetailsActions(viewModel::requestCancel, viewModel::clearCancelError),
                modifier = Modifier.padding(padding),
            )
        }
    }
    when (val current = cancel) {
        is CancelBookingUiState.Confirming -> CancelConfirmationDialog(
            booking = current.booking,
            cancelling = false,
            onDismiss = viewModel::dismissCancelConfirmation,
            onConfirm = viewModel::confirmCancel,
        )
        CancelBookingUiState.Cancelling -> {
            val booking = (details as? BookingDetailsUiState.Success)?.booking
            if (booking != null) CancelConfirmationDialog(booking, true, {}, {})
        }
        else -> Unit
    }
}

@Composable
private fun BookingDetailsContent(
    booking: BookingResponse,
    canCancel: Boolean,
    cancelState: CancelBookingUiState,
    actions: BookingDetailsActions,
    modifier: Modifier,
) {
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
                CampusStatusChip(booking.status.name, bookingStatusTone(booking.status))
                Text(booking.space.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${booking.space.building} · ${booking.space.roomNumber}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Booking #${booking.bookingId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CampusSectionHeader("Booking information")
        CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Large)) {
                    CampusInfoRow("Date", formatBookingDate(booking.startDateTime), Modifier.weight(1f))
                    CampusInfoRow("Time", formatBookingRange(booking), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Large)) {
                    CampusInfoRow("Capacity", booking.space.capacity.toString(), Modifier.weight(1f))
                    CampusInfoRow("Room", booking.space.roomNumber, Modifier.weight(1f))
                }
                CampusInfoRow(
                    "Equipment",
                    booking.space.equipment.sorted().joinToString().ifEmpty { "None listed" },
                )
            }
        }
        CampusSectionHeader("Timeline")
        CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
            ) {
                CampusInfoRow("Created", booking.createdAt.replace('T', ' '))
                CampusInfoRow("Updated", booking.updatedAt.replace('T', ' '))
            }
        }
        if (canCancel) {
            OutlinedButton(
                onClick = actions.onCancel,
                enabled = cancelState !is CancelBookingUiState.Cancelling,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Cancel Booking") }
        }
        if (cancelState is CancelBookingUiState.Error) {
            CampusSurfaceCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
                ) {
                    CampusStatusChip("NOT CANCELLED", CampusStatusTone.ERROR)
                    Text("Cancellation not completed", fontWeight = FontWeight.Bold)
                    Text(cancelState.message)
                    TextButton(onClick = actions.onDismissError) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun CancelConfirmationDialog(
    booking: BookingResponse,
    cancelling: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!cancelling) onDismiss() },
        title = { Text("Cancel this booking?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                Text(booking.space.name)
                Text(formatBookingDate(booking.startDateTime))
                Text(formatBookingRange(booking))
                Text(
                    "This action cannot be undone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !cancelling) {
                Text(
                    if (cancelling) "Cancelling…" else "Cancel Booking",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !cancelling) { Text("Keep Booking") }
        },
    )
}

private data class BookingDetailsActions(
    val onCancel: () -> Unit,
    val onDismissError: () -> Unit,
)

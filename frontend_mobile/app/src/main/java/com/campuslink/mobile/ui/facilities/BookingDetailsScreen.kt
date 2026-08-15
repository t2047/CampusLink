package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.BookingResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingDetailsScreen(viewModel: BookingDetailsViewModel, onBack: () -> Unit) {
    val details by viewModel.detailsState.collectAsStateWithLifecycle()
    val cancel by viewModel.cancelState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Booking Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to My Bookings")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = details) {
            BookingDetailsUiState.Loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            is BookingDetailsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (current.notFound) "Booking not found" else "Unable to load booking", fontWeight = FontWeight.Bold)
                Text(current.message)
                OutlinedButton(onClick = viewModel::retry) { Text("Retry") }
            }
            is BookingDetailsUiState.Success -> BookingDetailsContent(
                booking = current.booking,
                canCancel = viewModel.canCancel(current.booking),
                cancelState = cancel,
                actions = BookingDetailsActions(
                    onCancel = viewModel::requestCancel,
                    onDismissError = viewModel::clearCancelError,
                ),
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
            if (booking != null) {
                CancelConfirmationDialog(booking, true, onDismiss = {}, onConfirm = {})
            }
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(booking.space.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        BookingDetailLine("Booking ID", booking.bookingId.toString())
        BookingDetailLine("Building", booking.space.building)
        BookingDetailLine("Room", booking.space.roomNumber)
        BookingDetailLine("Capacity", booking.space.capacity.toString())
        BookingDetailLine("Equipment", booking.space.equipment.sorted().joinToString().ifEmpty { "None listed" })
        BookingDetailLine("Date", formatBookingDate(booking.startDateTime))
        BookingDetailLine("Time", formatBookingRange(booking))
        BookingDetailLine("Status", booking.status.name)
        BookingDetailLine("Created", booking.createdAt.replace('T', ' '))
        BookingDetailLine("Updated", booking.updatedAt.replace('T', ' '))
        if (canCancel) {
            Button(
                onClick = actions.onCancel,
                enabled = cancelState !is CancelBookingUiState.Cancelling,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel Booking") }
        }
        if (cancelState is CancelBookingUiState.Error) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cancellation not completed", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text(cancelState.message)
                    TextButton(onClick = actions.onDismissError) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun BookingDetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp))
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(booking.space.name)
                Text(formatBookingDate(booking.startDateTime))
                Text(formatBookingRange(booking))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !cancelling) {
                Text(if (cancelling) "Cancelling…" else "Confirm")
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

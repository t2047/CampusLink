package com.campuslink.mobile.ui.facilities

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.ui.CampusEmptyState
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusIconContainer
import com.campuslink.mobile.ui.CampusInfoRow
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusSectionHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun SpaceDetailsScreen(
    viewModel: SpaceDetailsViewModel,
    onBack: () -> Unit,
    onViewBooking: (Long) -> Unit,
    onMyBookings: () -> Unit,
    onReportIssue: (Long) -> Unit = {},
) {
    val details by viewModel.detailsState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val availability by viewModel.availabilityState.collectAsStateWithLifecycle()
    val booking by viewModel.bookingState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Space Details", onBack, "Back to search") },
    ) { padding ->
        when (val current = details) {
            SpaceDetailsUiState.Loading -> CampusLoadingState(
                label = "Loading space details…",
                modifier = Modifier.padding(padding),
            )
            is SpaceDetailsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusErrorState(
                    title = if (current.notFound) "Space not found" else "Unable to load space",
                    message = current.message,
                    retryLabel = "Retry",
                    onRetry = viewModel::retry,
                )
            }
            is SpaceDetailsUiState.Success -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(CampusSpacing.ExtraLarge),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
            ) {
                SpaceOverview(current.space)
                SpaceFacts(current.space)
                CampusSurfaceCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
                    ) {
                        Text("Equipment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            current.space.equipment.sorted().joinToString().ifEmpty { "None listed" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onReportIssue(current.space.spaceId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Report a facility issue") }
                AvailabilitySection(
                    selection = selection,
                    state = availability,
                    bookingState = booking,
                    viewModel = viewModel,
                    bookingActions = BookingActions(onViewBooking, onMyBookings),
                )
            }
        }
    }
    when (val current = booking) {
        is BookingCreationUiState.Confirming -> BookingConfirmationDialog(
            space = (details as? SpaceDetailsUiState.Success)?.space,
            request = current.request,
            submitting = false,
            onDismiss = viewModel::dismissBookingConfirmation,
            onConfirm = viewModel::confirmBooking,
        )
        is BookingCreationUiState.Submitting -> BookingConfirmationDialog(
            space = (details as? SpaceDetailsUiState.Success)?.space,
            request = current.request,
            submitting = true,
            onDismiss = {},
            onConfirm = {},
        )
        else -> Unit
    }
}

@Composable
private fun SpaceOverview(space: Space) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.ExtraLarge),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CampusIconContainer(
                    icon = Icons.Default.MeetingRoom,
                    contentDescription = null,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(space.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${space.building} · ${space.roomNumber}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CampusStatusChip(space.status, spaceTone(space.status))
            }
            Text(space.spaceType.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SpaceFacts(space: Space) {
    CampusSectionHeader("Key facts")
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Large)) {
                CampusInfoRow("Capacity", space.capacity.toString(), Modifier.weight(1f))
                CampusInfoRow("Floor", space.floor, Modifier.weight(1f))
            }
            CampusInfoRow("Opening hours", "${space.openingTime} – ${space.closingTime}")
        }
    }
}

@Composable
private fun AvailabilitySection(
    selection: AvailabilitySelection,
    state: AvailabilityUiState,
    bookingState: BookingCreationUiState,
    viewModel: SpaceDetailsViewModel,
    bookingActions: BookingActions,
) {
    val context = LocalContext.current
    CampusSectionHeader("Availability")
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CampusIconContainer(Icons.Default.EventAvailable, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("Check Availability", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose a date and time before booking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val initial = selection.date ?: LocalDate.now()
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> viewModel.updateDate(LocalDate.of(year, month + 1, day)) },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(selection.date?.format(DATE_DISPLAY) ?: "Choose date") }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
                TimeButton("Start time", selection.startTime, Modifier.weight(1f), viewModel::updateStartTime)
                TimeButton("End time", selection.endTime, Modifier.weight(1f), viewModel::updateEndTime)
            }
            Button(
                onClick = viewModel::checkAvailability,
                enabled = state !is AvailabilityUiState.Checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state is AvailabilityUiState.Checking) CircularProgressIndicator()
                else Text("Check Availability")
            }
            AvailabilityResult(state)
            if (state is AvailabilityUiState.Available && bookingState !is BookingCreationUiState.Success) {
                Button(
                    onClick = viewModel::requestBooking,
                    enabled = bookingState !is BookingCreationUiState.Submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Book This Space") }
            }
            BookingCreationResult(bookingState, viewModel, bookingActions)
        }
    }
}

@Composable
private fun BookingCreationResult(
    state: BookingCreationUiState,
    viewModel: SpaceDetailsViewModel,
    actions: BookingActions,
) {
    when (state) {
        BookingCreationUiState.Idle,
        is BookingCreationUiState.Confirming,
        is BookingCreationUiState.Submitting,
        -> Unit
        is BookingCreationUiState.Success -> CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
            ) {
                CampusStatusChip("CONFIRMED", CampusStatusTone.SUCCESS)
                Text("Booking confirmed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Booking ID: ${state.booking.bookingId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.booking.space.name)
                Text("${formatBookingDate(state.booking.startDateTime)} · ${formatBookingRange(state.booking)}")
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                    Button(onClick = { actions.onViewBooking(state.booking.bookingId) }) { Text("View Booking") }
                    OutlinedButton(onClick = actions.onMyBookings) { Text("My Bookings") }
                }
            }
        }
        is BookingCreationUiState.Error -> CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
            ) {
                CampusStatusChip("NOT COMPLETED", CampusStatusTone.ERROR)
                Text("Booking not completed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(state.message)
                TextButton(onClick = viewModel::clearBookingFeedback) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun BookingConfirmationDialog(
    space: Space?,
    request: CreateBookingRequest,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (space == null) return
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Book ${space.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                Text("${space.building} / ${space.roomNumber}")
                Text(formatBookingDate(request.startDateTime))
                Text(formatBookingRange(request.startDateTime, request.endDateTime))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(if (submitting) "Booking…" else "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
        },
    )
}

@Composable
private fun TimeButton(label: String, value: LocalTime?, modifier: Modifier, onSelected: (LocalTime) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val initial = value ?: LocalTime.of(9, 0)
            TimePickerDialog(
                context,
                { _, hour, minute -> onSelected(LocalTime.of(hour, minute)) },
                initial.hour,
                initial.minute,
                true,
            ).show()
        },
        modifier = modifier,
    ) { Text(value?.format(TIME_DISPLAY) ?: label) }
}

@Composable
private fun AvailabilityResult(state: AvailabilityUiState) {
    when (state) {
        AvailabilityUiState.Idle, AvailabilityUiState.Checking -> Unit
        is AvailabilityUiState.Available -> AvailabilityResultCard(
            "Available",
            "This space is available for the selected time.",
            CampusStatusTone.SUCCESS,
        )
        is AvailabilityUiState.Unavailable -> AvailabilityResultCard(
            "Unavailable",
            state.response.reasonCode?.replace('_', ' ') ?: "This time is not available.",
            CampusStatusTone.ERROR,
        )
        is AvailabilityUiState.Error -> AvailabilityResultCard(
            "Could not check availability",
            state.message,
            CampusStatusTone.ERROR,
        )
    }
}

@Composable
private fun AvailabilityResultCard(title: String, message: String, tone: CampusStatusTone) {
    Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
        CampusStatusChip(title.uppercase(), tone)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun spaceTone(status: String): CampusStatusTone = when (status.uppercase()) {
    "ACTIVE", "AVAILABLE", "OPEN" -> CampusStatusTone.SUCCESS
    "INACTIVE", "UNAVAILABLE", "CLOSED" -> CampusStatusTone.ERROR
    else -> CampusStatusTone.NEUTRAL
}

private val DATE_DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val TIME_DISPLAY = DateTimeFormatter.ofPattern("HH:mm")

private data class BookingActions(
    val onViewBooking: (Long) -> Unit,
    val onMyBookings: () -> Unit,
)

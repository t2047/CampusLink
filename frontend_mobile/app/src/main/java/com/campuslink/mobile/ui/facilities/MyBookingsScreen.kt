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
import androidx.compose.material.icons.automirrored.filled.EventNote
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.BookingStatus
import com.campuslink.mobile.ui.CampusEmptyState
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun MyBookingsScreen(
    viewModel: MyBookingsViewModel,
    onBack: () -> Unit,
    onOpenBooking: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onScreenVisible() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("My Bookings", onBack, "Back to Facilities") },
    ) { padding ->
        when (val current = state) {
            MyBookingsUiState.Loading -> CampusLoadingState("Loading bookings…", Modifier.padding(padding))
            MyBookingsUiState.Empty -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusEmptyState(
                    title = "No bookings yet",
                    message = "Book an available space and it will appear here.",
                    icon = Icons.AutoMirrored.Filled.EventNote,
                )
            }
            is MyBookingsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.ExtraLarge),
            ) {
                CampusErrorState(
                    title = "Unable to load bookings",
                    message = current.message,
                    retryLabel = "Retry",
                    onRetry = viewModel::refresh,
                )
            }
            is MyBookingsUiState.Success -> LazyColumn(
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
                        title = "Your bookings",
                        subtitle = "Upcoming reservations and booking history.",
                    )
                }
                items(current.bookings, key = { it.bookingId }) { booking ->
                    BookingListCard(booking, onClick = { onOpenBooking(booking.bookingId) })
                }
            }
        }
    }
}

@Composable
internal fun BookingListCard(booking: BookingResponse, onClick: () -> Unit) {
    Card(
        onClick = onClick,
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
                Text(
                    booking.space.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                CampusStatusChip(booking.status.name, bookingStatusTone(booking.status))
            }
            Text(
                "${booking.space.building} · ${booking.space.roomNumber}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatBookingDate(booking.startDateTime), style = MaterialTheme.typography.labelLarge)
            Text(formatBookingRange(booking), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun bookingStatusTone(status: BookingStatus): CampusStatusTone = when (status) {
    BookingStatus.CONFIRMED -> CampusStatusTone.SUCCESS
    BookingStatus.CANCELLED -> CampusStatusTone.ERROR
    BookingStatus.COMPLETED -> CampusStatusTone.NEUTRAL
}

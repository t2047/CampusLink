package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.BookingResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyBookingsScreen(
    viewModel: MyBookingsViewModel,
    onBack: () -> Unit,
    onOpenBooking: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onScreenVisible() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Bookings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Facilities")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            MyBookingsUiState.Loading -> LoadingBookings(Modifier.padding(padding))
            MyBookingsUiState.Empty -> EmptyBookings(Modifier.padding(padding))
            is MyBookingsUiState.Error -> BookingListError(
                message = current.message,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(padding),
            )
            is MyBookingsUiState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text("Your upcoming and past space bookings.", modifier = Modifier.padding(vertical = 8.dp)) }
                items(current.bookings, key = { it.bookingId }) { booking ->
                    BookingListCard(booking, onClick = { onOpenBooking(booking.bookingId) })
                }
            }
        }
    }
}

@Composable
private fun LoadingBookings(modifier: Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { CircularProgressIndicator() }
}

@Composable
private fun EmptyBookings(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No bookings yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Book an available space and it will appear here.")
    }
}

@Composable
private fun BookingListError(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Unable to load bookings", fontWeight = FontWeight.Bold)
        Text(message)
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun BookingListCard(booking: BookingResponse, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(booking.space.name, fontWeight = FontWeight.Bold)
            Text("${booking.space.building} / ${booking.space.roomNumber}")
            Text(formatBookingDate(booking.startDateTime))
            Text(formatBookingRange(booking))
            Text(booking.status.name, color = MaterialTheme.colorScheme.primary)
        }
    }
}

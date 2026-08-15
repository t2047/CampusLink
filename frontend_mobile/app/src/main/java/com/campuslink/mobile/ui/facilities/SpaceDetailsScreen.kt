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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.Space
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceDetailsScreen(viewModel: SpaceDetailsViewModel, onBack: () -> Unit) {
    val details by viewModel.detailsState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val availability by viewModel.availabilityState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Space Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to search")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = details) {
            SpaceDetailsUiState.Loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            is SpaceDetailsUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (current.notFound) "Space not found" else "Unable to load space", fontWeight = FontWeight.Bold)
                Text(current.message)
                OutlinedButton(onClick = viewModel::retry) { Text("Retry") }
            }
            is SpaceDetailsUiState.Success -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SpaceDetails(current.space)
                HorizontalDivider()
                AvailabilitySection(selection, availability, viewModel)
            }
        }
    }
}

@Composable
private fun SpaceDetails(space: Space) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(space.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        DetailLine("Building", space.building)
        DetailLine("Floor", space.floor)
        DetailLine("Room", space.roomNumber)
        DetailLine("Type", space.spaceType.replace('_', ' '))
        DetailLine("Capacity", space.capacity.toString())
        DetailLine("Equipment", space.equipment.sorted().joinToString().ifEmpty { "None listed" })
        DetailLine("Opening hours", "${space.openingTime} – ${space.closingTime}")
        DetailLine("Status", space.status)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun AvailabilitySection(
    selection: AvailabilitySelection,
    state: AvailabilityUiState,
    viewModel: SpaceDetailsViewModel,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Check Availability", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    }
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
        is AvailabilityUiState.Available -> ResultCard(
            title = "Available",
            message = "This space is available for the selected time.",
            isError = false,
        )
        is AvailabilityUiState.Unavailable -> ResultCard(
            title = "Unavailable",
            message = state.response.reasonCode?.replace('_', ' ') ?: "This time is not available.",
            isError = true,
        )
        is AvailabilityUiState.Error -> ResultCard("Could not check availability", state.message, true)
    }
}

@Composable
private fun ResultCard(title: String, message: String, isError: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(message)
        }
    }
}

private val DATE_DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val TIME_DISPLAY = DateTimeFormatter.ofPattern("HH:mm")

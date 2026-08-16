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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import com.campuslink.mobile.core.model.MaintenancePriority
import com.campuslink.mobile.core.model.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitMaintenanceScreen(
    viewModel: SubmitMaintenanceViewModel,
    onBack: () -> Unit,
    onViewRequest: (Long) -> Unit,
    onMyMaintenance: () -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val spaces by viewModel.spacesState.collectAsStateWithLifecycle()
    val submit by viewModel.submitState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report Maintenance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to facilities")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Tell Facilities what needs attention.")
            SpacePicker(spaces, form.selectedSpaceId, viewModel::selectSpace, viewModel::retrySpaces)
            val errors = (submit as? SubmitMaintenanceUiState.Error)?.fieldErrors.orEmpty()
            OutlinedTextField(
                value = form.facilityType,
                onValueChange = viewModel::updateFacilityType,
                label = { Text("Facility Type") },
                placeholder = { Text("e.g. Projector or Air Conditioning") },
                supportingText = {
                    Text(errors["facilityType"] ?: "${form.facilityType.length}/${SubmitMaintenanceViewModel.FACILITY_TYPE_MAX}")
                },
                isError = errors.containsKey("facilityType"),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.description,
                onValueChange = viewModel::updateDescription,
                label = { Text("Description") },
                minLines = 4,
                supportingText = {
                    Text(errors["description"] ?: "${form.description.length}/${SubmitMaintenanceViewModel.DESCRIPTION_MAX}")
                },
                isError = errors.containsKey("description"),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Priority", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaintenancePriority.entries.forEach { priority ->
                    FilterChip(
                        selected = form.priority == priority,
                        onClick = { viewModel.updatePriority(priority) },
                        label = { Text(priority.displayName()) },
                    )
                }
            }
            Button(
                onClick = viewModel::requestSubmit,
                enabled = submit !is SubmitMaintenanceUiState.Submitting && spaces is MaintenanceSpacesUiState.Success,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (submit is SubmitMaintenanceUiState.Submitting) CircularProgressIndicator()
                else Text("Submit Request")
            }
            SubmitFeedback(submit, viewModel, onViewRequest, onMyMaintenance)
        }
    }
    when (val current = submit) {
        is SubmitMaintenanceUiState.Confirming -> SubmitConfirmation(
            MaintenanceConfirmation(
                current.space,
                current.request.facilityType,
                current.request.priority,
                current.request.description,
            ),
            submitting = false,
            onDismiss = viewModel::dismissConfirmation,
            onConfirm = viewModel::confirmSubmit,
        )
        SubmitMaintenanceUiState.Submitting -> {
            val selected = (spaces as? MaintenanceSpacesUiState.Success)?.spaces
                ?.firstOrNull { it.spaceId == form.selectedSpaceId }
            if (selected != null) {
                SubmitConfirmation(
                    MaintenanceConfirmation(selected, form.facilityType, form.priority, form.description),
                    submitting = true,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpacePicker(
    state: MaintenanceSpacesUiState,
    selectedSpaceId: Long?,
    onSelect: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        MaintenanceSpacesUiState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator()
            Text("Loading campus spaces…")
        }
        is MaintenanceSpacesUiState.Error -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text("Retry Spaces") }
            }
        }
        is MaintenanceSpacesUiState.Success -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = state.spaces.firstOrNull { it.spaceId == selectedSpaceId }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = selected?.displayLocation().orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Space") },
                    placeholder = { Text("Choose a campus space") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.spaces.forEach { space ->
                        DropdownMenuItem(
                            text = { Text(space.displayLocation()) },
                            onClick = {
                                onSelect(space.spaceId)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmitFeedback(
    state: SubmitMaintenanceUiState,
    viewModel: SubmitMaintenanceViewModel,
    onViewRequest: (Long) -> Unit,
    onMyMaintenance: () -> Unit,
) {
    when (state) {
        is SubmitMaintenanceUiState.Success -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Request submitted", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Ticket #${state.maintenance.ticketId}")
                Text("Status: ${state.maintenance.status.displayName()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onViewRequest(state.maintenance.ticketId) }) { Text("View Request") }
                    OutlinedButton(onClick = onMyMaintenance) { Text("My Requests") }
                }
            }
        }
        is SubmitMaintenanceUiState.Error -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Request not submitted", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(state.message)
                TextButton(onClick = viewModel::clearFeedback) { Text("Dismiss") }
            }
        }
        else -> Unit
    }
}

@Composable
private fun SubmitConfirmation(
    confirmation: MaintenanceConfirmation,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Submit maintenance request?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(confirmation.space.displayLocation())
                Text(confirmation.facilityType)
                Text(confirmation.priority.name)
                Text(confirmation.description)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(if (submitting) "Submitting…" else "Submit")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

private data class MaintenanceConfirmation(
    val space: Space,
    val facilityType: String,
    val priority: MaintenancePriority,
    val description: String,
)

internal fun Space.displayLocation(): String = "$name · $building / $roomNumber"

internal fun MaintenancePriority.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

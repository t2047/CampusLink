package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.MaintenancePriority
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSectionHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar

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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Report Maintenance", onBack, "Back to Facilities") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = CampusSpacing.ExtraLarge,
                top = CampusSpacing.Small,
                end = CampusSpacing.ExtraLarge,
                bottom = CampusSpacing.Huge,
            ),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Large),
        ) {
            item {
                CampusPageHeader(
                    title = "Report an issue",
                    subtitle = "Tell Facilities what needs attention.",
                )
            }
            item {
                CampusSectionHeader("Location and issue")
                CampusSurfaceCard(Modifier.fillMaxWidth().padding(top = CampusSpacing.Medium)) {
                    MaintenanceFormCard(form, spaces, submit, viewModel)
                }
            }
            item {
                Button(
                    onClick = viewModel::requestSubmit,
                    enabled = submit !is SubmitMaintenanceUiState.Submitting &&
                        spaces is MaintenanceSpacesUiState.Success,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (submit is SubmitMaintenanceUiState.Submitting) CircularProgressIndicator()
                    else Text("Submit Request")
                }
            }
            item { SubmitFeedback(submit, viewModel, onViewRequest, onMyMaintenance) }
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

@Composable
private fun MaintenanceFormCard(
    form: MaintenanceForm,
    spaces: MaintenanceSpacesUiState,
    submit: SubmitMaintenanceUiState,
    viewModel: SubmitMaintenanceViewModel,
) {
    Column(
        Modifier.fillMaxWidth().padding(CampusSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
    ) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
            MaintenancePriority.entries.forEach { priority ->
                FilterChip(
                    selected = form.priority == priority,
                    onClick = { viewModel.updatePriority(priority) },
                    label = { Text(priority.displayName()) },
                )
            }
        }
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
        MaintenanceSpacesUiState.Loading -> CampusLoadingState("Loading campus spaces…")
        is MaintenanceSpacesUiState.Error -> CampusErrorState(
            title = "Unable to load spaces",
            message = state.message,
            retryLabel = "Retry Spaces",
            onRetry = onRetry,
        )
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
                    isError = selectedSpaceId == null,
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
        is SubmitMaintenanceUiState.Success -> CampusSurfaceCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
            ) {
                CampusStatusChip(state.maintenance.status.displayName(), CampusStatusTone.SUCCESS)
                Text("Request submitted", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Ticket #${state.maintenance.ticketId}")
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                    Button(onClick = { onViewRequest(state.maintenance.ticketId) }) { Text("View Request") }
                    OutlinedButton(onClick = onMyMaintenance) { Text("My Requests") }
                }
            }
        }
        is SubmitMaintenanceUiState.Error -> if (state.fieldErrors.isEmpty()) {
            CampusErrorState(
                title = "Request not submitted",
                message = state.message,
                retryLabel = "Dismiss",
                onRetry = viewModel::clearFeedback,
            )
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
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                Text(confirmation.space.displayLocation(), fontWeight = FontWeight.Bold)
                Text(confirmation.facilityType)
                CampusStatusChip(confirmation.priority.displayName(), priorityTone(confirmation.priority))
                Text(confirmation.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(if (submitting) "Submitting…" else "Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
        },
    )
}

private fun priorityTone(priority: MaintenancePriority): CampusStatusTone = when (priority) {
    MaintenancePriority.LOW -> CampusStatusTone.NEUTRAL
    MaintenancePriority.MEDIUM -> CampusStatusTone.INFO
    MaintenancePriority.HIGH -> CampusStatusTone.WARNING
}

private data class MaintenanceConfirmation(
    val space: Space,
    val facilityType: String,
    val priority: MaintenancePriority,
    val description: String,
)

internal fun Space.displayLocation(): String = "$name · $building / $roomNumber"

internal fun MaintenancePriority.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

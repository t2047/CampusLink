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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.ui.CampusEmptyState
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusIconContainer
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar

@Composable
fun SpaceSearchScreen(
    viewModel: SpaceSearchViewModel,
    onBack: () -> Unit,
    onOpenSpace: (Long) -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Search Spaces", onBack, "Back to Facilities") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = CampusSpacing.ExtraLarge,
                top = CampusSpacing.Small,
                end = CampusSpacing.ExtraLarge,
                bottom = CampusSpacing.Huge,
            ),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            item { SearchFilters(form, viewModel) }
            when (val current = state) {
                SpaceSearchUiState.Loading -> item { CampusLoadingState("Searching campus spaces…") }
                SpaceSearchUiState.Empty -> item {
                    CampusEmptyState(
                        title = "No matching spaces",
                        message = "Try widening your filters or resetting the search.",
                        icon = Icons.Default.Search,
                    )
                }
                is SpaceSearchUiState.Error -> item {
                    CampusErrorState(
                        title = "Unable to search spaces",
                        message = current.message,
                        retryLabel = "Retry",
                        onRetry = viewModel::retry,
                    )
                }
                is SpaceSearchUiState.Success -> {
                    item {
                        Text(
                            text = "${current.spaces.size} space${if (current.spaces.size == 1) "" else "s"} found",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(current.spaces, key = Space::spaceId) { space ->
                        SpaceCard(space = space, onClick = { onOpenSpace(space.spaceId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilters(form: SpaceSearchForm, viewModel: SpaceSearchViewModel) {
    var advancedFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    val activeAdvancedFilters = listOf(
        form.building,
        form.spaceType,
        form.minimumCapacity,
        form.equipment,
    ).count(String::isNotBlank)
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
                CampusIconContainer(Icons.Default.Search, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("Find the right space", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Filter by location, type, capacity or equipment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = form.query,
                onValueChange = viewModel::updateQuery,
                label = { Text("Keyword") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { advancedFiltersExpanded = !advancedFiltersExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (activeAdvancedFilters == 0) {
                        "Advanced filters"
                    } else {
                        "Advanced filters ($activeAdvancedFilters active)"
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (advancedFiltersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (advancedFiltersExpanded) {
                        "Hide advanced filters"
                    } else {
                        "Show advanced filters"
                    },
                )
            }
            if (advancedFiltersExpanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
                    OutlinedTextField(
                        value = form.building,
                        onValueChange = viewModel::updateBuilding,
                        label = { Text("Building") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.spaceType,
                        onValueChange = viewModel::updateSpaceType,
                        label = { Text("Space type") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = form.minimumCapacity,
                    onValueChange = viewModel::updateMinimumCapacity,
                    label = { Text("Minimum capacity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.equipment,
                    onValueChange = viewModel::updateEquipment,
                    label = { Text("Equipment") },
                    supportingText = { Text("Separate multiple items with commas") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
                Button(onClick = viewModel::search, modifier = Modifier.weight(1f)) { Text("Search") }
                OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) { Text("Reset") }
            }
        }
    }
}

@Composable
internal fun SpaceCard(space: Space, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
            ) {
                CampusIconContainer(Icons.Default.MeetingRoom, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(space.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${space.building} · ${space.roomNumber}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CampusStatusChip(space.status, spaceStatusTone(space.status))
            }
            Text("${space.spaceType.replace('_', ' ')} · Capacity ${space.capacity}")
            Text(
                if (space.equipment.isEmpty()) "No equipment listed" else space.equipment.sorted().joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun spaceStatusTone(status: String): CampusStatusTone = when (status.uppercase()) {
    "ACTIVE", "AVAILABLE", "OPEN" -> CampusStatusTone.SUCCESS
    "INACTIVE", "UNAVAILABLE", "CLOSED" -> CampusStatusTone.ERROR
    else -> CampusStatusTone.NEUTRAL
}

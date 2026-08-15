package com.campuslink.mobile.ui.facilities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSearchScreen(
    viewModel: SpaceSearchViewModel,
    onBack: () -> Unit,
    onOpenSpace: (Long) -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Spaces") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Facilities")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SearchFilters(
                    form = form,
                    viewModel = viewModel,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when (val current = state) {
                SpaceSearchUiState.Loading -> item {
                    Row(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                SpaceSearchUiState.Empty -> item {
                    Text("No spaces match these filters.", modifier = Modifier.padding(vertical = 24.dp))
                }
                is SpaceSearchUiState.Error -> item {
                    ErrorContent(current.message, viewModel::retry)
                }
                is SpaceSearchUiState.Success -> items(current.spaces, key = Space::spaceId) { space ->
                    SpaceCard(space = space, onClick = { onOpenSpace(space.spaceId) })
                }
            }
            item { Text("") }
        }
    }
}

@Composable
private fun SearchFilters(form: SpaceSearchForm, viewModel: SpaceSearchViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = form.query,
            onValueChange = viewModel::updateQuery,
            label = { Text("Keyword") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            label = { Text("Equipment (comma-separated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = viewModel::search, modifier = Modifier.weight(1f)) { Text("Search") }
            OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) { Text("Reset") }
        }
    }
}

@Composable
private fun SpaceCard(space: Space, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(space.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(space.status, color = MaterialTheme.colorScheme.primary)
            }
            Text("${space.building} · ${space.roomNumber}")
            Text("${space.spaceType.replace('_', ' ')} · Capacity ${space.capacity}")
            Text(
                if (space.equipment.isEmpty()) "No equipment listed" else space.equipment.sorted().joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 24.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = retry) { Text("Retry") }
    }
}

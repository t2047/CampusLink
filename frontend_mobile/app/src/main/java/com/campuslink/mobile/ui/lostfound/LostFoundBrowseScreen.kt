package com.campuslink.mobile.ui.lostfound

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.ReportType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LostFoundBrowseScreen(
    viewModel: LostFoundBrowseViewModel,
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse Lost & Found") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Lost & Found")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BrowseFilters(form, viewModel) }
            when (val current = state) {
                LostFoundBrowseUiState.Loading -> item { LoadingRow() }
                LostFoundBrowseUiState.Empty -> item {
                    Text("No open reports match these filters.", modifier = Modifier.padding(vertical = 24.dp))
                }
                is LostFoundBrowseUiState.Error -> item {
                    ErrorBlock(current.message, viewModel::retry)
                }
                is LostFoundBrowseUiState.Success -> {
                    items(current.reports, key = LostFoundReport::id) { report ->
                        LostFoundReportCard(report) { onOpenReport(report.id) }
                    }
                    if (!current.lastPage) {
                        item {
                            OutlinedButton(
                                onClick = viewModel::loadMore,
                                enabled = !current.loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (current.loadingMore) CircularProgressIndicator(Modifier.height(20.dp))
                                else Text("Load more")
                            }
                        }
                    }
                }
            }
            item { Text("") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseFilters(form: LostFoundBrowseForm, viewModel: LostFoundBrowseViewModel) {
    var categoryExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportType.entries.forEach { type ->
                FilterChip(
                    selected = form.reportType == type,
                    onClick = { viewModel.updateReportType(type) },
                    label = { Text(if (type == ReportType.FOUND) "Found items" else "Lost items") },
                )
            }
        }
        OutlinedTextField(
            value = form.keyword,
            onValueChange = viewModel::updateKeyword,
            label = { Text("Keyword") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = { categoryExpanded = !categoryExpanded },
        ) {
            OutlinedTextField(
                value = form.category?.displayName() ?: "Any category",
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Any category") },
                    onClick = {
                        viewModel.updateCategory(null)
                        categoryExpanded = false
                    },
                )
                ItemCategory.entries.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.displayName()) },
                        onClick = {
                            viewModel.updateCategory(category)
                            categoryExpanded = false
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.colour,
                onValueChange = viewModel::updateColour,
                label = { Text("Colour") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.location,
                onValueChange = viewModel::updateLocation,
                label = { Text("Location") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.dateFrom,
                onValueChange = viewModel::updateDateFrom,
                label = { Text("From YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.dateTo,
                onValueChange = viewModel::updateDateTo,
                label = { Text("To YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = viewModel::search, modifier = Modifier.weight(1f)) { Text("Search") }
            OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) { Text("Reset") }
        }
    }
}

@Composable
internal fun LostFoundReportCard(report: LostFoundReport, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            report.images.firstOrNull()?.let { image ->
                AsyncImage(
                    model = resolveLostFoundImageUrl(image.url),
                    contentDescription = report.itemName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(report.itemName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(report.reportType.name, color = MaterialTheme.colorScheme.primary)
                }
                Text("${report.category.displayName()} · ${report.status.name}")
                Text("${report.location} · ${report.eventDate}")
                Text(
                    report.description,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LoadingRow() {
    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun ErrorBlock(message: String, retry: () -> Unit) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = retry) { Text("Retry") }
    }
}

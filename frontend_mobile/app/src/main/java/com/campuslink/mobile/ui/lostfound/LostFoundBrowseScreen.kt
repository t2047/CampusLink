package com.campuslink.mobile.ui.lostfound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.core.model.ReportType
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
fun LostFoundBrowseScreen(
    viewModel: LostFoundBrowseViewModel,
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar("Browse Lost & Found", onBack, "Back to Lost & Found") },
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
            item { BrowseFilters(form, viewModel) }
            when (val current = state) {
                LostFoundBrowseUiState.Loading -> item { CampusLoadingState("Loading reports…") }
                LostFoundBrowseUiState.Empty -> item {
                    CampusEmptyState(
                        title = "No matching reports",
                        message = "Try another item type or widen your filters.",
                        icon = Icons.Default.Search,
                    )
                }
                is LostFoundBrowseUiState.Error -> item {
                    CampusErrorState(
                        title = "Unable to load reports",
                        message = current.message,
                        retryLabel = "Retry",
                        onRetry = viewModel::retry,
                    )
                }
                is LostFoundBrowseUiState.Success -> {
                    item {
                        Text(
                            "${current.reports.size} report${if (current.reports.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                                if (current.loadingMore) CircularProgressIndicator(Modifier.height(CampusSpacing.ExtraLarge))
                                else Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseFilters(form: LostFoundBrowseForm, viewModel: LostFoundBrowseViewModel) {
    var advancedVisible by remember { mutableStateOf(false) }
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
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
                label = { Text("Search reports") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { advancedVisible = !advancedVisible }) {
                Text(if (advancedVisible) "Hide filters" else "More filters")
            }
            if (advancedVisible) {
                AdvancedFilters(form, viewModel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
                Button(onClick = viewModel::search, modifier = Modifier.weight(1f)) { Text("Search") }
                OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) { Text("Reset") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFilters(form: LostFoundBrowseForm, viewModel: LostFoundBrowseViewModel) {
    var categoryExpanded by remember { mutableStateOf(false) }
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
    Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
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
    OutlinedTextField(
        value = form.dateFrom,
        onValueChange = viewModel::updateDateFrom,
        label = { Text("From date") },
        placeholder = { Text("YYYY-MM-DD") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.dateTo,
        onValueChange = viewModel::updateDateTo,
        label = { Text("To date") },
        placeholder = { Text("YYYY-MM-DD") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun LostFoundReportCard(report: LostFoundReport, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(CampusSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            val image = report.images.firstOrNull()
            if (image != null) {
                AsyncImage(
                    model = resolveLostFoundImageUrl(image.url),
                    contentDescription = "Photo of ${report.itemName}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(104.dp).clip(RoundedCornerShape(CampusSpacing.Medium)),
                )
            } else {
                CampusIconContainer(
                    icon = Icons.Default.Image,
                    contentDescription = "No photo available",
                    modifier = Modifier.size(104.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall),
            ) {
                Text(
                    report.itemName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.ExtraSmall)) {
                    CampusStatusChip(report.reportType.name, CampusStatusTone.INFO)
                    CampusStatusChip(report.status.name, reportStatusTone(report.status))
                }
                Text(report.category.displayName(), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${report.location} · ${report.eventDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun reportStatusTone(status: ReportStatus): CampusStatusTone = when (status) {
    ReportStatus.OPEN -> CampusStatusTone.SUCCESS
    ReportStatus.CLAIMED -> CampusStatusTone.INFO
    ReportStatus.CLOSED -> CampusStatusTone.NEUTRAL
}

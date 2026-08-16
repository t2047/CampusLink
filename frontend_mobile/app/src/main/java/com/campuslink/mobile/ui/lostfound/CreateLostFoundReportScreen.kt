package com.campuslink.mobile.ui.lostfound

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.core.model.UploadImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateLostFoundReportScreen(
    reportType: ReportType,
    viewModel: CreateLostFoundReportViewModel,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageUris = remember { mutableStateListOf<Uri>() }
    var imageReadError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
        imageReadError = if (selected.size > 5) "Only the first 5 images were selected." else null
        imageUris.clear()
        imageUris.addAll(selected.take(5))
    }
    val success = state as? CreateReportUiState.Success
    LaunchedEffect(success?.report?.id) {
        success?.let {
            viewModel.clearFeedback()
            onCreated(it.report.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reportType == ReportType.LOST) "Report lost item" else "Report found item") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ReportFormFields(form, viewModel) }
            item {
                OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose images (${imageUris.size}/5)")
                }
            }
            imageReadError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (imageUris.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(imageUris, key = Uri::toString) { uri ->
                            OutlinedCard {
                                Column {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Selected report image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.height(120.dp).fillParentMaxWidth(0.42f),
                                    )
                                    OutlinedButton(onClick = { imageUris.remove(uri) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                when (val current = state) {
                    CreateReportUiState.Idle -> Button(
                        onClick = {
                            scope.launch {
                                val images = runCatching { readImages(context, imageUris) }
                                    .onFailure { imageReadError = "One of the selected images could not be read." }
                                    .getOrNull()
                                if (images != null) viewModel.submit(images)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Publish report") }
                    CreateReportUiState.Submitting -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                    is CreateReportUiState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::clearFeedback) { Text("Dismiss") }
                    }
                    is CreateReportUiState.Success -> Unit
                }
            }
            item { Text("") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportFormFields(form: CreateReportForm, viewModel: CreateLostFoundReportViewModel) {
    var categoryExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = form.itemName,
            onValueChange = viewModel::updateItemName,
            label = { Text("Item name*") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = { categoryExpanded = !categoryExpanded },
        ) {
            OutlinedTextField(
                value = form.category.displayName(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Category*") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
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
        OutlinedTextField(
            value = form.description,
            onValueChange = viewModel::updateDescription,
            label = { Text("Description*") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
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
                label = { Text("Location*") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = form.eventDate,
            onValueChange = viewModel::updateEventDate,
            label = { Text("Date* (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.timeDescription,
            onValueChange = viewModel::updateTimeDescription,
            label = { Text("Time description") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private suspend fun readImages(context: Context, uris: List<Uri>): List<UploadImage> = withContext(Dispatchers.IO) {
    uris.mapIndexed { index, uri ->
        val contentType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        require(contentType in setOf("image/jpeg", "image/png", "image/webp"))
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        UploadImage(
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "image-${index + 1}",
            contentType = contentType,
            bytes = bytes,
        )
    }
}

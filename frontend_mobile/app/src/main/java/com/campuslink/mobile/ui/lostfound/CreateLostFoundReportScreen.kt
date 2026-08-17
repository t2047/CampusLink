package com.campuslink.mobile.ui.lostfound

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.core.model.UploadImage
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusSectionHeader
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val CREATE_REPORT_LIST_TAG = "create-report-list"

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
        imageReadError = if (selected.size > MAX_IMAGES) "Only the first 5 images were selected." else null
        imageUris.clear()
        imageUris.addAll(selected.take(MAX_IMAGES))
    }
    val success = state as? CreateReportUiState.Success
    LaunchedEffect(success?.report?.id) {
        success?.let {
            viewModel.clearFeedback()
            onCreated(it.report.id)
        }
    }

    val pageTitle = if (reportType == ReportType.LOST) "Report Lost Item" else "Report Found Item"
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CampusTopAppBar(pageTitle, onBack, "Back to Lost & Found") },
    ) { padding ->
        CreateReportContent(
            data = CreateReportUiData(pageTitle, form, state, imageUris, imageReadError),
            actions = CreateReportUiActions(
                pickImages = { picker.launch("image/*") },
                removeImage = imageUris::remove,
                dismissImageError = { imageReadError = null },
                dismissReportError = viewModel::clearFeedback,
                publish = {
                    scope.launch {
                        val images = runCatching { readImages(context, imageUris) }
                            .onFailure { error ->
                                imageReadError = if (error is IllegalArgumentException) {
                                    error.message
                                } else {
                                    "One of the selected images could not be read."
                                }
                            }
                            .getOrNull()
                        if (images != null) viewModel.submit(images)
                    }
                },
            ),
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun CreateReportContent(
    data: CreateReportUiData,
    actions: CreateReportUiActions,
    viewModel: CreateLostFoundReportViewModel,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(CREATE_REPORT_LIST_TAG),
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
                title = data.pageTitle,
                subtitle = "Share clear details to help the campus community respond.",
            )
        }
        item {
            CampusSectionHeader("Item details")
            CampusSurfaceCard(Modifier.fillMaxWidth().padding(top = CampusSpacing.Medium)) {
                ReportFormFields(data.form, viewModel)
            }
        }
        item { CampusSectionHeader("Photos", supportingText = "Optional · up to 5") }
        item {
            OutlinedButton(onClick = actions.pickImages, modifier = Modifier.fillMaxWidth()) {
                Text("Choose images (${data.images.size}/$MAX_IMAGES)")
            }
        }
        data.imageError?.let { message ->
            item {
                CampusErrorState("Image selection issue", message, "Dismiss", actions.dismissImageError)
            }
        }
        if (data.images.isNotEmpty()) item { SelectedImages(data.images, actions.removeImage) }
        item { CreateReportSubmitState(data.state, actions) }
    }
}

@Composable
private fun SelectedImages(images: List<Uri>, onRemove: (Uri) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
        items(images, key = Uri::toString) { uri ->
            OutlinedCard {
                Column {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Selected report image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(120.dp)
                            .fillParentMaxWidth(0.42f)
                            .clip(RoundedCornerShape(CampusSpacing.Medium)),
                    )
                    OutlinedButton(
                        onClick = { onRemove(uri) },
                        modifier = Modifier.padding(CampusSpacing.Small),
                    ) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun CreateReportSubmitState(state: CreateReportUiState, actions: CreateReportUiActions) {
    when (state) {
        CreateReportUiState.Idle -> Button(
            onClick = actions.publish,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Publish report") }
        CreateReportUiState.Submitting -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        is CreateReportUiState.Error -> CampusErrorState(
            title = "Report not published",
            message = state.message,
            retryLabel = "Dismiss",
            onRetry = actions.dismissReportError,
        )
        is CreateReportUiState.Success -> Unit
    }
}

private data class CreateReportUiData(
    val pageTitle: String,
    val form: CreateReportForm,
    val state: CreateReportUiState,
    val images: List<Uri>,
    val imageError: String?,
)

private data class CreateReportUiActions(
    val pickImages: () -> Unit,
    val removeImage: (Uri) -> Unit,
    val dismissImageError: () -> Unit,
    val dismissReportError: () -> Unit,
    val publish: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportFormFields(form: CreateReportForm, viewModel: CreateLostFoundReportViewModel) {
    var categoryExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(CampusSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
    ) {
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
            supportingText = { Text("Include identifying details without sharing sensitive information") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.colour,
            onValueChange = viewModel::updateColour,
            label = { Text("Colour") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.location,
            onValueChange = viewModel::updateLocation,
            label = { Text("Location*") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.eventDate,
            onValueChange = viewModel::updateEventDate,
            label = { Text("Date*") },
            supportingText = { Text("YYYY-MM-DD") },
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
        require(contentType in setOf("image/jpeg", "image/png", "image/webp")) {
            "Only JPEG, PNG, and WebP images are supported."
        }
        val declaredSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        require(declaredSize == null || declaredSize <= MAX_UPLOAD_IMAGE_BYTES) {
            "Each image must be 10 MB or smaller."
        }
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "The selected image could not be opened."
        }.use { it.readImageBytes() }
        UploadImage(
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "image-${index + 1}",
            contentType = contentType,
            bytes = bytes,
        )
    }
}

private const val MAX_IMAGES = 5
internal const val MAX_UPLOAD_IMAGE_BYTES = 10 * 1024 * 1024

internal fun InputStream.readImageBytes(maxBytes: Int = MAX_UPLOAD_IMAGE_BYTES): ByteArray {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        totalBytes += count
        require(totalBytes <= maxBytes) { "Each image must be 10 MB or smaller." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

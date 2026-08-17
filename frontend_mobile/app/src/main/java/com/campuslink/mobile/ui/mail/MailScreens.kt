@file:Suppress("LongParameterList", "LongMethod", "MaxLineLength")

package com.campuslink.mobile.ui.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.CalendarEvent
import com.campuslink.mobile.core.model.ExtractedSchedule
import com.campuslink.mobile.core.model.MailMessage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val MAIL_FOLDERS = listOf(
    "inbox" to "Inbox",
    "sent" to "Sent",
    "archived" to "Archive",
    "trash" to "Trash",
    "spam" to "Spam",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailHomeScreen(
    viewModel: MailHomeViewModel,
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
    onCompose: () -> Unit,
    onCalendar: () -> Unit,
    openAuthorization: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.authUrl) {
        state.authUrl?.let {
            openAuthorization(it)
            viewModel.clearAuthUrl()
            viewModel.pollOAuthStatus()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onCalendar) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Calendar")
                    }
                    IconButton(onClick = onCompose) {
                        Icon(Icons.Default.Add, contentDescription = "Compose")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.connected != true) {
                item { GmailConnectionCard(state, viewModel::requestAuthorization, viewModel::refresh) }
            } else {
                item {
                    Text(
                        text = state.connectedEmail?.let { "Connected account: $it" } ?: "Gmail connected",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    TabRow(selectedTabIndex = MAIL_FOLDERS.indexOfFirst { it.first == state.folder }.coerceAtLeast(0)) {
                        MAIL_FOLDERS.forEach { (value, label) ->
                            Tab(
                                selected = state.folder == value,
                                onClick = { viewModel.updateFolder(value) },
                                text = { Text(label) },
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        modifier = Modifier.fillMaxWidth().testTag("mail-search"),
                        label = { Text("Search mail") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = viewModel::search) { Text("Search") }
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.unreadOnly,
                            onClick = { viewModel.updateUnreadOnly(!state.unreadOnly) },
                            label = { Text("Unread") },
                        )
                        FilterChip(
                            selected = state.starredOnly,
                            onClick = { viewModel.updateStarredOnly(!state.starredOnly) },
                            label = { Text("Starred") },
                        )
                    }
                }
                state.actionMessage?.let { message ->
                    item { FeedbackText(message, MaterialThemeColors.Success) }
                }
                state.error?.let { message ->
                    item { ErrorBlock(message, viewModel::refresh) }
                }
                if (state.loading) {
                    item { LoadingRow() }
                } else if (!state.loading && state.messages.isEmpty() && state.error == null) {
                    item { EmptyMailState(state.folder) }
                } else {
                    items(state.messages, key = MailMessage::id) { message ->
                        MailMessageCard(
                            message = message,
                            onClick = { onOpenMessage(message.id) },
                            onToggleRead = { viewModel.toggleRead(message) },
                            onToggleStar = { viewModel.toggleStar(message) },
                            onArchive = { viewModel.archive(message) },
                            onDelete = { viewModel.delete(message) },
                        )
                    }
                    if (!state.lastPage) {
                        item {
                            OutlinedButton(
                                onClick = viewModel::loadMore,
                                enabled = !state.loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.loadingMore) CircularProgressIndicator()
                                else Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GmailConnectionCard(
    state: MailHomeUiState,
    connect: () -> Unit,
    retry: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Link, contentDescription = null)
            Text("Connect your Gmail", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Authorize your own Gmail account to read and manage campus mail.")
            state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            state.actionMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Text("Authorize Gmail")
            }
            TextButton(onClick = retry, modifier = Modifier.align(Alignment.End)) { Text("Check again") }
        }
    }
}

@Composable
private fun MailMessageCard(
    message: MailMessage,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleStar: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (message.read) {
                androidx.compose.material3.MaterialTheme.colorScheme.surface
            } else {
                androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text(message.subject.ifBlank { "(No subject)" }, fontWeight = if (message.read) FontWeight.Normal else FontWeight.Bold)
                },
                supportingContent = {
                    Column {
                        Text(message.sender, maxLines = 1)
                        Text(message.preview, maxLines = 2, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = {
                        IconButton(onClick = onToggleStar) {
                        Icon(
                            if (message.starred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (message.starred) "Unstar" else "Star",
                        )
                    }
                },
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onToggleRead) {
                    Icon(Icons.Default.MarkEmailUnread, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (message.read) "Mark unread" else "Mark read")
                }
                TextButton(onClick = onArchive) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Archive")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Trash")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDetailsScreen(
    viewModel: MailDetailsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message = state.message
    LaunchedEffect(state.removed) {
        if (state.removed) onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mail details") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    message?.let { message ->
                        IconButton(onClick = viewModel::toggleStar) {
                            Icon(if (message.starred) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Star")
                        }
                        IconButton(onClick = viewModel::archive) {
                            Icon(Icons.Default.Archive, contentDescription = "Archive")
                        }
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Default.Delete, contentDescription = "Trash")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingRow(Modifier.padding(padding))
            state.error != null -> ErrorBlock(state.error, viewModel::retry, Modifier.padding(padding))
            message != null -> MailDetailsContent(message, state.actionMessage, state.error, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MailDetailsContent(
    message: MailMessage,
    actionMessage: String?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(message.subject.ifBlank { "(No subject)" }, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(message.sender, fontWeight = FontWeight.SemiBold)
            if (message.recipients.isNotEmpty()) Text("To: ${message.recipients.joinToString()}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDate(message.created_at), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actionMessage?.let { item { FeedbackText(it, MaterialThemeColors.Success) } }
        error?.let { item { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) } }
        item {
            HorizontalDivider()
            Text(message.body.ifBlank { "(No message body)" }, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMailScreen(viewModel: ComposeMailViewModel, onBack: () -> Unit) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is ComposeMailUiState.Sent) onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = viewModel::send, enabled = state !is ComposeMailUiState.Sending) {
                    if (state is ComposeMailUiState.Sending) CircularProgressIndicator()
                    else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.recipients,
                onValueChange = viewModel::updateRecipients,
                modifier = Modifier.fillMaxWidth().testTag("mail-recipients"),
                label = { Text("Recipients") },
                supportingText = { Text("Separate multiple addresses with commas") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = form.subject,
                onValueChange = viewModel::updateSubject,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Subject") },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.body,
                onValueChange = viewModel::updateBody,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Message") },
            )
            when (val current = state) {
                is ComposeMailUiState.Error -> Text(current.message, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: CalendarViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::beginCreate) { Icon(Icons.Default.Add, contentDescription = "Add event") }
                    IconButton(onClick = viewModel::extract, enabled = !state.extracting) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Extract from mail")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { item { ErrorBlock(it, viewModel::clearFeedback) } }
            state.actionMessage?.let { item { FeedbackText(it, MaterialThemeColors.Success) } }
            if (state.editorVisible) {
                item { CalendarEditor(state, viewModel) }
            }
            item {
                if (state.extracting) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text("Scanning recent emails for schedules…")
                        }
                    }
                }
            }
            if (state.proposals.isNotEmpty()) {
                item { Text("Suggested events", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(state.proposals, key = ExtractedSchedule::key) { proposal ->
                    ExtractedScheduleCard(proposal, proposal.key in state.selectedProposalKeys, viewModel::toggleProposal)
                }
                item {
                    Button(onClick = viewModel::importSelected, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import selected")
                    }
                }
            }
            item { Text("My events", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (state.loading) {
                item { LoadingRow() }
            } else if (state.events.isEmpty()) {
                item { Text("No calendar events yet.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.events, key = CalendarEvent::id) { event ->
                    CalendarEventCard(event, viewModel::beginEdit) { pendingDelete = it }
                }
            }
        }
    }
    pendingDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this event?") },
            text = { Text("${event.title} will be permanently removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(event)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CalendarEditor(state: CalendarUiState, viewModel: CalendarViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (state.editingId == null) "New event" else "Edit event", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(state.form.title, viewModel::updateTitle, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(state.form.description, viewModel::updateDescription, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.form.location, viewModel::updateLocation, label = { Text("Location") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                state.form.startTime,
                viewModel::updateStartTime,
                label = { Text("Start (ISO, e.g. 2026-08-16T14:00:00)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                state.form.endTime,
                viewModel::updateEndTime,
                label = { Text("End (ISO, e.g. 2026-08-16T16:00:00)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.form.allDay, viewModel::updateAllDay)
                Text("All day")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::cancelEdit) { Text("Cancel") }
                Button(onClick = viewModel::save, enabled = !state.saving) {
                    if (state.saving) CircularProgressIndicator()
                    else Text("Save")
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(event: CalendarEvent, onEdit: (CalendarEvent) -> Unit, onDelete: (CalendarEvent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(event.title, fontWeight = FontWeight.Bold) },
            supportingContent = {
                Column {
                    Text("${formatDate(event.start_time)} – ${formatDate(event.end_time)}")
                    if (event.location.isNotBlank()) Text(event.location)
                    if (event.source == "mail") Text("Imported from mail", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            },
            trailingContent = {
                Row {
                    IconButton(onClick = { onEdit(event) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { onDelete(event) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            },
        )
    }
}

@Composable
private fun ExtractedScheduleCard(event: ExtractedSchedule, selected: Boolean, onToggle: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onToggle(event.key) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, { onToggle(event.key) })
            Column(Modifier.weight(1f)) {
                Text(event.title, fontWeight = FontWeight.Bold)
                Text("${formatDate(event.start_time)} – ${formatDate(event.end_time)}")
                if (event.location.isNotBlank()) Text(event.location, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.email_subject.isNotBlank()) Text("From: ${event.email_subject}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LoadingRow(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
}

@Composable
private fun EmptyMailState(folder: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Email, contentDescription = null)
        Text("No messages in ${MAIL_FOLDERS.firstOrNull { it.first == folder }?.second ?: folder}.")
    }
}

@Composable
private fun ErrorBlock(message: String?, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message ?: "Something went wrong.", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = retry) { Text("Retry") }
    }
}

private object MaterialThemeColors {
    val Success = Color(0xFF1B7F4B)
}

@Composable
private fun FeedbackText(message: String, color: Color) {
    Text(message, color = color, modifier = Modifier.padding(horizontal = 4.dp))
}

private fun formatDate(value: String): String = runCatching {
    LocalDateTime.parse(value.substringBefore('+').substringBefore('Z')).format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm"))
}.getOrElse { value.replace('T', ' ') }

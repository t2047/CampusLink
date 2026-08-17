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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campuslink.mobile.core.model.CalendarEvent
import com.campuslink.mobile.core.model.ExtractedSchedule
import com.campuslink.mobile.core.model.MailMessage
import com.campuslink.mobile.ui.CalendarStrings
import com.campuslink.mobile.ui.CampusActionCard
import com.campuslink.mobile.ui.CampusActionCopy
import com.campuslink.mobile.ui.CampusEmptyState
import com.campuslink.mobile.ui.CampusErrorState
import com.campuslink.mobile.ui.CampusInfoRow
import com.campuslink.mobile.ui.CampusLoadingState
import com.campuslink.mobile.ui.CampusPageHeader
import com.campuslink.mobile.ui.CampusStatusChip
import com.campuslink.mobile.ui.CampusStatusTone
import com.campuslink.mobile.ui.CampusSurfaceCard
import com.campuslink.mobile.ui.CampusTopAppBar
import com.campuslink.mobile.ui.CampusSpacing
import com.campuslink.mobile.ui.MailStrings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun mailFolders(text: MailStrings) = listOf(
    "inbox" to text.inbox,
    "sent" to text.sent,
    "archived" to text.archive,
    "trash" to text.trash,
    "spam" to text.spam,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailHomeScreen(
    viewModel: MailHomeViewModel,
    text: MailStrings,
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
            CampusTopAppBar(
                title = text.title,
                onBack = onBack,
                backDescription = text.back,
                actions = {
                    IconButton(onClick = onCalendar) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = text.calendar)
                    }
                    IconButton(onClick = onCompose) {
                        Icon(Icons.Default.Add, contentDescription = text.compose)
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = text.refresh)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = CampusSpacing.Large),
            contentPadding = PaddingValues(vertical = CampusSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            if (state.connected != true) {
                item { GmailConnectionCard(state, text, viewModel::requestAuthorization, viewModel::refresh) }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.connectedEmail?.let { text.connectedAccount.format(it) } ?: text.connected,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(CampusSpacing.Small))
                        CampusStatusChip(text.connected, CampusStatusTone.SUCCESS)
                    }
                }
                item {
                    MailFolderTabs(
                        selectedFolder = state.folder,
                        text = text,
                        onFolderSelected = viewModel::updateFolder,
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        modifier = Modifier.fillMaxWidth().testTag("mail-search"),
                        label = { Text(text.searchMail) },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = viewModel::search) { Text(text.search) }
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                        FilterChip(
                            selected = state.unreadOnly,
                            onClick = { viewModel.updateUnreadOnly(!state.unreadOnly) },
                            label = { Text(text.unread) },
                        )
                        FilterChip(
                            selected = state.starredOnly,
                            onClick = { viewModel.updateStarredOnly(!state.starredOnly) },
                            label = { Text(text.starred) },
                        )
                    }
                }
                state.actionMessage?.let { message ->
                    item { FeedbackText(localizeMailFeedback(message, text)) }
                }
                state.error?.let { message ->
                    item { ErrorBlock(text.unableToLoad, message, text.retry, viewModel::refresh) }
                }
                if (state.loading) {
                    item { CampusLoadingState(text.loading) }
                } else if (!state.loading && state.messages.isEmpty() && state.error == null) {
                    item { EmptyMailState(state.folder, text) }
                } else {
                    items(state.messages, key = MailMessage::id) { message ->
                        MailMessageCard(
                            message = message,
                            onClick = { onOpenMessage(message.id) },
                            onToggleRead = { viewModel.toggleRead(message) },
                            onToggleStar = { viewModel.toggleStar(message) },
                            onArchive = { viewModel.archive(message) },
                            onDelete = { viewModel.delete(message) },
                            text = text,
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
                                else Text(text.loadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MailFolderTabs(
    selectedFolder: String,
    text: MailStrings,
    onFolderSelected: (String) -> Unit,
) {
    val folders = mailFolders(text)
    ScrollableTabRow(
        selectedTabIndex = folders.indexOfFirst { it.first == selectedFolder }.coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().testTag("mail-folder-tabs"),
        edgePadding = 0.dp,
    ) {
        folders.forEach { (value, label) ->
            Tab(
                selected = selectedFolder == value,
                onClick = { onFolderSelected(value) },
                modifier = Modifier.testTag("mail-folder-$value"),
                text = {
                    Text(
                        text = label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                },
            )
        }
    }
}

@Composable
private fun GmailConnectionCard(
    state: MailHomeUiState,
    text: MailStrings,
    connect: () -> Unit,
    retry: () -> Unit,
) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CampusSpacing.ExtraLarge), verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
            Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            CampusPageHeader(title = text.connectTitle, subtitle = text.connectDescription)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.actionMessage?.let {
                Text(localizeMailFeedback(it, text), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Text(text.authorizeGmail)
            }
            TextButton(onClick = retry, modifier = Modifier.align(Alignment.End)) { Text(text.checkAgain) }
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
    text: MailStrings,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (message.read) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text(message.subject.ifBlank { text.noSubject }, fontWeight = if (message.read) FontWeight.Normal else FontWeight.Bold)
                },
                supportingContent = {
                    Column {
                        Text(message.sender, maxLines = 1)
                        Text(message.preview, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = {
                        IconButton(onClick = onToggleStar) {
                        Icon(
                            if (message.starred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (message.starred) text.unstar else text.star,
                        )
                    }
                },
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CampusSpacing.ExtraSmall),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onToggleRead,
                    contentPadding = PaddingValues(horizontal = CampusSpacing.ExtraSmall),
                    modifier = Modifier.testTag("mail-action-read"),
                ) {
                    Icon(Icons.Default.MarkEmailUnread, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(CampusSpacing.ExtraSmall))
                    Text(
                        if (message.read) text.markUnread else text.markRead,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(
                    onClick = onArchive,
                    contentPadding = PaddingValues(horizontal = CampusSpacing.ExtraSmall),
                    modifier = Modifier.testTag("mail-action-archive"),
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(CampusSpacing.ExtraSmall))
                    Text(text.archive, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = CampusSpacing.ExtraSmall),
                    modifier = Modifier.testTag("mail-action-trash"),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(CampusSpacing.ExtraSmall))
                    Text(text.trash, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDetailsScreen(
    viewModel: MailDetailsViewModel,
    text: MailStrings,
    onBack: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message = state.message
    LaunchedEffect(state.removed) {
        if (state.removed) onBack()
    }
    Scaffold(
        topBar = {
            CampusTopAppBar(
                title = text.detailsTitle,
                onBack = onBack,
                backDescription = text.back,
                actions = {
                    IconButton(onClick = onOpenCalendar) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = text.calendar)
                    }
                    message?.let { message ->
                        IconButton(onClick = viewModel::toggleRead) {
                            Icon(
                                Icons.Default.MarkEmailUnread,
                                contentDescription = if (message.read) text.markUnread else text.markRead,
                            )
                        }
                        IconButton(onClick = viewModel::toggleStar) {
                            Icon(
                                if (message.starred) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (message.starred) text.unstar else text.star,
                            )
                        }
                        IconButton(onClick = viewModel::archive) {
                            Icon(Icons.Default.Archive, contentDescription = text.archive)
                        }
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Default.Delete, contentDescription = text.trash)
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> CampusLoadingState(text.loadingMessage, Modifier.padding(padding))
            state.error != null -> ErrorBlock(
                text.unableToLoadMessage,
                state.error,
                text.retry,
                viewModel::retry,
                Modifier.padding(padding).padding(horizontal = CampusSpacing.Large),
            )
            message != null -> MailDetailsContent(message, state.actionMessage, state.error, text, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MailDetailsContent(
    message: MailMessage,
    actionMessage: String?,
    error: String?,
    text: MailStrings,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = CampusSpacing.Large),
        contentPadding = PaddingValues(vertical = CampusSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
    ) {
        item {
            CampusSurfaceCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(CampusSpacing.ExtraLarge),
                    verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
                ) {
                    Text(
                        message.subject.ifBlank { text.noSubject },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    CampusInfoRow(text.from, message.sender)
                    if (message.recipients.isNotEmpty()) {
                        CampusInfoRow(text.recipients, message.recipients.joinToString())
                    }
                    CampusInfoRow(text.date, formatDate(message.created_at))
                }
            }
        }
        actionMessage?.let { item { FeedbackText(localizeMailFeedback(it, text)) } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            CampusSurfaceCard(Modifier.fillMaxWidth()) {
                Text(
                    message.body.ifBlank { text.noBody },
                    modifier = Modifier.padding(CampusSpacing.ExtraLarge),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMailScreen(viewModel: ComposeMailViewModel, text: MailStrings, onBack: () -> Unit) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is ComposeMailUiState.Sent) onBack()
    }
    Scaffold(
        topBar = {
            CampusTopAppBar(
                title = text.newMessage,
                onBack = onBack,
                backDescription = text.back,
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(CampusSpacing.Large), horizontalArrangement = Arrangement.End) {
                Button(onClick = viewModel::send, enabled = state !is ComposeMailUiState.Sending) {
                    if (state is ComposeMailUiState.Sending) CircularProgressIndicator()
                    else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(CampusSpacing.Small))
                        Text(text.send)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(CampusSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            OutlinedTextField(
                value = form.recipients,
                onValueChange = viewModel::updateRecipients,
                modifier = Modifier.fillMaxWidth().testTag("mail-recipients"),
                label = { Text(text.recipients) },
                supportingText = { Text(text.recipientsHelper) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = form.subject,
                onValueChange = viewModel::updateSubject,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text.subject) },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.body,
                onValueChange = viewModel::updateBody,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text(text.message) },
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
fun CalendarScreen(
    viewModel: CalendarViewModel,
    text: CalendarStrings,
    onBack: () -> Unit,
    onOpenSourceEmail: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CalendarEvent?>(null) }
    Scaffold(
        topBar = {
            CampusTopAppBar(
                title = text.title,
                onBack = onBack,
                backDescription = text.back,
                actions = {
                    IconButton(onClick = viewModel::beginCreate) { Icon(Icons.Default.Add, contentDescription = text.addEvent) }
                    IconButton(onClick = viewModel::extract, enabled = !state.extracting) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = text.extractFromMail)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = CampusSpacing.Large),
            contentPadding = PaddingValues(vertical = CampusSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
        ) {
            state.error?.let { item { ErrorBlock(text.unableToLoad, it, text.retry, viewModel::clearFeedback) } }
            state.actionMessage?.let { item { FeedbackText(localizeCalendarFeedback(it, text)) } }
            if (state.editorVisible) {
                item { CalendarEditor(state, viewModel, text) }
            }
            item {
                CalendarMailScanOptions(state, viewModel, text)
            }
            item {
                if (state.extracting) {
                    CampusSurfaceCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(CampusSpacing.Large),
                            horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(text.scanning)
                        }
                    }
                }
            }
            if (state.proposals.isNotEmpty()) {
                item { CampusPageHeader(text.suggestedEvents, text.extractFromMail) }
                items(state.proposals, key = ExtractedSchedule::key) { proposal ->
                    ExtractedScheduleCard(
                        event = proposal,
                        selected = proposal.key in state.selectedProposalKeys,
                        onToggle = viewModel::toggleProposal,
                        onOpenSourceEmail = { proposal.source_email_id?.let(onOpenSourceEmail) },
                        text = text,
                    )
                }
                item {
                    Button(onClick = viewModel::importSelected, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(CampusSpacing.Small))
                        Text(text.importSelected)
                    }
                }
            }
            item { CampusPageHeader(text.myEvents, text.events) }
            if (state.loading) {
                item { CampusLoadingState(text.loading) }
            } else if (state.events.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
                        CampusEmptyState(text.myEvents, text.noEvents, icon = Icons.Default.CalendarMonth)
                        CampusActionCard(
                            copy = CampusActionCopy(text.newEvent, text.addEvent),
                            icon = Icons.Default.Add,
                            onClick = viewModel::beginCreate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                items(state.events, key = CalendarEvent::id) { event ->
                    CalendarEventCard(
                        event = event,
                        onEdit = viewModel::beginEdit,
                        onDelete = { pendingDelete = it },
                        onOpenSourceEmail = { event.source_email_id?.let(onOpenSourceEmail) },
                        text = text,
                    )
                }
            }
        }
    }
    pendingDelete?.let { event ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text.deleteQuestion) },
            text = { Text(text.deleteMessage.format(event.title)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(event)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(text.delete) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(text.cancel) } },
        )
    }
}

@Composable
private fun CalendarMailScanOptions(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
    text: CalendarStrings,
) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(CampusSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(CampusSpacing.Small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text.extractFromMail, fontWeight = FontWeight.Bold)
                    Text(text.scanWindow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = viewModel::extract, enabled = !state.extracting) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(CampusSpacing.Small))
                    Text(text.scan)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CampusSpacing.Small)) {
                listOf(
                    0 to text.scanToday,
                    7 to text.scanSevenDays,
                    30 to text.scanThirtyDays,
                ).forEach { (days, label) ->
                    FilterChip(
                        selected = state.extractionDays == days,
                        onClick = { viewModel.updateExtractionDays(days) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarEditor(state: CalendarUiState, viewModel: CalendarViewModel, text: CalendarStrings) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CampusSpacing.Large), verticalArrangement = Arrangement.spacedBy(CampusSpacing.Medium)) {
            Text(
                if (state.editingId == null) text.newEvent else text.editEvent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                state.form.title,
                viewModel::updateTitle,
                label = { Text(text.eventTitle) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                state.form.description,
                viewModel::updateDescription,
                label = { Text(text.description) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state.form.location,
                viewModel::updateLocation,
                label = { Text(text.location) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                state.form.startTime,
                viewModel::updateStartTime,
                label = { Text(text.startTime) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                state.form.endTime,
                viewModel::updateEndTime,
                label = { Text(text.endTime) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.form.allDay, viewModel::updateAllDay)
                Text(text.allDay)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::cancelEdit) { Text(text.cancel) }
                Button(onClick = viewModel::save, enabled = !state.saving) {
                    if (state.saving) CircularProgressIndicator()
                    else Text(text.save)
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(
    event: CalendarEvent,
    onEdit: (CalendarEvent) -> Unit,
    onDelete: (CalendarEvent) -> Unit,
    onOpenSourceEmail: () -> Unit,
    text: CalendarStrings,
) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(event.title, fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Column {
                        Text("${formatDate(event.start_time)} – ${formatDate(event.end_time)}")
                        if (event.location.isNotBlank()) Text(event.location)
                        if (event.source == "mail") CampusStatusChip(text.fromMail, CampusStatusTone.INFO)
                    }
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onEdit(event) }) { Icon(Icons.Default.Edit, contentDescription = text.edit) }
                        IconButton(onClick = { onDelete(event) }) { Icon(Icons.Default.Delete, contentDescription = text.delete) }
                    }
                },
            )
            if (!event.source_email_id.isNullOrBlank()) {
                TextButton(onClick = onOpenSourceEmail, modifier = Modifier.padding(start = CampusSpacing.Medium)) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(CampusSpacing.Small))
                    Text(text.viewSourceEmail)
                }
            }
        }
    }
}

@Composable
private fun ExtractedScheduleCard(
    event: ExtractedSchedule,
    selected: Boolean,
    onToggle: (String) -> Unit,
    onOpenSourceEmail: () -> Unit,
    text: CalendarStrings,
) {
    CampusSurfaceCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(event.key) }.padding(CampusSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(selected, { onToggle(event.key) })
                Column(Modifier.weight(1f)) {
                    Text(event.title, fontWeight = FontWeight.Bold)
                    Text("${formatDate(event.start_time)} – ${formatDate(event.end_time)}")
                    if (event.location.isNotBlank()) Text(event.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.email_subject.isNotBlank()) {
                        Text("${text.fromMail}: ${event.email_subject}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!event.source_email_id.isNullOrBlank()) {
                TextButton(onClick = onOpenSourceEmail, modifier = Modifier.padding(start = CampusSpacing.Medium)) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(CampusSpacing.Small))
                    Text(text.viewSourceEmail)
                }
            }
        }
    }
}

@Composable
private fun EmptyMailState(folder: String, text: MailStrings) {
    val folderLabel = mailFolders(text).firstOrNull { it.first == folder }?.second ?: folder
    CampusEmptyState(text.title, text.noMessages.format(folderLabel), icon = Icons.Default.Email)
}

@Composable
private fun ErrorBlock(
    title: String,
    message: String?,
    retryLabel: String,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CampusErrorState(title, message ?: title, retryLabel, retry, modifier)
}

@Composable
private fun FeedbackText(message: String) {
    CampusStatusChip(message, CampusStatusTone.SUCCESS, Modifier.padding(horizontal = CampusSpacing.ExtraSmall))
}

private fun localizeMailFeedback(message: String, text: MailStrings): String = when (message) {
    "Waiting for Gmail authorization…" -> text.waitingAuthorization
    "Gmail connected" -> text.gmailConnectedMessage
    "Authorization not detected yet. Tap Check again." -> text.authorizationPending
    "Message updated" -> text.messageUpdated
    else -> message
}

private fun localizeCalendarFeedback(message: String, text: CalendarStrings): String = when (message) {
    "Event created" -> text.eventCreated
    "Event updated" -> text.eventUpdated
    "Event deleted" -> text.eventDeleted
    else -> {
        val scanned = Regex("Scanned (\\d+) emails \\((.+) mode\\)").matchEntire(message)
        val imported = Regex("Imported (\\d+) events; skipped (\\d+)").matchEntire(message)
        when {
            scanned != null -> text.scannedEmails.format(scanned.groupValues[1], scanned.groupValues[2])
            imported != null -> text.importedEvents.format(imported.groupValues[1], imported.groupValues[2])
            else -> message
        }
    }
}

private fun formatDate(value: String): String = runCatching {
    val parsed = runCatching { java.time.OffsetDateTime.parse(value) }.getOrElse {
        LocalDateTime.parse(value.substringBefore('[').substringBefore('Z'))
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime()
    }
    parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z"))
}.getOrElse { value.replace('T', ' ') }

@file:Suppress("TooGenericExceptionCaught", "MaxLineLength", "TooManyFunctions")

package com.campuslink.mobile.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.core.model.CalendarEvent
import com.campuslink.mobile.core.model.CalendarEventRequest
import com.campuslink.mobile.core.model.CalendarEventUpdate
import com.campuslink.mobile.core.model.ExtractedSchedule
import com.campuslink.mobile.core.model.ImportRequest
import com.campuslink.mobile.core.model.MailMessage
import com.campuslink.mobile.core.model.SendMailRequest
import com.campuslink.mobile.core.model.UpdateMailRequest
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.mail.MailDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class MailHomeUiState(
    val connected: Boolean? = null,
    val connectedEmail: String? = null,
    val authUrl: String? = null,
    val folder: String = "inbox",
    val query: String = "",
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val messages: List<MailMessage> = emptyList(),
    val page: Int = 0,
    val lastPage: Boolean = true,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val actionMessage: String? = null,
)

class MailHomeViewModel(private val repository: MailDataSource) : ViewModel() {
    private val mutableState = MutableStateFlow(MailHomeUiState())
    val state: StateFlow<MailHomeUiState> = mutableState.asStateFlow()
    private var requestJob: Job? = null
    private var oauthPollingJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null, actionMessage = null)
            try {
                val status = repository.oauthStatus()
                mutableState.value = mutableState.value.copy(
                    connected = status.connected,
                    connectedEmail = status.email,
                    authUrl = null,
                )
                if (status.connected) loadPage(reset = true) else {
                    mutableState.value = mutableState.value.copy(loading = false, messages = emptyList())
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = exception.toMailMessage())
            }
        }
    }

    fun requestAuthorization() {
        viewModelScope.launch {
            try {
                val response = repository.oauthUrl()
                mutableState.value = mutableState.value.copy(
                    connected = response.connected,
                    authUrl = response.auth_url,
                    error = null,
                )
                if (response.connected) refresh()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage())
            }
        }
    }

    fun clearAuthUrl() = mutableState.value.copy(authUrl = null).also { mutableState.value = it }

    fun disconnect() {
        viewModelScope.launch {
            try {
                repository.disconnectOAuth()
                mutableState.value = mutableState.value.copy(
                    connected = false,
                    connectedEmail = null,
                    messages = emptyList(),
                    page = 0,
                    lastPage = true,
                    actionMessage = "Gmail disconnected",
                    error = null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage())
            }
        }
    }

    fun pollOAuthStatus() {
        if (oauthPollingJob?.isActive == true) return
        oauthPollingJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(actionMessage = "Waiting for Gmail authorization…")
            repeat(OAUTH_POLL_ATTEMPTS) {
                delay(OAUTH_POLL_INTERVAL_MS)
                try {
                    val status = repository.oauthStatus()
                    if (status.connected) {
                        mutableState.value = mutableState.value.copy(
                            connected = true,
                            connectedEmail = status.email,
                            actionMessage = "Gmail connected",
                            error = null,
                        )
                        loadPage(reset = true)
                        return@launch
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // 浏览器授权期间网络短暂失败时继续轮询，用户仍可手动刷新。
                }
            }
            mutableState.value = mutableState.value.copy(actionMessage = "Authorization not detected yet. Tap Check again.")
        }
    }

    fun updateFolder(folder: String) {
        if (mutableState.value.folder == folder) return
        mutableState.value = mutableState.value.copy(folder = folder, actionMessage = null)
        search()
    }

    fun updateQuery(query: String) = mutableState.value.copy(query = query, error = null).also { mutableState.value = it }

    fun updateUnreadOnly(value: Boolean) {
        mutableState.value = mutableState.value.copy(unreadOnly = value)
        search()
    }

    fun updateStarredOnly(value: Boolean) {
        mutableState.value = mutableState.value.copy(starredOnly = value)
        search()
    }

    fun search() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch { loadPage(reset = true) }
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || current.loadingMore || current.lastPage || current.connected != true) return
        requestJob?.cancel()
        requestJob = viewModelScope.launch { loadPage(reset = false) }
    }

    fun toggleRead(message: MailMessage) = updateMessage(
        message,
        UpdateMailRequest(read = !message.read),
    )

    fun toggleStar(message: MailMessage) = updateMessage(
        message,
        UpdateMailRequest(starred = !message.starred),
    )

    fun archive(message: MailMessage) {
        viewModelScope.launch {
            runAction(message.id, "Archived") { repository.archiveMessage(message.id) }
        }
    }

    fun delete(message: MailMessage) {
        viewModelScope.launch {
            runAction(message.id, "Moved to trash") { repository.deleteMessage(message.id) }
        }
    }

    fun clearActionMessage() = mutableState.value.copy(actionMessage = null).also { mutableState.value = it }

    private suspend fun loadPage(reset: Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(
            loading = reset,
            loadingMore = !reset,
            error = null,
            actionMessage = null,
        )
        try {
            val page = repository.listMessages(
                folder = current.folder,
                query = current.query,
                unread = current.unreadOnly.takeIf { it },
                starred = current.starredOnly.takeIf { it },
                page = if (reset) 0 else current.page + 1,
            )
            val messages = if (reset) page.content else current.messages + page.content
            mutableState.value = mutableState.value.copy(
                messages = messages,
                page = page.page,
                lastPage = page.last,
                loading = false,
                loadingMore = false,
                error = null,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            val notConnected = exception.errorCode == "GMAIL_NOT_CONNECTED"
            mutableState.value = mutableState.value.copy(
                connected = if (notConnected) false else mutableState.value.connected,
                authUrl = exception.authUrl,
                loading = false,
                loadingMore = false,
                error = exception.toMailMessage(),
            )
        } catch (exception: Exception) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                loadingMore = false,
                error = exception.toMailMessage(),
            )
        }
    }

    private fun updateMessage(message: MailMessage, request: UpdateMailRequest) {
        viewModelScope.launch {
            try {
                val updated = repository.updateMessage(message.id, request)
                mutableState.value = mutableState.value.copy(
                    messages = mutableState.value.messages.map { if (it.id == updated.id) updated else it },
                    actionMessage = "Message updated",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage())
            }
        }
    }

    private suspend fun runAction(messageId: String, message: String, action: suspend () -> MailMessage) {
        try {
            action()
            mutableState.value = mutableState.value.copy(
                messages = mutableState.value.messages.filterNot { it.id == messageId },
                actionMessage = message,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            mutableState.value = mutableState.value.copy(error = exception.toMailMessage())
        }
    }

    private companion object {
        const val OAUTH_POLL_ATTEMPTS = 60
        const val OAUTH_POLL_INTERVAL_MS = 2_000L
    }
}

data class MailDetailsUiState(
    val loading: Boolean = true,
    val message: MailMessage? = null,
    val error: String? = null,
    val actionMessage: String? = null,
    val removed: Boolean = false,
)

class MailDetailsViewModel(
    private val messageId: String,
    private val repository: MailDataSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MailDetailsUiState())
    val state: StateFlow<MailDetailsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    fun toggleRead() {
        val message = mutableState.value.message ?: return
        updateMessage(UpdateMailRequest(read = !message.read))
    }

    fun toggleStar() {
        val message = mutableState.value.message ?: return
        updateMessage(UpdateMailRequest(starred = !message.starred))
    }

    private fun updateMessage(request: UpdateMailRequest) {
        val message = mutableState.value.message ?: return
        viewModelScope.launch {
            try {
                val updated = repository.updateMessage(message.id, request)
                mutableState.value = mutableState.value.copy(message = updated, actionMessage = "Message updated")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage(), actionMessage = null)
            }
        }
    }

    fun archive() = move { repository.archiveMessage(messageId) }
    fun delete() = move { repository.deleteMessage(messageId) }

    fun clearActionMessage() = mutableState.value.copy(actionMessage = null).also { mutableState.value = it }

    private fun load() {
        viewModelScope.launch {
            mutableState.value = MailDetailsUiState(loading = true)
            try {
                mutableState.value = MailDetailsUiState(loading = false, message = repository.getMessage(messageId))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = MailDetailsUiState(loading = false, error = exception.toMailMessage())
            }
        }
    }

    private fun move(action: suspend () -> MailMessage) {
        viewModelScope.launch {
            try {
                action()
                mutableState.value = mutableState.value.copy(removed = true, actionMessage = "Message updated")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage(), actionMessage = null)
            }
        }
    }
}

data class ComposeMailForm(
    val recipients: String = "",
    val subject: String = "",
    val body: String = "",
)

sealed interface ComposeMailUiState {
    data object Idle : ComposeMailUiState
    data object Sending : ComposeMailUiState
    data class Sent(val message: MailMessage) : ComposeMailUiState
    data class Error(val message: String) : ComposeMailUiState
}

class ComposeMailViewModel(private val repository: MailDataSource) : ViewModel() {
    private val mutableForm = MutableStateFlow(ComposeMailForm())
    val form: StateFlow<ComposeMailForm> = mutableForm.asStateFlow()
    private val mutableState = MutableStateFlow<ComposeMailUiState>(ComposeMailUiState.Idle)
    val state: StateFlow<ComposeMailUiState> = mutableState.asStateFlow()

    fun updateRecipients(value: String) = updateForm { copy(recipients = value) }
    fun updateSubject(value: String) = updateForm { copy(subject = value) }
    fun updateBody(value: String) = updateForm { copy(body = value) }

    fun send() {
        if (mutableState.value is ComposeMailUiState.Sending) return
        val current = mutableForm.value
        val recipients = current.recipients.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty)
        val error = when {
            recipients.isEmpty() -> "Enter at least one recipient."
            recipients.any { !it.contains('@') } -> "Check the recipient email addresses."
            current.subject.trim().isEmpty() -> "Subject is required."
            current.subject.trim().length > 160 -> "Subject must be 160 characters or fewer."
            current.body.trim().isEmpty() -> "Message body is required."
            current.body.length > 10000 -> "Message body must be 10000 characters or fewer."
            else -> null
        }
        if (error != null) {
            mutableState.value = ComposeMailUiState.Error(error)
            return
        }
        viewModelScope.launch {
            mutableState.value = ComposeMailUiState.Sending
            try {
                mutableState.value = ComposeMailUiState.Sent(
                    repository.sendMessage(SendMailRequest(recipients, current.subject.trim(), current.body)),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = ComposeMailUiState.Error(exception.toMailMessage())
            }
        }
    }

    fun clearFeedback() {
        if (mutableState.value !is ComposeMailUiState.Sending) mutableState.value = ComposeMailUiState.Idle
    }

    private fun updateForm(transform: ComposeMailForm.() -> ComposeMailForm) {
        mutableForm.value = mutableForm.value.transform()
        clearFeedback()
    }
}

data class CalendarForm(
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startTime: String = defaultCalendarTime(1),
    val endTime: String = defaultCalendarTime(2),
    val allDay: Boolean = false,
)

private const val DEFAULT_EXTRACTION_DAYS = 0

data class CalendarUiState(
    val loading: Boolean = true,
    val displayMonth: YearMonth = YearMonth.now(),
    val events: List<CalendarEvent> = emptyList(),
    val form: CalendarForm = CalendarForm(),
    val editorVisible: Boolean = false,
    val editingId: String? = null,
    val saving: Boolean = false,
    val extracting: Boolean = false,
    val extractionDays: Int = DEFAULT_EXTRACTION_DAYS,
    val extractionMode: String? = null,
    val proposals: List<ExtractedSchedule> = emptyList(),
    val selectedProposalKeys: Set<String> = emptySet(),
    val error: String? = null,
    val actionMessage: String? = null,
)

class CalendarViewModel(private val repository: MailDataSource) : ViewModel() {
    private val mutableState = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                val month = mutableState.value.displayMonth
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    events = repository.listCalendarEvents(
                        start = monthStart(month),
                        end = monthStart(month.plusMonths(1)),
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = exception.toMailMessage())
            }
        }
    }

    fun updateTitle(value: String) = updateForm { copy(title = value) }
    fun updateDescription(value: String) = updateForm { copy(description = value) }
    fun updateLocation(value: String) = updateForm { copy(location = value) }
    fun updateStartTime(value: String) = updateForm { copy(startTime = value) }
    fun updateEndTime(value: String) = updateForm { copy(endTime = value) }
    fun updateAllDay(value: Boolean) = updateForm { copy(allDay = value) }

    fun updateExtractionDays(days: Int) {
        if (days !in EXTRACTION_DAY_OPTIONS) return
        mutableState.value = mutableState.value.copy(extractionDays = days, error = null)
    }

    fun beginCreate(date: LocalDate = LocalDate.now()) {
        mutableState.value = mutableState.value.copy(
            form = calendarFormForDate(date),
            editorVisible = true,
            editingId = null,
            error = null,
        )
    }

    fun previousMonth() = changeMonth { minusMonths(1) }

    fun nextMonth() = changeMonth { plusMonths(1) }

    fun currentMonth() = changeMonth { YearMonth.now() }

    fun beginEdit(event: CalendarEvent) {
        mutableState.value = mutableState.value.copy(
            editorVisible = true,
            editingId = event.id,
            form = CalendarForm(event.title, event.description, event.location, event.start_time, event.end_time, event.all_day),
            error = null,
        )
    }

    fun cancelEdit() = mutableState.value.copy(
        editorVisible = false,
        editingId = null,
        form = CalendarForm(),
        error = null,
    ).also {
        mutableState.value = it
    }

    fun save() {
        val current = mutableState.value
        val form = current.form
        val error = validateCalendarForm(form)
        if (error != null) {
            mutableState.value = current.copy(error = error)
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(saving = true, error = null)
            try {
                val month = mutableState.value.displayMonth
                val updatedEvents = if (current.editingId == null) {
                    repository.createCalendarEvent(form.toRequest())
                    repository.listCalendarEvents(monthStart(month), monthStart(month.plusMonths(1)))
                } else {
                    repository.updateCalendarEvent(current.editingId, form.toUpdate())
                    repository.listCalendarEvents(monthStart(month), monthStart(month.plusMonths(1)))
                }
                mutableState.value = mutableState.value.copy(
                    saving = false,
                    events = updatedEvents,
                    editorVisible = false,
                    editingId = null,
                    form = CalendarForm(),
                    actionMessage = if (current.editingId == null) "Event created" else "Event updated",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(saving = false, error = exception.toMailMessage())
            }
        }
    }

    fun delete(event: CalendarEvent) {
        viewModelScope.launch {
            try {
                repository.deleteCalendarEvent(event.id)
                val current = mutableState.value
                val isEditingDeletedEvent = current.editingId == event.id
                mutableState.value = mutableState.value.copy(
                    events = current.events.filterNot { it.id == event.id },
                    // 编辑器与列表位于同一个页面。删除当前正在编辑的事件后，
                    // 必须关闭编辑器并清空表单，否则页面会继续显示已删除的事件。
                    editorVisible = if (isEditingDeletedEvent) false else current.editorVisible,
                    editingId = if (isEditingDeletedEvent) null else current.editingId,
                    form = if (isEditingDeletedEvent) CalendarForm() else current.form,
                    actionMessage = "Event deleted",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage())
            }
        }
    }

    fun extract() {
        val days = mutableState.value.extractionDays
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(extracting = true, error = null, proposals = emptyList())
            try {
                val result = repository.extractCalendarSchedules(days = days, maxResults = MAX_EXTRACTION_RESULTS)
                mutableState.value = mutableState.value.copy(
                    extracting = false,
                    proposals = result.events,
                    selectedProposalKeys = result.events.map { it.key }.toSet(),
                    extractionMode = result.mode,
                    actionMessage = "Scanned ${result.scanned} emails (${result.mode} mode)",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(extracting = false, error = exception.toMailMessage())
            }
        }
    }

    fun toggleProposal(key: String) {
        val selected = mutableState.value.selectedProposalKeys.toMutableSet()
        if (!selected.add(key)) selected.remove(key)
        mutableState.value = mutableState.value.copy(selectedProposalKeys = selected)
    }

    fun importSelected() {
        val current = mutableState.value
        val selected = current.proposals.filter { it.key in current.selectedProposalKeys }
        if (selected.isEmpty()) {
            mutableState.value = current.copy(error = "Select at least one proposed event.")
            return
        }
        viewModelScope.launch {
            try {
                val month = mutableState.value.displayMonth
                val result = repository.importCalendarSchedules(ImportRequest(selected))
                mutableState.value = mutableState.value.copy(
                    proposals = emptyList(),
                    selectedProposalKeys = emptySet(),
                    events = repository.listCalendarEvents(monthStart(month), monthStart(month.plusMonths(1))),
                    actionMessage = "Imported ${result.imported} events; skipped ${result.skipped}",
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(error = exception.toMailMessage())
            }
        }
    }

    fun clearFeedback() = mutableState.value.copy(error = null, actionMessage = null).also { mutableState.value = it }

    private fun updateForm(transform: CalendarForm.() -> CalendarForm) {
        mutableState.value = mutableState.value.copy(form = mutableState.value.form.transform(), error = null)
    }

    private fun changeMonth(transform: YearMonth.() -> YearMonth) {
        val next = transform(mutableState.value.displayMonth)
        mutableState.value = mutableState.value.copy(displayMonth = next, error = null)
        load()
    }

    private companion object {
        val EXTRACTION_DAY_OPTIONS = setOf(0, 1, 2, 3, 7)
        const val MAX_EXTRACTION_RESULTS = 50
    }
}

private fun CalendarForm.toRequest() = CalendarEventRequest(title.trim(), description.trim(), location.trim(), startTime.trim(), endTime.trim(), allDay)

private fun CalendarForm.toUpdate() = CalendarEventUpdate(title.trim(), description.trim(), location.trim(), startTime.trim(), endTime.trim(), allDay)

private fun validateCalendarForm(form: CalendarForm): String? = when {
    form.title.trim().isEmpty() -> "Title is required."
    form.title.trim().length > 200 -> "Title must be 200 characters or fewer."
    runCatching { LocalDateTime.parse(form.startTime.trim()) }.isFailure -> "Start time must use ISO format, for example 2026-08-16T14:00:00."
    runCatching { LocalDateTime.parse(form.endTime.trim()) }.isFailure -> "End time must use ISO format, for example 2026-08-16T16:00:00."
    else -> {
        val start = runCatching { LocalDateTime.parse(form.startTime.trim()) }.getOrNull()
        val end = runCatching { LocalDateTime.parse(form.endTime.trim()) }.getOrNull()
        if (start != null && end != null && !end.isAfter(start)) "End time must be after start time." else null
    }
}

private fun defaultCalendarTime(hoursFromNow: Long): String = LocalDateTime.now()
    .plusHours(hoursFromNow)
    .withMinute(0)
    .withSecond(0)
    .withNano(0)
    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

private fun calendarFormForDate(date: LocalDate): CalendarForm = CalendarForm(
    startTime = date.atTime(9, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    endTime = date.atTime(10, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
)

private fun monthStart(month: YearMonth): String = month.atDay(1)
    .atStartOfDay()
    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

private fun Exception.toMailMessage(): String = when (this) {
    is ApiException -> when {
        errorCode == "GMAIL_NOT_CONNECTED" -> "Gmail is not connected. Authorize your account first."
        statusCode == 401 -> "Your session has expired. Please sign in again."
        statusCode == 404 -> "The requested mail or calendar item was not found."
        statusCode == 422 -> message.ifBlank { "Please check the entered values." }
        statusCode in 400..499 -> message.ifBlank { "The request could not be completed." }
        else -> "Mail service is temporarily unavailable."
    }
    is SocketTimeoutException -> "The request timed out. Please try again."
    is IOException -> "Network unavailable. Check your connection and try again."
    is SerializationException -> "The Mail service response could not be read."
    else -> message?.ifBlank { null } ?: "Something went wrong. Please try again."
}

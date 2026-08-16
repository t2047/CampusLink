package com.campuslink.mobile.ui.lostfound

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.core.model.CreateLostFoundReportRequest
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.LostFoundClaim
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.LostFoundSearchFilters
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.core.model.UploadImage
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.lostfound.LostFoundDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate

data class LostFoundBrowseForm(
    val reportType: ReportType = ReportType.FOUND,
    val keyword: String = "",
    val category: ItemCategory? = null,
    val colour: String = "",
    val location: String = "",
    val dateFrom: String = "",
    val dateTo: String = "",
)

sealed interface LostFoundBrowseUiState {
    data object Loading : LostFoundBrowseUiState
    data object Empty : LostFoundBrowseUiState
    data class Success(
        val reports: List<LostFoundReport>,
        val lastPage: Boolean,
        val loadingMore: Boolean = false,
    ) : LostFoundBrowseUiState
    data class Error(val message: String) : LostFoundBrowseUiState
}

class LostFoundBrowseViewModel(private val repository: LostFoundDataSource) : ViewModel() {
    private val mutableForm = MutableStateFlow(LostFoundBrowseForm())
    val form: StateFlow<LostFoundBrowseForm> = mutableForm.asStateFlow()
    private val mutableState = MutableStateFlow<LostFoundBrowseUiState>(LostFoundBrowseUiState.Loading)
    val state: StateFlow<LostFoundBrowseUiState> = mutableState.asStateFlow()
    private var requestJob: Job? = null
    private var currentPage = 0

    init {
        search()
    }

    fun updateReportType(value: ReportType) {
        if (mutableForm.value.reportType == value) return
        updateForm { copy(reportType = value) }
        search()
    }
    fun updateKeyword(value: String) = updateForm { copy(keyword = value) }
    fun updateCategory(value: ItemCategory?) = updateForm { copy(category = value) }
    fun updateColour(value: String) = updateForm { copy(colour = value) }
    fun updateLocation(value: String) = updateForm { copy(location = value) }
    fun updateDateFrom(value: String) = updateForm { copy(dateFrom = value) }
    fun updateDateTo(value: String) = updateForm { copy(dateTo = value) }

    fun reset() {
        mutableForm.value = LostFoundBrowseForm()
        search()
    }

    fun retry() = search()

    fun search() {
        if (!datesAreValid()) return
        currentPage = 0
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            mutableState.value = LostFoundBrowseUiState.Loading
            loadPage(append = false)
        }
    }

    fun loadMore() {
        val current = mutableState.value as? LostFoundBrowseUiState.Success ?: return
        if (current.lastPage || current.loadingMore) return
        mutableState.value = current.copy(loadingMore = true)
        requestJob = viewModelScope.launch { loadPage(append = true) }
    }

    private suspend fun loadPage(append: Boolean) {
        when (val result = lostFoundRequest { repository.searchReports(mutableForm.value.toFilters(currentPage)) }) {
            is LostFoundRequestResult.Success -> {
                val page = result.value
                val previous = (mutableState.value as? LostFoundBrowseUiState.Success)?.reports.orEmpty()
                val reports = if (append) previous + page.content else page.content
                currentPage = page.page + 1
                mutableState.value = if (reports.isEmpty()) {
                    LostFoundBrowseUiState.Empty
                } else {
                    LostFoundBrowseUiState.Success(reports, page.last)
                }
            }
            is LostFoundRequestResult.Failure -> {
                mutableState.value = LostFoundBrowseUiState.Error(result.exception.toLostFoundMessage())
            }
        }
    }

    private fun datesAreValid(): Boolean {
        val values = listOf(mutableForm.value.dateFrom, mutableForm.value.dateTo).filter(String::isNotBlank)
        if (values.any { runCatching { LocalDate.parse(it) }.isFailure }) {
            mutableState.value = LostFoundBrowseUiState.Error("Dates must use YYYY-MM-DD.")
            return false
        }
        return true
    }

    private fun updateForm(transform: LostFoundBrowseForm.() -> LostFoundBrowseForm) {
        mutableForm.value = mutableForm.value.transform()
    }
}

sealed interface LostFoundDetailsUiState {
    data object Loading : LostFoundDetailsUiState
    data class Success(val report: LostFoundReport) : LostFoundDetailsUiState
    data class Error(val message: String, val notFound: Boolean = false) : LostFoundDetailsUiState
}

sealed interface ClaimSubmissionUiState {
    data object Idle : ClaimSubmissionUiState
    data object Submitting : ClaimSubmissionUiState
    data class Success(val claim: LostFoundClaim) : ClaimSubmissionUiState
    data class Error(val message: String) : ClaimSubmissionUiState
}

class LostFoundDetailsViewModel(
    private val reportId: Long,
    private val repository: LostFoundDataSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow<LostFoundDetailsUiState>(LostFoundDetailsUiState.Loading)
    val state: StateFlow<LostFoundDetailsUiState> = mutableState.asStateFlow()
    private val mutableClaimState = MutableStateFlow<ClaimSubmissionUiState>(ClaimSubmissionUiState.Idle)
    val claimState: StateFlow<ClaimSubmissionUiState> = mutableClaimState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    fun submitClaim(proof: String) {
        if (mutableClaimState.value is ClaimSubmissionUiState.Submitting) return
        val normalized = proof.trim()
        if (normalized.length !in 10..1000) {
            mutableClaimState.value = ClaimSubmissionUiState.Error("Proof must be between 10 and 1000 characters.")
            return
        }
        viewModelScope.launch {
            mutableClaimState.value = ClaimSubmissionUiState.Submitting
            when (val result = lostFoundRequest { repository.submitClaim(reportId, normalized) }) {
                is LostFoundRequestResult.Success -> {
                    mutableClaimState.value = ClaimSubmissionUiState.Success(result.value)
                }
                is LostFoundRequestResult.Failure -> {
                    mutableClaimState.value = ClaimSubmissionUiState.Error(result.exception.toLostFoundMessage())
                }
            }
        }
    }

    fun clearClaimFeedback() {
        if (mutableClaimState.value !is ClaimSubmissionUiState.Submitting) {
            mutableClaimState.value = ClaimSubmissionUiState.Idle
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableState.value = LostFoundDetailsUiState.Loading
            when (val result = lostFoundRequest { repository.getReport(reportId) }) {
                is LostFoundRequestResult.Success -> {
                    mutableState.value = LostFoundDetailsUiState.Success(result.value)
                }
                is LostFoundRequestResult.Failure -> {
                    mutableState.value = LostFoundDetailsUiState.Error(
                        result.exception.toLostFoundMessage(),
                        notFound = result.exception is ApiException && result.exception.statusCode == 404,
                    )
                }
            }
        }
    }
}

data class CreateReportForm(
    val itemName: String = "",
    val category: ItemCategory = ItemCategory.OTHER,
    val description: String = "",
    val colour: String = "",
    val location: String = "",
    val eventDate: String = LocalDate.now().toString(),
    val timeDescription: String = "",
)

sealed interface CreateReportUiState {
    data object Idle : CreateReportUiState
    data object Submitting : CreateReportUiState
    data class Success(val report: LostFoundReport) : CreateReportUiState
    data class Error(val message: String) : CreateReportUiState
}

class CreateLostFoundReportViewModel(
    private val reportType: ReportType,
    private val repository: LostFoundDataSource,
) : ViewModel() {
    private val mutableForm = MutableStateFlow(CreateReportForm())
    val form: StateFlow<CreateReportForm> = mutableForm.asStateFlow()
    private val mutableState = MutableStateFlow<CreateReportUiState>(CreateReportUiState.Idle)
    val state: StateFlow<CreateReportUiState> = mutableState.asStateFlow()

    fun updateItemName(value: String) = updateForm { copy(itemName = value) }
    fun updateCategory(value: ItemCategory) = updateForm { copy(category = value) }
    fun updateDescription(value: String) = updateForm { copy(description = value) }
    fun updateColour(value: String) = updateForm { copy(colour = value) }
    fun updateLocation(value: String) = updateForm { copy(location = value) }
    fun updateEventDate(value: String) = updateForm { copy(eventDate = value) }
    fun updateTimeDescription(value: String) = updateForm { copy(timeDescription = value) }

    fun submit(images: List<UploadImage>) {
        if (mutableState.value is CreateReportUiState.Submitting) return
        val request = validateAndCreateRequest() ?: return
        if (images.size > 5) {
            mutableState.value = CreateReportUiState.Error("You can upload at most 5 images.")
            return
        }
        if (images.any { it.bytes.size > MAX_IMAGE_BYTES }) {
            mutableState.value = CreateReportUiState.Error("Each image must be 10 MB or smaller.")
            return
        }
        viewModelScope.launch {
            mutableState.value = CreateReportUiState.Submitting
            when (val result = lostFoundRequest { repository.createReport(request, images) }) {
                is LostFoundRequestResult.Success -> mutableState.value = CreateReportUiState.Success(result.value)
                is LostFoundRequestResult.Failure -> {
                    mutableState.value = CreateReportUiState.Error(result.exception.toLostFoundMessage())
                }
            }
        }
    }

    fun clearFeedback() {
        if (mutableState.value !is CreateReportUiState.Submitting) mutableState.value = CreateReportUiState.Idle
    }

    private fun validateAndCreateRequest(): CreateLostFoundReportRequest? {
        val current = mutableForm.value
        val date = runCatching { LocalDate.parse(current.eventDate) }.getOrNull()
        val message = when {
            current.itemName.trim().length !in 2..100 -> "Item name must be between 2 and 100 characters."
            current.description.trim().length !in 10..2000 -> "Description must be between 10 and 2000 characters."
            current.location.isBlank() || current.location.length > 200 -> "Location is required."
            date == null -> "Date must use YYYY-MM-DD."
            date.isAfter(LocalDate.now()) -> "Date cannot be in the future."
            current.colour.length > 50 -> "Colour must be 50 characters or fewer."
            current.timeDescription.length > 100 -> "Time description must be 100 characters or fewer."
            else -> null
        }
        if (message != null) {
            mutableState.value = CreateReportUiState.Error(message)
            return null
        }
        return CreateLostFoundReportRequest(
            reportType = reportType,
            itemName = current.itemName.trim(),
            category = current.category,
            description = current.description.trim(),
            colour = current.colour.trim().ifEmpty { null },
            location = current.location.trim(),
            eventDate = current.eventDate,
            timeDescription = current.timeDescription.trim().ifEmpty { null },
        )
    }

    private fun updateForm(transform: CreateReportForm.() -> CreateReportForm) {
        mutableForm.value = mutableForm.value.transform()
        clearFeedback()
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    }
}

enum class ClaimsMode {
    MINE,
    RECEIVED,
}

sealed interface ClaimsUiState {
    data object Loading : ClaimsUiState
    data object Empty : ClaimsUiState
    data class Success(val claims: List<LostFoundClaim>) : ClaimsUiState
    data class Error(val message: String) : ClaimsUiState
}

sealed interface ClaimDecisionUiState {
    data object Idle : ClaimDecisionUiState
    data class Submitting(val claimId: Long) : ClaimDecisionUiState
    data class Success(val message: String) : ClaimDecisionUiState
    data class Error(val message: String) : ClaimDecisionUiState
}

class LostFoundClaimsViewModel(private val repository: LostFoundDataSource) : ViewModel() {
    private val mutableMode = MutableStateFlow(ClaimsMode.MINE)
    val mode: StateFlow<ClaimsMode> = mutableMode.asStateFlow()
    private val mutableState = MutableStateFlow<ClaimsUiState>(ClaimsUiState.Loading)
    val state: StateFlow<ClaimsUiState> = mutableState.asStateFlow()
    private val mutableDecisionState = MutableStateFlow<ClaimDecisionUiState>(ClaimDecisionUiState.Idle)
    val decisionState: StateFlow<ClaimDecisionUiState> = mutableDecisionState.asStateFlow()

    init {
        load()
    }

    fun changeMode(mode: ClaimsMode) {
        if (mutableMode.value == mode) return
        mutableMode.value = mode
        load()
    }

    fun retry() = load()

    fun decide(claimId: Long, approve: Boolean, note: String) {
        if (mutableDecisionState.value is ClaimDecisionUiState.Submitting) return
        if (note.length > 500) {
            mutableDecisionState.value = ClaimDecisionUiState.Error("Decision note must be 500 characters or fewer.")
            return
        }
        viewModelScope.launch {
            mutableDecisionState.value = ClaimDecisionUiState.Submitting(claimId)
            when (val result = lostFoundRequest { repository.decideClaim(claimId, approve, note.trim()) }) {
                is LostFoundRequestResult.Success -> {
                    mutableDecisionState.value = ClaimDecisionUiState.Success(
                        "Claim ${result.value.status.name.lowercase()}.",
                    )
                    load()
                }
                is LostFoundRequestResult.Failure -> {
                    mutableDecisionState.value = ClaimDecisionUiState.Error(result.exception.toLostFoundMessage())
                }
            }
        }
    }

    fun clearDecisionFeedback() {
        if (mutableDecisionState.value !is ClaimDecisionUiState.Submitting) {
            mutableDecisionState.value = ClaimDecisionUiState.Idle
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableState.value = ClaimsUiState.Loading
            val result = lostFoundRequest {
                if (mutableMode.value == ClaimsMode.MINE) repository.getMyClaims()
                else repository.getReceivedClaims()
            }
            when (result) {
                is LostFoundRequestResult.Success -> {
                    val claims = result.value.sortedByDescending(LostFoundClaim::createdAt)
                    mutableState.value = if (claims.isEmpty()) ClaimsUiState.Empty else ClaimsUiState.Success(claims)
                }
                is LostFoundRequestResult.Failure -> {
                    mutableState.value = ClaimsUiState.Error(result.exception.toLostFoundMessage())
                }
            }
        }
    }
}

private fun LostFoundBrowseForm.toFilters(page: Int) = LostFoundSearchFilters(
    reportType = reportType,
    keyword = keyword,
    category = category,
    colour = colour,
    location = location,
    dateFrom = dateFrom,
    dateTo = dateTo,
    status = ReportStatus.OPEN,
    page = page,
)

private fun Exception.toLostFoundMessage(): String = when (this) {
    is ApiException -> when (statusCode) {
        401 -> "Your session has expired. Please sign in again."
        404 -> "The requested Lost & Found record was not found."
        409 -> message.ifBlank { "This record has changed. Refresh and try again." }
        413 -> "The selected image is too large."
        415 -> "Only JPEG, PNG, and WebP images are supported."
        422 -> message.ifBlank { "Check the information and try again." }
        in 400..499 -> message.ifBlank { "The request could not be completed." }
        else -> "Lost & Found service is temporarily unavailable."
    }
    is SocketTimeoutException -> "The request timed out. Please try again."
    is IOException -> "Network unavailable. Check your connection and try again."
    is SerializationException -> "The Lost & Found response could not be read."
    else -> "Something went wrong. Please try again."
}

private sealed interface LostFoundRequestResult<out T> {
    data class Success<T>(val value: T) : LostFoundRequestResult<T>
    data class Failure(val exception: Exception) : LostFoundRequestResult<Nothing>
}

private suspend fun <T> lostFoundRequest(block: suspend () -> T): LostFoundRequestResult<T> = try {
    LostFoundRequestResult.Success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: ApiException) {
    LostFoundRequestResult.Failure(exception)
} catch (exception: SocketTimeoutException) {
    LostFoundRequestResult.Failure(exception)
} catch (exception: IOException) {
    LostFoundRequestResult.Failure(exception)
} catch (exception: SerializationException) {
    LostFoundRequestResult.Failure(exception)
}

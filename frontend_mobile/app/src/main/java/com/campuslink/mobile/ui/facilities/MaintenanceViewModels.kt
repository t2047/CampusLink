package com.campuslink.mobile.ui.facilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.core.model.MaintenancePriority
import com.campuslink.mobile.core.model.MaintenanceResponse
import com.campuslink.mobile.core.model.MaintenanceStatus
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.model.SubmitMaintenanceRequest
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.facilities.FacilitiesDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

data class MaintenanceForm(
    val selectedSpaceId: Long? = null,
    val facilityType: String = "",
    val description: String = "",
    val priority: MaintenancePriority = MaintenancePriority.MEDIUM,
)

sealed interface MaintenanceSpacesUiState {
    data object Loading : MaintenanceSpacesUiState
    data class Success(val spaces: List<Space>) : MaintenanceSpacesUiState
    data class Error(val message: String) : MaintenanceSpacesUiState
}

sealed interface SubmitMaintenanceUiState {
    data object Idle : SubmitMaintenanceUiState
    data class Confirming(val request: SubmitMaintenanceRequest, val space: Space) : SubmitMaintenanceUiState
    data object Submitting : SubmitMaintenanceUiState
    data class Success(val maintenance: MaintenanceResponse) : SubmitMaintenanceUiState
    data class Error(
        val message: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : SubmitMaintenanceUiState
}

class SubmitMaintenanceViewModel(
    private val repository: FacilitiesDataSource,
    private val preselectedSpaceId: Long? = null,
) : ViewModel() {
    private val mutableForm = MutableStateFlow(MaintenanceForm(selectedSpaceId = preselectedSpaceId))
    val form: StateFlow<MaintenanceForm> = mutableForm.asStateFlow()
    private val mutableSpacesState = MutableStateFlow<MaintenanceSpacesUiState>(MaintenanceSpacesUiState.Loading)
    val spacesState: StateFlow<MaintenanceSpacesUiState> = mutableSpacesState.asStateFlow()
    private val mutableSubmitState = MutableStateFlow<SubmitMaintenanceUiState>(SubmitMaintenanceUiState.Idle)
    val submitState: StateFlow<SubmitMaintenanceUiState> = mutableSubmitState.asStateFlow()
    private var submitJob: Job? = null

    init {
        loadSpaces()
    }

    fun retrySpaces() = loadSpaces()

    fun selectSpace(spaceId: Long) = updateForm { copy(selectedSpaceId = spaceId) }

    fun updateFacilityType(value: String) = updateForm { copy(facilityType = value.take(FACILITY_TYPE_MAX)) }

    fun updateDescription(value: String) = updateForm { copy(description = value.take(DESCRIPTION_MAX)) }

    fun updatePriority(value: MaintenancePriority) = updateForm { copy(priority = value) }

    fun requestSubmit() {
        if (mutableSubmitState.value is SubmitMaintenanceUiState.Submitting) return
        val current = mutableForm.value
        val errors = validate(current)
        if (errors.isNotEmpty()) {
            mutableSubmitState.value = SubmitMaintenanceUiState.Error("Check the highlighted fields.", errors)
            return
        }
        val spaces = (mutableSpacesState.value as? MaintenanceSpacesUiState.Success)?.spaces.orEmpty()
        val space = spaces.firstOrNull { it.spaceId == current.selectedSpaceId }
        if (space == null) {
            mutableSubmitState.value = SubmitMaintenanceUiState.Error(
                "Choose an available campus space.",
                mapOf("spaceId" to "Space is required."),
            )
            return
        }
        mutableSubmitState.value = SubmitMaintenanceUiState.Confirming(current.toRequest(), space)
    }

    fun dismissConfirmation() {
        if (mutableSubmitState.value is SubmitMaintenanceUiState.Confirming) {
            mutableSubmitState.value = SubmitMaintenanceUiState.Idle
        }
    }

    fun confirmSubmit() {
        val confirming = mutableSubmitState.value as? SubmitMaintenanceUiState.Confirming ?: return
        if (submitJob?.isActive == true) return
        mutableSubmitState.value = SubmitMaintenanceUiState.Submitting
        submitJob = viewModelScope.launch {
            try {
                mutableSubmitState.value = SubmitMaintenanceUiState.Success(
                    repository.submitMaintenance(confirming.request),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableSubmitState.value = SubmitMaintenanceUiState.Error(
                    exception.toMaintenanceMessage(),
                    exception.validationErrors,
                )
            } catch (exception: SocketTimeoutException) {
                mutableSubmitState.value = SubmitMaintenanceUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: IOException) {
                mutableSubmitState.value = SubmitMaintenanceUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: SerializationException) {
                mutableSubmitState.value = SubmitMaintenanceUiState.Error(exception.toMaintenanceMessage())
            }
        }
    }

    fun clearFeedback() {
        if (mutableSubmitState.value is SubmitMaintenanceUiState.Error) {
            mutableSubmitState.value = SubmitMaintenanceUiState.Idle
        }
    }

    private fun loadSpaces() {
        viewModelScope.launch {
            mutableSpacesState.value = MaintenanceSpacesUiState.Loading
            try {
                val spaces = repository.searchSpaces(SpaceSearchFilters())
                mutableSpacesState.value = MaintenanceSpacesUiState.Success(spaces)
                val selected = mutableForm.value.selectedSpaceId
                if (selected != null && spaces.none { it.spaceId == selected }) {
                    mutableForm.value = mutableForm.value.copy(selectedSpaceId = null)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableSpacesState.value = MaintenanceSpacesUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: SocketTimeoutException) {
                mutableSpacesState.value = MaintenanceSpacesUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: IOException) {
                mutableSpacesState.value = MaintenanceSpacesUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: SerializationException) {
                mutableSpacesState.value = MaintenanceSpacesUiState.Error(exception.toMaintenanceMessage())
            }
        }
    }

    private fun updateForm(transform: MaintenanceForm.() -> MaintenanceForm) {
        mutableForm.value = mutableForm.value.transform()
        if (mutableSubmitState.value is SubmitMaintenanceUiState.Error) {
            mutableSubmitState.value = SubmitMaintenanceUiState.Idle
        }
    }

    private fun validate(form: MaintenanceForm): Map<String, String> = buildMap {
        if (form.selectedSpaceId == null) put("spaceId", "Space is required.")
        if (form.facilityType.isBlank()) put("facilityType", "Facility type is required.")
        if (form.facilityType.length > FACILITY_TYPE_MAX) {
            put("facilityType", "Facility type must be $FACILITY_TYPE_MAX characters or fewer.")
        }
        if (form.description.isBlank()) put("description", "Description is required.")
        if (form.description.length > DESCRIPTION_MAX) {
            put("description", "Description must be $DESCRIPTION_MAX characters or fewer.")
        }
    }

    private fun MaintenanceForm.toRequest() = SubmitMaintenanceRequest(
        spaceId = requireNotNull(selectedSpaceId),
        facilityType = facilityType.trim(),
        description = description.trim(),
        priority = priority,
    )

    companion object {
        const val FACILITY_TYPE_MAX = 255
        const val DESCRIPTION_MAX = 2000
    }
}

sealed interface MyMaintenanceUiState {
    data object Loading : MyMaintenanceUiState
    data object Empty : MyMaintenanceUiState
    data class Success(val requests: List<MaintenanceResponse>) : MyMaintenanceUiState
    data class Error(val message: String) : MyMaintenanceUiState
}

class MyMaintenanceViewModel(private val repository: FacilitiesDataSource) : ViewModel() {
    private val mutableState = MutableStateFlow<MyMaintenanceUiState>(MyMaintenanceUiState.Loading)
    val state: StateFlow<MyMaintenanceUiState> = mutableState.asStateFlow()
    private var hasBeenPresented = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = MyMaintenanceUiState.Loading
            try {
                val requests = repository.listMaintenanceRequests().sortedForDisplay()
                mutableState.value = if (requests.isEmpty()) MyMaintenanceUiState.Empty
                else MyMaintenanceUiState.Success(requests)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableState.value = MyMaintenanceUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: SocketTimeoutException) {
                mutableState.value = MyMaintenanceUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: IOException) {
                mutableState.value = MyMaintenanceUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: SerializationException) {
                mutableState.value = MyMaintenanceUiState.Error(exception.toMaintenanceMessage())
            }
        }
    }

    fun onScreenVisible() {
        if (hasBeenPresented) refresh() else hasBeenPresented = true
    }
}

sealed interface MaintenanceDetailsUiState {
    data object Loading : MaintenanceDetailsUiState
    data class Success(val maintenance: MaintenanceResponse) : MaintenanceDetailsUiState
    data class Error(val message: String, val notFound: Boolean = false) : MaintenanceDetailsUiState
}

class MaintenanceDetailsViewModel(
    private val ticketId: Long,
    private val repository: FacilitiesDataSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow<MaintenanceDetailsUiState>(MaintenanceDetailsUiState.Loading)
    val state: StateFlow<MaintenanceDetailsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            mutableState.value = MaintenanceDetailsUiState.Loading
            try {
                mutableState.value = MaintenanceDetailsUiState.Success(repository.getMaintenanceDetails(ticketId))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableState.value = MaintenanceDetailsUiState.Error(
                    exception.toMaintenanceMessage(),
                    notFound = exception.statusCode == 404,
                )
            } catch (exception: SocketTimeoutException) {
                mutableState.value = MaintenanceDetailsUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: IOException) {
                mutableState.value = MaintenanceDetailsUiState.Error(exception.toMaintenanceMessage())
            } catch (exception: SerializationException) {
                mutableState.value = MaintenanceDetailsUiState.Error(exception.toMaintenanceMessage())
            }
        }
    }
}

internal fun List<MaintenanceResponse>.sortedForDisplay(): List<MaintenanceResponse> = sortedWith(
    compareBy<MaintenanceResponse> { it.status.displayBucket() }
        .thenByDescending { parseDateTime(it.updatedAt) ?: parseDateTime(it.createdAt) }
        .thenByDescending { it.ticketId },
)

private fun MaintenanceStatus.displayBucket(): Int = when (this) {
    MaintenanceStatus.SUBMITTED, MaintenanceStatus.IN_PROGRESS -> 0
    MaintenanceStatus.RESOLVED, MaintenanceStatus.CANCELLED -> 1
}

internal fun Exception.toMaintenanceMessage(): String = when (this) {
    is ApiException -> when (statusCode) {
        400 -> message.ifBlank { "Check the maintenance request and try again." }
        401 -> "Your session has expired. Please sign in again."
        404 -> when (errorCode) {
            "SPACE_NOT_FOUND" -> "The selected space is no longer available. Choose another space."
            else -> "Maintenance request not found."
        }
        in 500..599 -> "Facilities service is temporarily unavailable."
        else -> "The maintenance request could not be completed."
    }
    is SocketTimeoutException -> "The request timed out. Please try again."
    is IOException -> "Network unavailable. Check your connection and try again."
    is SerializationException -> "The maintenance response could not be read."
    else -> "Something went wrong. Please try again."
}

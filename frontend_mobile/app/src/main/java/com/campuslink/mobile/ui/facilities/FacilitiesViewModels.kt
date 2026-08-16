package com.campuslink.mobile.ui.facilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class SpaceSearchForm(
    val query: String = "",
    val building: String = "",
    val spaceType: String = "",
    val minimumCapacity: String = "",
    val equipment: String = "",
)

sealed interface SpaceSearchUiState {
    data object Loading : SpaceSearchUiState
    data object Empty : SpaceSearchUiState
    data class Success(val spaces: List<Space>) : SpaceSearchUiState
    data class Error(val message: String) : SpaceSearchUiState
}

class SpaceSearchViewModel(private val repository: FacilitiesDataSource) : ViewModel() {
    private val mutableForm = MutableStateFlow(SpaceSearchForm())
    val form: StateFlow<SpaceSearchForm> = mutableForm.asStateFlow()
    private val mutableState = MutableStateFlow<SpaceSearchUiState>(SpaceSearchUiState.Loading)
    val state: StateFlow<SpaceSearchUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null

    init {
        search()
    }

    fun updateQuery(value: String) = updateForm { copy(query = value) }
    fun updateBuilding(value: String) = updateForm { copy(building = value) }
    fun updateSpaceType(value: String) = updateForm { copy(spaceType = value) }
    fun updateMinimumCapacity(value: String) = updateForm { copy(minimumCapacity = value.filter(Char::isDigit)) }
    fun updateEquipment(value: String) = updateForm { copy(equipment = value) }

    fun reset() {
        mutableForm.value = SpaceSearchForm()
        search()
    }

    fun retry() = search()

    fun search() {
        val current = mutableForm.value
        val minimumCapacity = current.minimumCapacity.takeIf(String::isNotBlank)?.toIntOrNull()
        if (current.minimumCapacity.isNotBlank() && minimumCapacity == null) {
            mutableState.value = SpaceSearchUiState.Error("Minimum capacity must be a whole number.")
            return
        }
        val filters = SpaceSearchFilters(
            query = current.query,
            building = current.building,
            spaceType = current.spaceType,
            minimumCapacity = minimumCapacity,
            equipment = current.equipment.split(',').map(String::trim).filter(String::isNotEmpty),
        )
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = SpaceSearchUiState.Loading
            try {
                val spaces = repository.searchSpaces(filters)
                mutableState.value = if (spaces.isEmpty()) SpaceSearchUiState.Empty
                else SpaceSearchUiState.Success(spaces)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableState.value = SpaceSearchUiState.Error(exception.toUserMessage())
            } catch (exception: SocketTimeoutException) {
                mutableState.value = SpaceSearchUiState.Error(exception.toUserMessage())
            } catch (exception: IOException) {
                mutableState.value = SpaceSearchUiState.Error(exception.toUserMessage())
            } catch (exception: SerializationException) {
                mutableState.value = SpaceSearchUiState.Error(exception.toUserMessage())
            }
        }
    }

    private fun updateForm(transform: SpaceSearchForm.() -> SpaceSearchForm) {
        mutableForm.value = mutableForm.value.transform()
    }
}

sealed interface SpaceDetailsUiState {
    data object Loading : SpaceDetailsUiState
    data class Success(val space: Space) : SpaceDetailsUiState
    data class Error(val message: String, val notFound: Boolean = false) : SpaceDetailsUiState
}

data class AvailabilitySelection(
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
)

sealed interface AvailabilityUiState {
    data object Idle : AvailabilityUiState
    data object Checking : AvailabilityUiState
    data class Available(val response: AvailabilityResponse) : AvailabilityUiState
    data class Unavailable(val response: AvailabilityResponse) : AvailabilityUiState
    data class Error(val message: String) : AvailabilityUiState
}

sealed interface BookingCreationUiState {
    data object Idle : BookingCreationUiState
    data class Confirming(val request: CreateBookingRequest) : BookingCreationUiState
    data class Submitting(val request: CreateBookingRequest) : BookingCreationUiState
    data class Success(val booking: BookingResponse) : BookingCreationUiState
    data class Error(val message: String, val conflict: Boolean = false) : BookingCreationUiState
}

class SpaceDetailsViewModel(
    private val spaceId: Long,
    private val repository: FacilitiesDataSource,
) : ViewModel() {
    private val mutableDetailsState = MutableStateFlow<SpaceDetailsUiState>(SpaceDetailsUiState.Loading)
    val detailsState: StateFlow<SpaceDetailsUiState> = mutableDetailsState.asStateFlow()
    private val mutableSelection = MutableStateFlow(AvailabilitySelection())
    val selection: StateFlow<AvailabilitySelection> = mutableSelection.asStateFlow()
    private val mutableAvailabilityState = MutableStateFlow<AvailabilityUiState>(AvailabilityUiState.Idle)
    val availabilityState: StateFlow<AvailabilityUiState> = mutableAvailabilityState.asStateFlow()
    private val mutableBookingState = MutableStateFlow<BookingCreationUiState>(BookingCreationUiState.Idle)
    val bookingState: StateFlow<BookingCreationUiState> = mutableBookingState.asStateFlow()
    private var availabilityJob: Job? = null
    private var availabilityGeneration = 0L

    init {
        loadSpace()
    }

    fun retry() = loadSpace()

    fun updateDate(value: LocalDate) = updateSelection { copy(date = value) }
    fun updateStartTime(value: LocalTime) = updateSelection { copy(startTime = value) }
    fun updateEndTime(value: LocalTime) = updateSelection { copy(endTime = value) }

    fun checkAvailability() {
        val request = mutableSelection.value
        val date = request.date
        val startTime = request.startTime
        val endTime = request.endTime
        if (date == null || startTime == null || endTime == null) {
            mutableAvailabilityState.value = AvailabilityUiState.Error("Choose a date, start time, and end time.")
            return
        }
        if (!endTime.isAfter(startTime)) {
            mutableAvailabilityState.value = AvailabilityUiState.Error("End time must be after start time.")
            return
        }
        availabilityJob?.cancel()
        val generation = ++availabilityGeneration
        availabilityJob = viewModelScope.launch {
            mutableAvailabilityState.value = AvailabilityUiState.Checking
            try {
                val response = repository.checkAvailability(
                    spaceId,
                    LocalDateTime.of(date, startTime).format(BACKEND_DATE_TIME),
                    LocalDateTime.of(date, endTime).format(BACKEND_DATE_TIME),
                )
                if (generation == availabilityGeneration && request == mutableSelection.value) {
                    mutableAvailabilityState.value = if (response.available) {
                        AvailabilityUiState.Available(response)
                    } else {
                        AvailabilityUiState.Unavailable(response)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                setAvailabilityError(exception, generation, request)
            } catch (exception: SocketTimeoutException) {
                setAvailabilityError(exception, generation, request)
            } catch (exception: IOException) {
                setAvailabilityError(exception, generation, request)
            } catch (exception: SerializationException) {
                setAvailabilityError(exception, generation, request)
            }
        }
    }

    fun requestBooking() {
        val available = mutableAvailabilityState.value as? AvailabilityUiState.Available ?: return
        if (mutableBookingState.value is BookingCreationUiState.Submitting) return
        mutableBookingState.value = BookingCreationUiState.Confirming(
            CreateBookingRequest(
                spaceId = spaceId,
                startDateTime = available.response.startDateTime,
                endDateTime = available.response.endDateTime,
            ),
        )
    }

    fun dismissBookingConfirmation() {
        if (mutableBookingState.value is BookingCreationUiState.Confirming) {
            mutableBookingState.value = BookingCreationUiState.Idle
        }
    }

    fun confirmBooking() {
        val confirming = mutableBookingState.value as? BookingCreationUiState.Confirming ?: return
        mutableBookingState.value = BookingCreationUiState.Submitting(confirming.request)
        viewModelScope.launch {
            try {
                mutableBookingState.value = BookingCreationUiState.Success(
                    repository.createBooking(confirming.request),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                val conflict = exception.statusCode == 409
                if (conflict) mutableAvailabilityState.value = AvailabilityUiState.Idle
                mutableBookingState.value = BookingCreationUiState.Error(
                    message = exception.toBookingCreationMessage(),
                    conflict = conflict,
                )
            } catch (exception: SocketTimeoutException) {
                mutableBookingState.value = BookingCreationUiState.Error(exception.toUserMessage())
            } catch (exception: IOException) {
                mutableBookingState.value = BookingCreationUiState.Error(exception.toUserMessage())
            } catch (exception: SerializationException) {
                mutableBookingState.value = BookingCreationUiState.Error(exception.toUserMessage())
            }
        }
    }

    fun clearBookingFeedback() {
        if (mutableBookingState.value !is BookingCreationUiState.Submitting) {
            mutableBookingState.value = BookingCreationUiState.Idle
        }
    }

    private fun setAvailabilityError(
        exception: Exception,
        generation: Long,
        request: AvailabilitySelection,
    ) {
        if (generation == availabilityGeneration && request == mutableSelection.value) {
            mutableAvailabilityState.value = AvailabilityUiState.Error(exception.toUserMessage())
        }
    }

    private fun loadSpace() {
        viewModelScope.launch {
            mutableDetailsState.value = SpaceDetailsUiState.Loading
            try {
                mutableDetailsState.value = SpaceDetailsUiState.Success(repository.getSpace(spaceId))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableDetailsState.value = SpaceDetailsUiState.Error(
                    message = exception.toUserMessage(),
                    notFound = exception.statusCode == 404,
                )
            } catch (exception: SocketTimeoutException) {
                mutableDetailsState.value = SpaceDetailsUiState.Error(exception.toUserMessage())
            } catch (exception: IOException) {
                mutableDetailsState.value = SpaceDetailsUiState.Error(exception.toUserMessage())
            } catch (exception: SerializationException) {
                mutableDetailsState.value = SpaceDetailsUiState.Error(exception.toUserMessage())
            }
        }
    }

    private fun updateSelection(transform: AvailabilitySelection.() -> AvailabilitySelection) {
        availabilityGeneration++
        availabilityJob?.cancel()
        mutableSelection.value = mutableSelection.value.transform()
        mutableAvailabilityState.value = AvailabilityUiState.Idle
        mutableBookingState.value = BookingCreationUiState.Idle
    }

    companion object {
        private val BACKEND_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}

private fun ApiException.toBookingCreationMessage(): String = when {
    statusCode == 409 && errorCode == "BOOKING_CONFLICT" ->
        "This time slot is no longer available. Please check availability again."
    statusCode == 409 -> "This space can no longer be booked for the selected time. Please check availability again."
    statusCode == 400 -> message.ifBlank { "Check the booking date and time, then try again." }
    statusCode == 401 -> "Your session has expired. Please sign in again."
    statusCode == 404 -> "This space is no longer available."
    else -> "Facilities service is temporarily unavailable."
}

private fun Exception.toUserMessage(): String = when (this) {
    is ApiException -> when (statusCode) {
        401 -> "Your session has expired. Please sign in again."
        404 -> "Space not found."
        in 400..499 -> message.ifBlank { "The request could not be completed." }
        else -> "Facilities service is temporarily unavailable."
    }
    is SocketTimeoutException -> "The request timed out. Please try again."
    is IOException -> "Network unavailable. Check your connection and try again."
    is SerializationException -> "The Facilities response could not be read."
    else -> "Something went wrong. Please try again."
}

package com.campuslink.mobile.ui.facilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.BookingStatus
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.facilities.FacilitiesDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime

sealed interface MyBookingsUiState {
    data object Loading : MyBookingsUiState
    data object Empty : MyBookingsUiState
    data class Success(val bookings: List<BookingResponse>) : MyBookingsUiState
    data class Error(val message: String) : MyBookingsUiState
}

class MyBookingsViewModel(
    private val repository: FacilitiesDataSource,
    private val nowProvider: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {
    private val mutableState = MutableStateFlow<MyBookingsUiState>(MyBookingsUiState.Loading)
    val state: StateFlow<MyBookingsUiState> = mutableState.asStateFlow()
    private var hasBeenPresented = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = MyBookingsUiState.Loading
            try {
                val bookings = repository.listBookings().sortedForDisplay(nowProvider())
                mutableState.value = if (bookings.isEmpty()) MyBookingsUiState.Empty
                else MyBookingsUiState.Success(bookings)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableState.value = MyBookingsUiState.Error(exception.toBookingMessage())
            } catch (exception: SocketTimeoutException) {
                mutableState.value = MyBookingsUiState.Error(exception.toBookingMessage())
            } catch (exception: IOException) {
                mutableState.value = MyBookingsUiState.Error(exception.toBookingMessage())
            } catch (exception: SerializationException) {
                mutableState.value = MyBookingsUiState.Error(exception.toBookingMessage())
            }
        }
    }

    fun onScreenVisible() {
        if (hasBeenPresented) refresh() else hasBeenPresented = true
    }
}

sealed interface BookingDetailsUiState {
    data object Loading : BookingDetailsUiState
    data class Success(val booking: BookingResponse) : BookingDetailsUiState
    data class Error(val message: String, val notFound: Boolean = false) : BookingDetailsUiState
}

sealed interface CancelBookingUiState {
    data object Idle : CancelBookingUiState
    data class Confirming(val booking: BookingResponse) : CancelBookingUiState
    data object Cancelling : CancelBookingUiState
    data class Error(val message: String) : CancelBookingUiState
}

class BookingDetailsViewModel(
    private val bookingId: Long,
    private val repository: FacilitiesDataSource,
    private val nowProvider: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {
    private val mutableDetailsState = MutableStateFlow<BookingDetailsUiState>(BookingDetailsUiState.Loading)
    val detailsState: StateFlow<BookingDetailsUiState> = mutableDetailsState.asStateFlow()
    private val mutableCancelState = MutableStateFlow<CancelBookingUiState>(CancelBookingUiState.Idle)
    val cancelState: StateFlow<CancelBookingUiState> = mutableCancelState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    fun canCancel(booking: BookingResponse): Boolean = booking.status == BookingStatus.CONFIRMED &&
        parseDateTime(booking.startDateTime)?.isAfter(nowProvider()) == true

    fun requestCancel() {
        val booking = (mutableDetailsState.value as? BookingDetailsUiState.Success)?.booking ?: return
        if (!canCancel(booking) || mutableCancelState.value is CancelBookingUiState.Cancelling) return
        mutableCancelState.value = CancelBookingUiState.Confirming(booking)
    }

    fun dismissCancelConfirmation() {
        if (mutableCancelState.value is CancelBookingUiState.Confirming) {
            mutableCancelState.value = CancelBookingUiState.Idle
        }
    }

    fun confirmCancel() {
        if (mutableCancelState.value !is CancelBookingUiState.Confirming) return
        mutableCancelState.value = CancelBookingUiState.Cancelling
        viewModelScope.launch {
            try {
                val booking = repository.cancelBooking(bookingId)
                mutableDetailsState.value = BookingDetailsUiState.Success(booking)
                mutableCancelState.value = CancelBookingUiState.Idle
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                handleCancelApiError(exception)
            } catch (exception: SocketTimeoutException) {
                mutableCancelState.value = CancelBookingUiState.Error(exception.toBookingMessage())
            } catch (exception: IOException) {
                mutableCancelState.value = CancelBookingUiState.Error(exception.toBookingMessage())
            } catch (exception: SerializationException) {
                mutableCancelState.value = CancelBookingUiState.Error(exception.toBookingMessage())
            }
        }
    }

    fun clearCancelError() {
        if (mutableCancelState.value is CancelBookingUiState.Error) {
            mutableCancelState.value = CancelBookingUiState.Idle
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableDetailsState.value = BookingDetailsUiState.Loading
            mutableCancelState.value = CancelBookingUiState.Idle
            try {
                mutableDetailsState.value = BookingDetailsUiState.Success(repository.getBookingDetails(bookingId))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                mutableDetailsState.value = BookingDetailsUiState.Error(
                    message = exception.toBookingMessage(),
                    notFound = exception.statusCode == 404,
                )
            } catch (exception: SocketTimeoutException) {
                mutableDetailsState.value = BookingDetailsUiState.Error(exception.toBookingMessage())
            } catch (exception: IOException) {
                mutableDetailsState.value = BookingDetailsUiState.Error(exception.toBookingMessage())
            } catch (exception: SerializationException) {
                mutableDetailsState.value = BookingDetailsUiState.Error(exception.toBookingMessage())
            }
        }
    }

    private fun handleCancelApiError(exception: ApiException) {
        if (exception.statusCode == 404) {
            mutableDetailsState.value = BookingDetailsUiState.Error("Booking not found.", notFound = true)
            mutableCancelState.value = CancelBookingUiState.Idle
        } else {
            mutableCancelState.value = CancelBookingUiState.Error(exception.toBookingMessage())
        }
    }
}

internal fun List<BookingResponse>.sortedForDisplay(now: LocalDateTime): List<BookingResponse> = sortedWith { a, b ->
    val aStart = parseDateTime(a.startDateTime)
    val bStart = parseDateTime(b.startDateTime)
    val aBucket = displayBucket(a, aStart, now)
    val bBucket = displayBucket(b, bStart, now)
    if (aBucket != bBucket) {
        aBucket.compareTo(bBucket)
    } else {
        val dateOrder = if (aBucket < HISTORY_BUCKET) compareValues(aStart, bStart)
        else compareValues(bStart, aStart)
        if (dateOrder != 0) dateOrder else a.bookingId.compareTo(b.bookingId)
    }
}

private fun displayBucket(booking: BookingResponse, start: LocalDateTime?, now: LocalDateTime): Int = when {
    booking.status == BookingStatus.CONFIRMED && start?.isAfter(now) == true -> 0
    start?.isAfter(now) == true -> 1
    else -> HISTORY_BUCKET
}

private fun Exception.toBookingMessage(): String = when (this) {
    is ApiException -> when (statusCode) {
        400 -> message.ifBlank { "Check the booking information and try again." }
        401 -> "Your session has expired. Please sign in again."
        404 -> "Booking not found."
        409 -> message.ifBlank { "This booking can no longer be cancelled." }
        in 500..599 -> "Facilities service is temporarily unavailable."
        else -> "The booking request could not be completed."
    }
    is SocketTimeoutException -> "The request timed out. Please try again."
    is IOException -> "Network unavailable. Check your connection and try again."
    is SerializationException -> "The booking response could not be read."
    else -> "Something went wrong. Please try again."
}

private const val HISTORY_BUCKET = 2

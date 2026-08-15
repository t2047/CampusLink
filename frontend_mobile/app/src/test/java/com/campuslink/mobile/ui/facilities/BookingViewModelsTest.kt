package com.campuslink.mobile.ui.facilities

import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.BookingStatus
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.facilities.FacilitiesDataSource
import com.campuslink.mobile.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class BookingViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `available slot requires confirmation before one create request`() = runTest {
        val repository = FakeBookingDataSource()
        val pending = CompletableDeferred<BookingResponse>()
        repository.createHandler = { pending.await() }
        val viewModel = availableDetailsViewModel(repository)

        viewModel.requestBooking()
        assertTrue(viewModel.bookingState.value is BookingCreationUiState.Confirming)
        assertEquals(0, repository.createCalls)
        viewModel.confirmBooking()
        viewModel.confirmBooking()
        runCurrent()

        assertTrue(viewModel.bookingState.value is BookingCreationUiState.Submitting)
        assertEquals(1, repository.createCalls)
        pending.complete(booking())
        advanceUntilIdle()
        assertTrue(viewModel.bookingState.value is BookingCreationUiState.Success)
    }

    @Test
    fun `create conflict clears stale availability`() = runTest {
        val repository = FakeBookingDataSource().apply {
            createFailure = ApiException(409, "overlap", "BOOKING_CONFLICT")
        }
        val viewModel = availableDetailsViewModel(repository)

        viewModel.requestBooking()
        viewModel.confirmBooking()
        advanceUntilIdle()

        val error = viewModel.bookingState.value as BookingCreationUiState.Error
        assertTrue(error.conflict)
        assertTrue(error.message.contains("no longer available"))
        assertEquals(AvailabilityUiState.Idle, viewModel.availabilityState.value)
    }

    @Test
    fun `unavailable slot cannot enter create flow`() = runTest {
        val repository = FakeBookingDataSource().apply { availabilityAvailable = false }
        val viewModel = availableDetailsViewModel(repository)

        assertTrue(viewModel.availabilityState.value is AvailabilityUiState.Unavailable)
        viewModel.requestBooking()

        assertEquals(BookingCreationUiState.Idle, viewModel.bookingState.value)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun `my bookings handles ordering empty error and retry`() = runTest {
        val repository = FakeBookingDataSource().apply {
            bookings = listOf(
                booking(4, BookingStatus.CANCELLED, "2026-08-17T12:00:00"),
                booking(3, BookingStatus.CONFIRMED, "2026-08-18T12:00:00"),
                booking(2, BookingStatus.CONFIRMED, "2026-08-17T10:00:00"),
                booking(1, BookingStatus.COMPLETED, "2026-08-15T10:00:00"),
            )
        }
        val now = { LocalDateTime.of(2026, 8, 16, 12, 0) }
        val viewModel = MyBookingsViewModel(repository, now)
        advanceUntilIdle()

        val success = viewModel.state.value as MyBookingsUiState.Success
        assertEquals(listOf(2L, 3L, 4L, 1L), success.bookings.map { it.bookingId })

        repository.bookings = emptyList()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(MyBookingsUiState.Empty, viewModel.state.value)

        repository.listFailure = java.io.IOException("offline")
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is MyBookingsUiState.Error)
        repository.listFailure = null
        repository.bookings = listOf(booking())
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is MyBookingsUiState.Success)
    }

    @Test
    fun `booking details maps not found and retries`() = runTest {
        val repository = FakeBookingDataSource().apply {
            detailsFailure = ApiException(404, "missing", "BOOKING_NOT_FOUND")
        }
        val viewModel = BookingDetailsViewModel(72, repository)
        advanceUntilIdle()

        assertTrue((viewModel.detailsState.value as BookingDetailsUiState.Error).notFound)
        repository.detailsFailure = null
        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.detailsState.value is BookingDetailsUiState.Success)
    }

    @Test
    fun `cancel waits for confirmation prevents duplicates and updates details`() = runTest {
        val repository = FakeBookingDataSource()
        val pending = CompletableDeferred<BookingResponse>()
        repository.cancelHandler = { pending.await() }
        val viewModel = BookingDetailsViewModel(
            72,
            repository,
            nowProvider = { LocalDateTime.of(2026, 8, 16, 12, 0) },
        )
        advanceUntilIdle()

        viewModel.requestCancel()
        assertTrue(viewModel.cancelState.value is CancelBookingUiState.Confirming)
        assertEquals(0, repository.cancelCalls)
        viewModel.confirmCancel()
        viewModel.confirmCancel()
        runCurrent()

        assertEquals(1, repository.cancelCalls)
        assertEquals(CancelBookingUiState.Cancelling, viewModel.cancelState.value)
        pending.complete(booking(status = BookingStatus.CANCELLED))
        advanceUntilIdle()
        val details = viewModel.detailsState.value as BookingDetailsUiState.Success
        assertEquals(BookingStatus.CANCELLED, details.booking.status)
        assertEquals(CancelBookingUiState.Idle, viewModel.cancelState.value)
    }

    @Test
    fun `cancel conflict remains an error and keeps confirmed details`() = runTest {
        val repository = FakeBookingDataSource().apply {
            cancelFailure = ApiException(409, "A booking cannot be cancelled after its start time")
        }
        val viewModel = BookingDetailsViewModel(
            72,
            repository,
            nowProvider = { LocalDateTime.of(2026, 8, 16, 12, 0) },
        )
        advanceUntilIdle()

        viewModel.requestCancel()
        viewModel.confirmCancel()
        advanceUntilIdle()

        assertTrue(viewModel.cancelState.value is CancelBookingUiState.Error)
        val details = viewModel.detailsState.value as BookingDetailsUiState.Success
        assertEquals(BookingStatus.CONFIRMED, details.booking.status)
    }

    private suspend fun TestScope.availableDetailsViewModel(repository: FakeBookingDataSource): SpaceDetailsViewModel {
        val viewModel = SpaceDetailsViewModel(4, repository)
        advanceUntilIdle()
        viewModel.updateDate(LocalDate.of(2026, 8, 17))
        viewModel.updateStartTime(LocalTime.of(9, 0))
        viewModel.updateEndTime(LocalTime.of(11, 0))
        viewModel.checkAvailability()
        advanceUntilIdle()
        return viewModel
    }

    private class FakeBookingDataSource : FacilitiesDataSource {
        var bookings = listOf(booking())
        var listFailure: Exception? = null
        var detailsFailure: Exception? = null
        var createFailure: Exception? = null
        var cancelFailure: Exception? = null
        var createCalls = 0
        var cancelCalls = 0
        var availabilityAvailable = true
        var createHandler: (suspend (CreateBookingRequest) -> BookingResponse)? = null
        var cancelHandler: (suspend (Long) -> BookingResponse)? = null

        override suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space> = listOf(SPACE)

        override suspend fun getSpace(spaceId: Long): Space = SPACE

        override suspend fun checkAvailability(
            spaceId: Long,
            startDateTime: String,
            endDateTime: String,
        ) = AvailabilityResponse(
            availabilityAvailable,
            if (availabilityAvailable) null else "BOOKING_CONFLICT",
            SPACE,
            startDateTime,
            endDateTime,
        )

        override suspend fun createBooking(request: CreateBookingRequest): BookingResponse {
            createCalls++
            createFailure?.let { throw it }
            return createHandler?.invoke(request) ?: booking()
        }

        override suspend fun listBookings(): List<BookingResponse> {
            listFailure?.let { throw it }
            return bookings
        }

        override suspend fun getBookingDetails(bookingId: Long): BookingResponse {
            detailsFailure?.let { throw it }
            return booking()
        }

        override suspend fun cancelBooking(bookingId: Long): BookingResponse {
            cancelCalls++
            cancelFailure?.let { throw it }
            return cancelHandler?.invoke(bookingId) ?: booking(status = BookingStatus.CANCELLED)
        }
    }

    companion object {
        private val SPACE = Space(
            4,
            "COM3-01-20 Project Room",
            "COM3",
            "01",
            "01-20",
            "PROJECT_ROOM",
            6,
            setOf("TV", "Whiteboard"),
            "08:00:00",
            "22:00:00",
            "AVAILABLE",
        )

        private fun booking(
            id: Long = 72,
            status: BookingStatus = BookingStatus.CONFIRMED,
            start: String = "2026-08-17T09:00:00",
        ) = BookingResponse(
            success = true,
            bookingId = id,
            space = SPACE,
            startDateTime = start,
            endDateTime = LocalDateTime.parse(start).plusHours(2).toString() + ":00",
            status = status,
            createdAt = "2026-08-16T10:00:00",
            updatedAt = "2026-08-16T10:00:00",
        )
    }
}

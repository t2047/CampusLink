package com.campuslink.mobile.ui.facilities

import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.MaintenancePriority
import com.campuslink.mobile.core.model.MaintenanceResponse
import com.campuslink.mobile.core.model.MaintenanceStatus
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.model.SubmitMaintenanceRequest
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.facilities.FacilitiesDataSource
import com.campuslink.mobile.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class MaintenanceViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `submit starts idle loads spaces and keeps valid preselection`() = runTest {
        val viewModel = SubmitMaintenanceViewModel(FakeMaintenanceDataSource(), preselectedSpaceId = 4)
        advanceUntilIdle()

        assertEquals(SubmitMaintenanceUiState.Idle, viewModel.submitState.value)
        assertTrue(viewModel.spacesState.value is MaintenanceSpacesUiState.Success)
        assertEquals(4L, viewModel.form.value.selectedSpaceId)
    }

    @Test
    fun `submit validation reports required fields before confirmation`() = runTest {
        val repository = FakeMaintenanceDataSource()
        val viewModel = SubmitMaintenanceViewModel(repository)
        advanceUntilIdle()

        viewModel.requestSubmit()

        val error = viewModel.submitState.value as SubmitMaintenanceUiState.Error
        assertEquals(setOf("spaceId", "facilityType", "description"), error.fieldErrors.keys)
        assertEquals(0, repository.submitCalls)
    }

    @Test
    fun `submit confirms first prevents duplicate write and returns success`() = runTest {
        val repository = FakeMaintenanceDataSource()
        val pending = CompletableDeferred<MaintenanceResponse>()
        repository.submitHandler = { pending.await() }
        val viewModel = validSubmitViewModel(repository)

        viewModel.requestSubmit()
        assertTrue(viewModel.submitState.value is SubmitMaintenanceUiState.Confirming)
        assertEquals(0, repository.submitCalls)
        viewModel.confirmSubmit()
        viewModel.confirmSubmit()
        runCurrent()

        assertEquals(1, repository.submitCalls)
        assertEquals(SubmitMaintenanceUiState.Submitting, viewModel.submitState.value)
        pending.complete(MAINTENANCE)
        advanceUntilIdle()
        assertEquals(91L, (viewModel.submitState.value as SubmitMaintenanceUiState.Success).maintenance.ticketId)
    }

    @Test
    fun `submit maps backend validation and network errors`() = runTest {
        val repository = FakeMaintenanceDataSource().apply {
            submitFailure = ApiException(
                400,
                "invalid",
                validationErrors = mapOf("description" to "must not be blank"),
            )
        }
        val viewModel = validSubmitViewModel(repository)
        viewModel.requestSubmit()
        viewModel.confirmSubmit()
        advanceUntilIdle()

        val validation = viewModel.submitState.value as SubmitMaintenanceUiState.Error
        assertEquals("must not be blank", validation.fieldErrors["description"])

        repository.submitFailure = IOException("offline")
        viewModel.requestSubmit()
        viewModel.confirmSubmit()
        advanceUntilIdle()
        assertTrue((viewModel.submitState.value as SubmitMaintenanceUiState.Error).message.contains("Network"))
    }

    @Test
    fun `list handles ordering empty error and retry`() = runTest {
        val repository = FakeMaintenanceDataSource().apply {
            requests = listOf(
                MAINTENANCE.copy(ticketId = 1, status = MaintenanceStatus.RESOLVED, updatedAt = "2026-08-16T15:00:00"),
                MAINTENANCE.copy(ticketId = 2, status = MaintenanceStatus.SUBMITTED, updatedAt = "2026-08-16T10:00:00"),
                MAINTENANCE.copy(ticketId = 3, status = MaintenanceStatus.IN_PROGRESS, updatedAt = "2026-08-16T14:00:00"),
                MAINTENANCE.copy(ticketId = 4, status = MaintenanceStatus.CANCELLED, updatedAt = "2026-08-16T16:00:00"),
            )
        }
        val viewModel = MyMaintenanceViewModel(repository)
        advanceUntilIdle()

        val success = viewModel.state.value as MyMaintenanceUiState.Success
        assertEquals(listOf(3L, 2L, 4L, 1L), success.requests.map(MaintenanceResponse::ticketId))

        repository.requests = emptyList()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(MyMaintenanceUiState.Empty, viewModel.state.value)

        repository.listFailure = IOException("offline")
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is MyMaintenanceUiState.Error)
        repository.listFailure = null
        repository.requests = listOf(MAINTENANCE)
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is MyMaintenanceUiState.Success)
    }

    @Test
    fun `details loads success maps safe not found and retries`() = runTest {
        val repository = FakeMaintenanceDataSource()
        val success = MaintenanceDetailsViewModel(91, repository)
        advanceUntilIdle()
        assertTrue(success.state.value is MaintenanceDetailsUiState.Success)

        repository.detailsFailure = ApiException(404, "missing", "TICKET_NOT_FOUND")
        val missing = MaintenanceDetailsViewModel(999, repository)
        advanceUntilIdle()
        assertTrue((missing.state.value as MaintenanceDetailsUiState.Error).notFound)

        repository.detailsFailure = null
        missing.retry()
        advanceUntilIdle()
        assertTrue(missing.state.value is MaintenanceDetailsUiState.Success)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.validSubmitViewModel(
        repository: FakeMaintenanceDataSource,
    ): SubmitMaintenanceViewModel {
        val viewModel = SubmitMaintenanceViewModel(repository, preselectedSpaceId = 4)
        advanceUntilIdle()
        viewModel.updateFacilityType("Projector")
        viewModel.updateDescription("The projector cannot turn on.")
        viewModel.updatePriority(MaintenancePriority.HIGH)
        return viewModel
    }

    private class FakeMaintenanceDataSource : FacilitiesDataSource {
        var requests = listOf(MAINTENANCE)
        var submitCalls = 0
        var submitFailure: Exception? = null
        var listFailure: Exception? = null
        var detailsFailure: Exception? = null
        var submitHandler: (suspend (SubmitMaintenanceRequest) -> MaintenanceResponse)? = null

        override suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space> = listOf(SPACE)
        override suspend fun getSpace(spaceId: Long): Space = SPACE
        override suspend fun checkAvailability(
            spaceId: Long,
            startDateTime: String,
            endDateTime: String,
        ): AvailabilityResponse = error("Unused")
        override suspend fun createBooking(request: CreateBookingRequest): BookingResponse = error("Unused")
        override suspend fun listBookings(): List<BookingResponse> = error("Unused")
        override suspend fun getBookingDetails(bookingId: Long): BookingResponse = error("Unused")
        override suspend fun cancelBooking(bookingId: Long): BookingResponse = error("Unused")

        override suspend fun submitMaintenance(request: SubmitMaintenanceRequest): MaintenanceResponse {
            submitCalls++
            submitFailure?.let { throw it }
            return submitHandler?.invoke(request) ?: MAINTENANCE
        }

        override suspend fun listMaintenanceRequests(): List<MaintenanceResponse> {
            listFailure?.let { throw it }
            return requests
        }

        override suspend fun getMaintenanceDetails(ticketId: Long): MaintenanceResponse {
            detailsFailure?.let { throw it }
            return MAINTENANCE
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
        private val MAINTENANCE = MaintenanceResponse(
            success = true,
            ticketId = 91,
            spaceId = 4,
            spaceName = SPACE.name,
            building = SPACE.building,
            roomNumber = SPACE.roomNumber,
            facilityType = "Projector",
            description = "The projector cannot turn on.",
            priority = MaintenancePriority.HIGH,
            status = MaintenanceStatus.SUBMITTED,
            createdAt = "2026-08-16T13:45:00",
            updatedAt = "2026-08-16T13:45:00",
        )
    }
}

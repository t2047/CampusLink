package com.campuslink.mobile.ui.facilities

import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.facilities.FacilitiesDataSource
import com.campuslink.mobile.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class FacilitiesViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `search loads success and sends form filters`() = runTest {
        val repository = FakeFacilitiesDataSource()
        val viewModel = SpaceSearchViewModel(repository)
        advanceUntilIdle()
        viewModel.updateQuery("project")
        viewModel.updateMinimumCapacity("4")
        viewModel.updateEquipment("TV, Whiteboard")

        viewModel.search()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is SpaceSearchUiState.Success)
        assertEquals(4, repository.lastFilters.minimumCapacity)
        assertEquals(listOf("TV", "Whiteboard"), repository.lastFilters.equipment)
    }

    @Test
    fun `search represents empty and error states and retry`() = runTest {
        val repository = FakeFacilitiesDataSource()
        repository.searchResult = emptyList()
        val viewModel = SpaceSearchViewModel(repository)
        advanceUntilIdle()
        assertEquals(SpaceSearchUiState.Empty, viewModel.state.value)

        repository.searchFailure = java.io.IOException("offline")
        viewModel.search()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is SpaceSearchUiState.Error)

        repository.searchFailure = null
        repository.searchResult = listOf(SPACE)
        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is SpaceSearchUiState.Success)
    }

    @Test
    fun `details maps not found and can retry`() = runTest {
        val repository = FakeFacilitiesDataSource().apply { detailsFailure = ApiException(404, "missing") }
        val viewModel = SpaceDetailsViewModel(4, repository)
        advanceUntilIdle()
        val failure = viewModel.detailsState.value as SpaceDetailsUiState.Error
        assertTrue(failure.notFound)

        repository.detailsFailure = null
        viewModel.retry()
        advanceUntilIdle()
        assertTrue(viewModel.detailsState.value is SpaceDetailsUiState.Success)
    }

    @Test
    fun `availability uses exact local datetime and input change invalidates result`() = runTest {
        val repository = FakeFacilitiesDataSource()
        val viewModel = SpaceDetailsViewModel(4, repository)
        advanceUntilIdle()
        viewModel.updateDate(LocalDate.of(2026, 8, 17))
        viewModel.updateStartTime(LocalTime.of(9, 0))
        viewModel.updateEndTime(LocalTime.of(11, 0))

        viewModel.checkAvailability()
        advanceUntilIdle()

        assertTrue(viewModel.availabilityState.value is AvailabilityUiState.Available)
        assertEquals("2026-08-17T09:00:00", repository.lastStartDateTime)
        assertEquals("2026-08-17T11:00:00", repository.lastEndDateTime)
        viewModel.updateEndTime(LocalTime.of(12, 0))
        assertEquals(AvailabilityUiState.Idle, viewModel.availabilityState.value)
    }

    @Test
    fun `stale availability response cannot replace newer input result`() = runTest {
        val first = CompletableDeferred<AvailabilityResponse>()
        val second = CompletableDeferred<AvailabilityResponse>()
        val repository = FakeFacilitiesDataSource().apply {
            availabilityHandler = { _, _, _ ->
                val response = if (availabilityCalls++ == 0) first else second
                withContext(NonCancellable) { response.await() }
            }
        }
        val viewModel = SpaceDetailsViewModel(4, repository)
        advanceUntilIdle()
        viewModel.updateDate(LocalDate.of(2026, 8, 17))
        viewModel.updateStartTime(LocalTime.of(9, 0))
        viewModel.updateEndTime(LocalTime.of(10, 0))
        viewModel.checkAvailability()
        viewModel.updateEndTime(LocalTime.of(11, 0))
        viewModel.checkAvailability()

        second.complete(availability(true, "2026-08-17T11:00:00"))
        advanceUntilIdle()
        assertTrue(viewModel.availabilityState.value is AvailabilityUiState.Available)
        first.complete(availability(false, "2026-08-17T10:00:00"))
        advanceUntilIdle()
        assertTrue(viewModel.availabilityState.value is AvailabilityUiState.Available)
    }

    private class FakeFacilitiesDataSource : FacilitiesDataSource {
        var searchResult = listOf(SPACE)
        var searchFailure: Exception? = null
        var detailsFailure: Exception? = null
        var lastFilters = SpaceSearchFilters()
        var lastStartDateTime = ""
        var lastEndDateTime = ""
        var availabilityCalls = 0
        var availabilityHandler: suspend (Long, String, String) -> AvailabilityResponse = { _, start, end ->
            availability(true, end, start)
        }

        override suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space> {
            lastFilters = filters
            searchFailure?.let { throw it }
            return searchResult
        }

        override suspend fun getSpace(spaceId: Long): Space {
            detailsFailure?.let { throw it }
            return SPACE
        }

        override suspend fun checkAvailability(
            spaceId: Long,
            startDateTime: String,
            endDateTime: String,
        ): AvailabilityResponse {
            lastStartDateTime = startDateTime
            lastEndDateTime = endDateTime
            return availabilityHandler(spaceId, startDateTime, endDateTime)
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
            setOf("TV"),
            "08:00:00",
            "22:00:00",
            "AVAILABLE",
        )

        private fun availability(
            available: Boolean,
            end: String,
            start: String = "2026-08-17T09:00:00",
        ) = AvailabilityResponse(available, if (available) null else "BOOKING_CONFLICT", SPACE, start, end)
    }
}

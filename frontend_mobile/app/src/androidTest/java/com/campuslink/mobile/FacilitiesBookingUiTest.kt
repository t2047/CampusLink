package com.campuslink.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.BookingStatus
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.facilities.FacilitiesDataSource
import com.campuslink.mobile.ui.facilities.BookingDetailsScreen
import com.campuslink.mobile.ui.facilities.BookingDetailsViewModel
import com.campuslink.mobile.ui.facilities.FacilitiesHomeScreen
import com.campuslink.mobile.ui.facilities.MyBookingsScreen
import com.campuslink.mobile.ui.facilities.MyBookingsViewModel
import com.campuslink.mobile.ui.facilities.SpaceDetailsScreen
import com.campuslink.mobile.ui.facilities.SpaceDetailsViewModel
import com.campuslink.mobile.ui.facilities.SpaceSearchScreen
import com.campuslink.mobile.ui.facilities.SpaceSearchViewModel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class FacilitiesBookingUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun facilitiesHomeCardsExposeRealRoutes() {
        var openedSearch = false
        rule.setContent {
            MaterialTheme {
                FacilitiesHomeScreen(
                    onBack = {},
                    onSearchSpaces = { openedSearch = true },
                    onMyBookings = {},
                    onReportMaintenance = {},
                    onMyMaintenance = {},
                )
            }
        }

        rule.onNodeWithText("Find a Space").assertHasClickAction().performClick()
        rule.onNodeWithText("My Bookings").assertHasClickAction()
        rule.onNodeWithText("Report Maintenance").assertHasClickAction()
        rule.onNodeWithText("My Maintenance").assertHasClickAction()
        rule.runOnIdle { assertTrue(openedSearch) }
    }

    @Test
    fun spaceSearchResultShowsFactsAndStatusChip() {
        val viewModel = SpaceSearchViewModel(FakeFacilitiesDataSource())
        rule.setContent {
            MaterialTheme { SpaceSearchScreen(viewModel, onBack = {}, onOpenSpace = {}) }
        }

        rule.onNodeWithText("COM3-01-20 Project Room").assertIsDisplayed()
        rule.onNodeWithText("AVAILABLE").assertIsDisplayed()
        rule.onNodeWithText("TV, Whiteboard").assertIsDisplayed()
        rule.onNodeWithText("Advanced filters").performClick()
        rule.onNodeWithText("Building").assertIsDisplayed()
    }

    @Test
    fun myBookingsRendersRealModelFields() {
        val repository = FakeFacilitiesDataSource()
        val viewModel = MyBookingsViewModel(repository)
        rule.setContent {
            MaterialTheme {
                MyBookingsScreen(viewModel, onBack = {}, onOpenBooking = {})
            }
        }

        rule.onNodeWithText("COM3-01-20 Project Room").assertIsDisplayed()
        rule.onNodeWithText("CONFIRMED").assertIsDisplayed()
    }

    @Test
    fun createBookingShowsConfirmationBeforeWrite() {
        val repository = FakeFacilitiesDataSource()
        val viewModel = SpaceDetailsViewModel(4, repository)
        rule.setContent {
            MaterialTheme {
                SpaceDetailsScreen(viewModel, onBack = {}, onViewBooking = {}, onMyBookings = {})
            }
        }
        rule.runOnIdle {
            viewModel.updateDate(LocalDate.of(2099, 8, 17))
            viewModel.updateStartTime(LocalTime.of(9, 0))
            viewModel.updateEndTime(LocalTime.of(11, 0))
            viewModel.checkAvailability()
        }

        rule.onNodeWithText("Book This Space").performScrollTo().performClick()
        rule.onNodeWithText("Book COM3-01-20 Project Room?").assertIsDisplayed()
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun spaceDetailsShowsStructuredSections() {
        val viewModel = SpaceDetailsViewModel(4, FakeFacilitiesDataSource())
        rule.setContent {
            MaterialTheme {
                SpaceDetailsScreen(viewModel, onBack = {}, onViewBooking = {}, onMyBookings = {})
            }
        }

        rule.onNodeWithText("Key facts").assertIsDisplayed()
        rule.onNodeWithText("Equipment").assertIsDisplayed()
        rule.onNodeWithText("Availability").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cancelBookingShowsConfirmationBeforeWrite() {
        val repository = FakeFacilitiesDataSource()
        val viewModel = BookingDetailsViewModel(72, repository)
        rule.setContent {
            MaterialTheme {
                BookingDetailsScreen(viewModel, onBack = {})
            }
        }

        rule.onNodeWithText("Cancel Booking").performScrollTo().performClick()
        rule.onNodeWithText("Cancel this booking?").assertIsDisplayed()
        assertEquals(0, repository.cancelCalls)
    }

    private class FakeFacilitiesDataSource : FacilitiesDataSource {
        var createCalls = 0
        var cancelCalls = 0

        override suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space> = listOf(SPACE)
        override suspend fun getSpace(spaceId: Long): Space = SPACE

        override suspend fun checkAvailability(
            spaceId: Long,
            startDateTime: String,
            endDateTime: String,
        ) = AvailabilityResponse(true, null, SPACE, startDateTime, endDateTime)

        override suspend fun createBooking(request: CreateBookingRequest): BookingResponse {
            createCalls++
            return BOOKING
        }

        override suspend fun listBookings(): List<BookingResponse> = listOf(BOOKING)
        override suspend fun getBookingDetails(bookingId: Long): BookingResponse = BOOKING

        override suspend fun cancelBooking(bookingId: Long): BookingResponse {
            cancelCalls++
            return BOOKING.copy(status = BookingStatus.CANCELLED)
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
        private val BOOKING = BookingResponse(
            success = true,
            bookingId = 72,
            space = SPACE,
            startDateTime = "2099-08-17T09:00:00",
            endDateTime = "2099-08-17T11:00:00",
            status = BookingStatus.CONFIRMED,
            createdAt = "2099-08-16T10:00:00",
            updatedAt = "2099-08-16T10:00:00",
        )
    }
}

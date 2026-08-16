package com.campuslink.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.MaintenancePriority
import com.campuslink.mobile.core.model.MaintenanceResponse
import com.campuslink.mobile.core.model.MaintenanceStatus
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.model.SubmitMaintenanceRequest
import com.campuslink.mobile.facilities.FacilitiesDataSource
import com.campuslink.mobile.ui.facilities.MaintenanceDetailsScreen
import com.campuslink.mobile.ui.facilities.MaintenanceDetailsViewModel
import com.campuslink.mobile.ui.facilities.MyMaintenanceScreen
import com.campuslink.mobile.ui.facilities.MyMaintenanceViewModel
import com.campuslink.mobile.ui.facilities.SubmitMaintenanceScreen
import com.campuslink.mobile.ui.facilities.SubmitMaintenanceViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FacilitiesMaintenanceUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun reportMaintenanceFormRendersWithPreselectedSpace() {
        val viewModel = SubmitMaintenanceViewModel(FakeFacilitiesDataSource(), preselectedSpaceId = 4)
        rule.setContent {
            MaterialTheme {
                SubmitMaintenanceScreen(viewModel, onBack = {}, onViewRequest = {}, onMyMaintenance = {})
            }
        }

        rule.onNodeWithText("Report Maintenance").assertIsDisplayed()
        rule.onNodeWithText("COM3-01-20 Project Room · COM3 / 01-20").assertIsDisplayed()
        rule.onNodeWithText("Facility Type").assertIsDisplayed()
        rule.onNodeWithText("Description").assertIsDisplayed()
    }

    @Test
    fun submitConfirmationAppearsBeforePost() {
        val repository = FakeFacilitiesDataSource()
        val viewModel = SubmitMaintenanceViewModel(repository, preselectedSpaceId = 4)
        rule.setContent {
            MaterialTheme {
                SubmitMaintenanceScreen(viewModel, onBack = {}, onViewRequest = {}, onMyMaintenance = {})
            }
        }
        rule.runOnIdle {
            viewModel.updateFacilityType("Projector")
            viewModel.updateDescription("The projector cannot turn on.")
            viewModel.updatePriority(MaintenancePriority.HIGH)
        }

        rule.onNodeWithText("Submit Request").performScrollTo().performClick()
        rule.onNodeWithText("Submit maintenance request?").assertIsDisplayed()
        assertEquals(0, repository.submitCalls)
    }

    @Test
    fun myMaintenanceListRendersRealFields() {
        val viewModel = MyMaintenanceViewModel(FakeFacilitiesDataSource())
        rule.setContent {
            MaterialTheme { MyMaintenanceScreen(viewModel, onBack = {}, onOpenRequest = {}) }
        }

        rule.onNodeWithText("Ticket #91").assertIsDisplayed()
        rule.onNodeWithText("Projector").assertIsDisplayed()
        rule.onNodeWithText("Submitted").assertIsDisplayed()
    }

    @Test
    fun maintenanceDetailsShowsReadOnlyStatus() {
        val viewModel = MaintenanceDetailsViewModel(91, FakeFacilitiesDataSource())
        rule.setContent {
            MaterialTheme { MaintenanceDetailsScreen(viewModel, onBack = {}) }
        }

        rule.onNodeWithText("Maintenance Details").assertIsDisplayed()
        rule.onNodeWithText("Ticket #91").assertIsDisplayed()
        rule.onNodeWithText("Status updates are managed by Facilities staff.").assertIsDisplayed()
        rule.onAllNodesWithText("Submitted", useUnmergedTree = true)[0].assertIsDisplayed()
    }

    private class FakeFacilitiesDataSource : FacilitiesDataSource {
        var submitCalls = 0

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
            return MAINTENANCE
        }

        override suspend fun listMaintenanceRequests(): List<MaintenanceResponse> = listOf(MAINTENANCE)
        override suspend fun getMaintenanceDetails(ticketId: Long): MaintenanceResponse = MAINTENANCE
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

package com.campuslink.mobile.ui.lostfound

import com.campuslink.mobile.core.model.ClaimReportSummary
import com.campuslink.mobile.core.model.ClaimStatus
import com.campuslink.mobile.core.model.CreateLostFoundReportRequest
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.LostFoundClaim
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.LostFoundSearchFilters
import com.campuslink.mobile.core.model.PageResponse
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.core.model.UploadImage
import com.campuslink.mobile.lostfound.LostFoundDataSource
import com.campuslink.mobile.ui.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LostFoundViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `browse defaults to open found reports and sends filters`() = runTest {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundBrowseViewModel(repository)
        advanceUntilIdle()
        viewModel.updateKeyword("headphones")
        viewModel.updateCategory(ItemCategory.ELECTRONICS)

        viewModel.search()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is LostFoundBrowseUiState.Success)
        assertEquals(ReportType.FOUND, repository.lastFilters.reportType)
        assertEquals(ReportStatus.OPEN, repository.lastFilters.status)
        assertEquals("headphones", repository.lastFilters.keyword)
    }

    @Test
    fun `switching report type immediately reloads matching reports`() = runTest {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundBrowseViewModel(repository)
        advanceUntilIdle()
        val callsBefore = repository.searchCalls

        viewModel.updateReportType(ReportType.LOST)
        advanceUntilIdle()

        assertEquals(ReportType.LOST, viewModel.form.value.reportType)
        assertEquals(ReportType.LOST, repository.lastFilters.reportType)
        assertEquals(callsBefore + 1, repository.searchCalls)
        assertTrue(viewModel.state.value is LostFoundBrowseUiState.Success)
    }

    @Test
    fun `browse validates dates before calling repository`() = runTest {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundBrowseViewModel(repository)
        advanceUntilIdle()
        val callsBefore = repository.searchCalls
        viewModel.updateDateFrom("16-08-2026")

        viewModel.search()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is LostFoundBrowseUiState.Error)
        assertEquals(callsBefore, repository.searchCalls)
    }

    @Test
    fun `create validates required fields and submits images`() = runTest {
        val repository = FakeLostFoundDataSource()
        val viewModel = CreateLostFoundReportViewModel(ReportType.LOST, repository)
        viewModel.submit(emptyList())
        assertTrue(viewModel.state.value is CreateReportUiState.Error)

        viewModel.updateItemName("Black headphones")
        viewModel.updateCategory(ItemCategory.ELECTRONICS)
        viewModel.updateDescription("Black over-ear headphones with a small scratch.")
        viewModel.updateLocation("CLB")
        viewModel.updateEventDate("2026-08-15")
        viewModel.submit(listOf(UploadImage("item.png", "image/png", byteArrayOf(1))))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is CreateReportUiState.Success)
        assertEquals(ReportType.LOST, repository.lastCreateRequest?.reportType)
        assertEquals(1, repository.lastImages.size)
    }

    @Test
    fun `claim requires meaningful proof and submits once valid`() = runTest {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundDetailsViewModel(8, repository)
        advanceUntilIdle()
        viewModel.submitClaim("short")
        assertTrue(viewModel.claimState.value is ClaimSubmissionUiState.Error)

        viewModel.submitClaim("The left ear cup has my initials inside.")
        advanceUntilIdle()

        assertTrue(viewModel.claimState.value is ClaimSubmissionUiState.Success)
        assertEquals("The left ear cup has my initials inside.", repository.lastProof)
    }

    @Test
    fun `received claims can be approved and list refreshes`() = runTest {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundClaimsViewModel(repository)
        advanceUntilIdle()
        viewModel.changeMode(ClaimsMode.RECEIVED)
        advanceUntilIdle()

        viewModel.decide(11, approve = true, note = "Proof verified")
        advanceUntilIdle()

        assertEquals(ClaimsMode.RECEIVED, viewModel.mode.value)
        assertEquals(11L, repository.lastDecisionClaimId)
        assertTrue(repository.lastDecisionApprove)
        assertTrue(viewModel.decisionState.value is ClaimDecisionUiState.Success)
    }

    private class FakeLostFoundDataSource : LostFoundDataSource {
        var searchCalls = 0
        var lastFilters = LostFoundSearchFilters()
        var lastCreateRequest: CreateLostFoundReportRequest? = null
        var lastImages = emptyList<UploadImage>()
        var lastProof = ""
        var lastDecisionClaimId = 0L
        var lastDecisionApprove = false

        override suspend fun searchReports(filters: LostFoundSearchFilters): PageResponse<LostFoundReport> {
            searchCalls++
            lastFilters = filters
            return PageResponse(listOf(REPORT), 0, 20, 1, 1, first = true, last = true)
        }

        override suspend fun getReport(reportId: Long) = REPORT

        override suspend fun createReport(
            request: CreateLostFoundReportRequest,
            images: List<UploadImage>,
        ): LostFoundReport {
            lastCreateRequest = request
            lastImages = images
            return REPORT.copy(reportType = request.reportType, createdByMe = true)
        }

        override suspend fun submitClaim(reportId: Long, proofDescription: String): LostFoundClaim {
            lastProof = proofDescription
            return CLAIM
        }

        override suspend fun getMyClaims() = listOf(CLAIM)

        override suspend fun getReceivedClaims() = listOf(CLAIM.copy(submittedByMe = false))

        override suspend fun decideClaim(
            claimId: Long,
            approve: Boolean,
            decisionNote: String,
        ): LostFoundClaim {
            lastDecisionClaimId = claimId
            lastDecisionApprove = approve
            return CLAIM.copy(status = if (approve) ClaimStatus.APPROVED else ClaimStatus.REJECTED)
        }
    }

    companion object {
        private val REPORT = LostFoundReport(
            id = 8,
            reportType = ReportType.FOUND,
            itemName = "Black headphones",
            category = ItemCategory.ELECTRONICS,
            description = "Black over-ear headphones with a small scratch.",
            colour = "black",
            location = "CLB",
            eventDate = "2026-08-15",
            timeDescription = "around 9 pm",
            status = ReportStatus.OPEN,
            createdByMe = false,
            createdAt = "2026-08-16T01:00:00Z",
            updatedAt = "2026-08-16T01:00:00Z",
        )
        private val CLAIM = LostFoundClaim(
            id = 11,
            report = ClaimReportSummary(8, "Black headphones", ItemCategory.ELECTRONICS, "CLB", ReportStatus.OPEN),
            proofDescription = "The left ear cup has my initials inside.",
            status = ClaimStatus.SUBMITTED,
            submittedByMe = true,
            createdAt = "2026-08-16T02:00:00Z",
            updatedAt = "2026-08-16T02:00:00Z",
        )
    }
}

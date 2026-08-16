package com.campuslink.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campuslink.mobile.core.model.ClaimReportSummary
import com.campuslink.mobile.core.model.ClaimStatus
import com.campuslink.mobile.core.model.CreateLostFoundReportRequest
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.LostFoundClaim
import com.campuslink.mobile.core.model.LostFoundImage
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.LostFoundSearchFilters
import com.campuslink.mobile.core.model.PageResponse
import com.campuslink.mobile.core.model.ReportStatus
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.core.model.UploadImage
import com.campuslink.mobile.lostfound.LostFoundDataSource
import com.campuslink.mobile.ui.lostfound.CREATE_REPORT_LIST_TAG
import com.campuslink.mobile.ui.lostfound.CreateLostFoundReportScreen
import com.campuslink.mobile.ui.lostfound.CreateLostFoundReportViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundBrowseScreen
import com.campuslink.mobile.ui.lostfound.LostFoundBrowseViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundClaimsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundClaimsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundHomeScreen
import com.campuslink.mobile.ui.lostfound.REPORT_DETAILS_LIST_TAG
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LostFoundUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun homeExposesBrowseReportAndClaimsActions() {
        var browsed = false
        rule.setContent {
            MaterialTheme {
                LostFoundHomeScreen(
                    onBack = {},
                    onBrowse = { browsed = true },
                    onCreate = {},
                    onClaims = {},
                )
            }
        }

        rule.onNodeWithText("Browse Reports").performClick()
        rule.onNodeWithText("Report Lost Item").assertIsDisplayed()
        rule.onNodeWithText("Report Found Item").assertIsDisplayed()
        rule.onNodeWithText("Claims").assertIsDisplayed()
        rule.runOnIdle { assertTrue(browsed) }
    }

    @Test
    fun browseCardShowsRealFieldsAndImageSemantics() {
        val viewModel = LostFoundBrowseViewModel(FakeLostFoundDataSource())
        rule.setContent {
            MaterialTheme { LostFoundBrowseScreen(viewModel, onBack = {}, onOpenReport = {}) }
        }

        rule.onNodeWithText("Black headphones").assertIsDisplayed()
        rule.onNodeWithText("OPEN").assertIsDisplayed()
        rule.onNodeWithContentDescription("Photo of Black headphones").assertExists()
    }

    @Test
    fun createReportUsesSharedScrollableForm() {
        val viewModel = CreateLostFoundReportViewModel(ReportType.LOST, FakeLostFoundDataSource())
        rule.setContent {
            MaterialTheme {
                CreateLostFoundReportScreen(
                    reportType = ReportType.LOST,
                    viewModel = viewModel,
                    onBack = {},
                    onCreated = {},
                )
            }
        }

        rule.onNodeWithText("Item details").assertIsDisplayed()
        rule.onNodeWithText("Item name*").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_REPORT_LIST_TAG).performScrollToNode(hasText("Publish report"))
        rule.onNodeWithText("Publish report").assertIsDisplayed()
    }

    @Test
    fun claimsCardShowsStatusAndRealProof() {
        val viewModel = LostFoundClaimsViewModel(FakeLostFoundDataSource())
        rule.setContent {
            MaterialTheme { LostFoundClaimsScreen(viewModel, onBack = {}, onOpenReport = {}) }
        }

        rule.onNodeWithText("SUBMITTED").assertIsDisplayed()
        rule.onNodeWithText("The left ear cup has my initials inside.").assertIsDisplayed()
        rule.onNodeWithText("View report").assertIsDisplayed()
    }

    @Test
    fun openFoundReportRequiresProofBeforeClaimWrite() {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundDetailsViewModel(8, repository)
        rule.setContent {
            MaterialTheme { LostFoundDetailsScreen(viewModel, onBack = {}) }
        }

        rule.onNodeWithTag(REPORT_DETAILS_LIST_TAG).performScrollToNode(hasText("Submit claim"))
        rule.onNodeWithText("Submit claim").performClick()
        rule.onNodeWithText("Submit ownership proof").assertIsDisplayed()
        rule.runOnIdle { assertTrue(repository.submittedProof == null) }
    }

    private class FakeLostFoundDataSource : LostFoundDataSource {
        var submittedProof: String? = null

        override suspend fun searchReports(filters: LostFoundSearchFilters) =
            PageResponse(listOf(REPORT_WITH_IMAGE), 0, 20, 1, 1, first = true, last = true)

        override suspend fun getReport(reportId: Long) = REPORT

        override suspend fun createReport(request: CreateLostFoundReportRequest, images: List<UploadImage>) = REPORT

        override suspend fun submitClaim(reportId: Long, proofDescription: String): LostFoundClaim {
            submittedProof = proofDescription
            return CLAIM
        }

        override suspend fun getMyClaims() = listOf(CLAIM)
        override suspend fun getReceivedClaims() = listOf(CLAIM)
        override suspend fun decideClaim(claimId: Long, approve: Boolean, decisionNote: String) = CLAIM
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
            status = ReportStatus.OPEN,
            createdByMe = false,
            createdAt = "2026-08-16T01:00:00Z",
            updatedAt = "2026-08-16T01:00:00Z",
        )
        private val REPORT_WITH_IMAGE = REPORT.copy(
            images = listOf(
                LostFoundImage(
                    id = 21,
                    url = "/api/lost-found/reports/8/images/21",
                    contentType = "image/jpeg",
                    fileSize = 512,
                    sortOrder = 0,
                ),
            ),
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

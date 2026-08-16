package com.campuslink.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundHomeScreen
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

        rule.onNodeWithText("Browse reports").performClick()
        rule.onNodeWithText("Report a lost item").assertIsDisplayed()
        rule.onNodeWithText("Report a found item").assertIsDisplayed()
        rule.onNodeWithText("Claims").assertIsDisplayed()
        rule.runOnIdle { assertTrue(browsed) }
    }

    @Test
    fun openFoundReportRequiresProofBeforeClaimWrite() {
        val repository = FakeLostFoundDataSource()
        val viewModel = LostFoundDetailsViewModel(8, repository)
        rule.setContent {
            MaterialTheme { LostFoundDetailsScreen(viewModel, onBack = {}) }
        }

        rule.onNodeWithText("Submit claim").performClick()
        rule.onNodeWithText("Submit ownership proof").assertIsDisplayed()
        rule.runOnIdle { assertTrue(repository.submittedProof == null) }
    }

    private class FakeLostFoundDataSource : LostFoundDataSource {
        var submittedProof: String? = null

        override suspend fun searchReports(filters: LostFoundSearchFilters) =
            PageResponse(listOf(REPORT), 0, 20, 1, 1, first = true, last = true)

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

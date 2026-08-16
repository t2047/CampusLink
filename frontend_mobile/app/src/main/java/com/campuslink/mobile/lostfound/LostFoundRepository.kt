package com.campuslink.mobile.lostfound

import com.campuslink.mobile.core.model.CreateLostFoundReportRequest
import com.campuslink.mobile.core.model.LostFoundClaim
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.LostFoundSearchFilters
import com.campuslink.mobile.core.model.PageResponse
import com.campuslink.mobile.core.model.UploadImage
import com.campuslink.mobile.core.network.LostFoundApi

interface LostFoundDataSource {
    suspend fun searchReports(filters: LostFoundSearchFilters): PageResponse<LostFoundReport>
    suspend fun getReport(reportId: Long): LostFoundReport
    suspend fun createReport(request: CreateLostFoundReportRequest, images: List<UploadImage>): LostFoundReport
    suspend fun submitClaim(reportId: Long, proofDescription: String): LostFoundClaim
    suspend fun getMyClaims(): List<LostFoundClaim>
    suspend fun getReceivedClaims(): List<LostFoundClaim>
    suspend fun decideClaim(claimId: Long, approve: Boolean, decisionNote: String): LostFoundClaim
}

class LostFoundRepository(private val api: LostFoundApi) : LostFoundDataSource {
    override suspend fun searchReports(filters: LostFoundSearchFilters) = api.searchReports(filters)

    override suspend fun getReport(reportId: Long) = api.getReport(reportId)

    override suspend fun createReport(request: CreateLostFoundReportRequest, images: List<UploadImage>) =
        api.createReport(request, images)

    override suspend fun submitClaim(reportId: Long, proofDescription: String) =
        api.submitClaim(reportId, proofDescription)

    override suspend fun getMyClaims() = api.getMyClaims()

    override suspend fun getReceivedClaims() = api.getReceivedClaims()

    override suspend fun decideClaim(claimId: Long, approve: Boolean, decisionNote: String) =
        api.decideClaim(claimId, approve, decisionNote)
}

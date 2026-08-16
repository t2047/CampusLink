package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.CreateLostFoundReportRequest
import com.campuslink.mobile.core.model.LostFoundClaim
import com.campuslink.mobile.core.model.LostFoundReport
import com.campuslink.mobile.core.model.LostFoundSearchFilters
import com.campuslink.mobile.core.model.PageResponse
import com.campuslink.mobile.core.model.UploadImage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class LostFoundApi(
    private val client: AuthenticatedHttpClient,
    private val json: Json,
) {
    suspend fun searchReports(filters: LostFoundSearchFilters): PageResponse<LostFoundReport> {
        val query = buildList {
            add("reportType" to filters.reportType.name)
            add("status" to filters.status.name)
            filters.keyword.trim().takeIf(String::isNotEmpty)?.let { add("keyword" to it) }
            filters.category?.let { add("category" to it.name) }
            filters.colour.trim().takeIf(String::isNotEmpty)?.let { add("colour" to it) }
            filters.location.trim().takeIf(String::isNotEmpty)?.let { add("location" to it) }
            filters.dateFrom.trim().takeIf(String::isNotEmpty)?.let { add("dateFrom" to it) }
            filters.dateTo.trim().takeIf(String::isNotEmpty)?.let { add("dateTo" to it) }
            filters.owner?.let { add("owner" to it) }
            add("page" to filters.page.toString())
            add("size" to filters.size.toString())
            add("sort" to "createdAt,desc")
        }
        return json.decodeFromString(
            PageResponse.serializer(LostFoundReport.serializer()),
            client.get(REPORTS_PATH, query),
        )
    }

    suspend fun getReport(reportId: Long): LostFoundReport = json.decodeFromString(
        LostFoundReport.serializer(),
        client.get("$REPORTS_PATH/$reportId"),
    )

    suspend fun createReport(
        request: CreateLostFoundReportRequest,
        images: List<UploadImage>,
    ): LostFoundReport {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "report",
                "report.json",
                json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE),
            )
        images.forEach { image ->
            multipart.addFormDataPart(
                "images",
                image.fileName,
                image.bytes.toRequestBody(image.contentType.toMediaType()),
            )
        }
        return json.decodeFromString(
            LostFoundReport.serializer(),
            client.postMultipart(REPORTS_PATH, multipart.build()),
        )
    }

    suspend fun submitClaim(reportId: Long, proofDescription: String): LostFoundClaim =
        json.decodeFromString(
            LostFoundClaim.serializer(),
            client.post(
                "$REPORTS_PATH/$reportId/claims",
                json.encodeToString(ProofRequest(proofDescription)),
            ),
        )

    suspend fun getMyClaims(): List<LostFoundClaim> = json.decodeFromString(
        ListSerializer(LostFoundClaim.serializer()),
        client.get("$CLAIMS_PATH/mine"),
    )

    suspend fun getReceivedClaims(): List<LostFoundClaim> = json.decodeFromString(
        ListSerializer(LostFoundClaim.serializer()),
        client.get("$CLAIMS_PATH/received"),
    )

    suspend fun decideClaim(claimId: Long, approve: Boolean, decisionNote: String): LostFoundClaim =
        json.decodeFromString(
            LostFoundClaim.serializer(),
            client.post(
                "$CLAIMS_PATH/$claimId/${if (approve) "approve" else "reject"}",
                json.encodeToString(DecisionRequest(decisionNote)),
            ),
        )

    @kotlinx.serialization.Serializable
    private data class ProofRequest(val proofDescription: String)

    @kotlinx.serialization.Serializable
    private data class DecisionRequest(val decisionNote: String)

    companion object {
        private const val REPORTS_PATH = "api/lost-found/reports"
        private const val CLAIMS_PATH = "api/lost-found/claims"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

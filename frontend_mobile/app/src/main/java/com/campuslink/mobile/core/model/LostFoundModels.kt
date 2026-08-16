package com.campuslink.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReportType {
    LOST,
    FOUND,
}

@Serializable
enum class ReportStatus {
    OPEN,
    CLAIMED,
    CLOSED,
}

@Serializable
enum class ClaimStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
}

@Serializable
enum class ItemCategory {
    ELECTRONICS,
    CLOTHING,
    BOOKS_STATIONERY,
    KEYS,
    WALLET_PURSE,
    ID_CARD,
    BAG,
    UMBRELLA,
    OTHER,
}

@Serializable
data class LostFoundImage(
    val id: Long,
    val url: String,
    val contentType: String,
    val fileSize: Long,
    val sortOrder: Int,
)

@Serializable
data class LostFoundReport(
    val id: Long,
    val reportType: ReportType,
    val itemName: String,
    val category: ItemCategory,
    val description: String,
    val colour: String? = null,
    val location: String,
    val eventDate: String,
    val timeDescription: String? = null,
    val status: ReportStatus,
    val images: List<LostFoundImage> = emptyList(),
    val createdByMe: Boolean,
    val adminHidden: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val first: Boolean,
    val last: Boolean,
)

@Serializable
data class ClaimReportSummary(
    val id: Long,
    val itemName: String,
    val category: ItemCategory,
    val location: String,
    val status: ReportStatus,
)

@Serializable
data class LostFoundClaim(
    val id: Long,
    val report: ClaimReportSummary,
    val proofDescription: String,
    val status: ClaimStatus,
    val decisionNote: String? = null,
    val submittedByMe: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateLostFoundReportRequest(
    val reportType: ReportType,
    val itemName: String,
    val category: ItemCategory,
    val description: String,
    val colour: String? = null,
    val location: String,
    val eventDate: String,
    val timeDescription: String? = null,
)

data class LostFoundSearchFilters(
    val reportType: ReportType = ReportType.FOUND,
    val keyword: String = "",
    val category: ItemCategory? = null,
    val colour: String = "",
    val location: String = "",
    val dateFrom: String = "",
    val dateTo: String = "",
    val status: ReportStatus = ReportStatus.OPEN,
    val owner: String? = null,
    val page: Int = 0,
    val size: Int = 20,
)

data class UploadImage(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

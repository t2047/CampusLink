package com.campuslink.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AuthResponse(val token: String, val email: String, val role: String)

@Serializable
data class AuthRequest(val email: String, val password: String)

data class AuthSession(val token: String, val email: String, val role: String)

enum class MessageRole { USER, ASSISTANT }

enum class MessageStatus { COMPLETE, STREAMING, FAILED, INTERRUPTED }

@Serializable
data class AgentStep(
    val agent: String? = null,
    val tool: String? = null,
    val label: String,
    val status: String,
)

@Serializable
data class MatchResult(
    @SerialName("item_id") val itemId: String = "",
    @SerialName("report_type") val reportType: String = "",
    @SerialName("item_name") val itemName: String = "",
    val category: String = "",
    val description: String = "",
    val colour: String? = null,
    val location: String = "",
    @SerialName("event_date") val eventDate: String = "",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("match_score") val matchScore: Double = 0.0,
    @SerialName("match_reason") val matchReason: List<String> = emptyList(),
    @SerialName("score_breakdown") val scoreBreakdown: Map<String, Double> = emptyMap(),
    @SerialName("matching_mode") val matchingMode: String? = null,
)

@Serializable
data class PendingConfirmation(
    val agent: String,
    val details: JsonObject = JsonObject(emptyMap()),
    val message: String? = null,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val timestamp: Long,
    val steps: List<AgentStep> = emptyList(),
    val matches: List<MatchResult> = emptyList(),
)

data class Conversation(
    val id: String,
    val ownerEmail: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingConfirmation: PendingConfirmation? = null,
)

data class SseEvent(val type: String, val data: JsonObject)

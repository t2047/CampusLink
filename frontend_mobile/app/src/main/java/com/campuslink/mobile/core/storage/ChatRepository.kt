package com.campuslink.mobile.core.storage

import com.campuslink.mobile.core.model.AgentStep
import com.campuslink.mobile.core.model.ChatMessage
import com.campuslink.mobile.core.model.Conversation
import com.campuslink.mobile.core.model.MatchResult
import com.campuslink.mobile.core.model.MessageRole
import com.campuslink.mobile.core.model.MessageStatus
import com.campuslink.mobile.core.model.PendingConfirmation
import com.campuslink.mobile.core.model.SseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class RetryTurn(val message: String, val assistantId: String)

interface ChatPersistence {
    fun messages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun conversation(id: String): Conversation?
    suspend fun beginTurn(conversationId: String, text: String): String
    suspend fun beginAssistant(conversationId: String): String
    suspend fun beginRetry(conversationId: String, failedAssistantId: String): RetryTurn
    suspend fun applyEvent(conversationId: String, assistantId: String, event: SseEvent): PendingConfirmation?
    suspend fun clearConfirmation(conversationId: String)
    suspend fun interrupt(assistantId: String)
}

class ChatRepository(private val dao: ChatDao, private val json: Json) : ChatPersistence {
    fun conversations(email: String): Flow<List<Conversation>> = dao.conversations(email).map { rows ->
        rows.map(::conversationFromEntity)
    }

    override fun messages(conversationId: String): Flow<List<ChatMessage>> = dao.messages(conversationId).map { rows ->
        rows.map(::messageFromEntity)
    }

    override suspend fun conversation(id: String): Conversation? = dao.conversation(id)?.let(::conversationFromEntity)

    suspend fun createConversation(email: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsertConversation(ConversationEntity(id, email, "New conversation", now, now))
        return id
    }

    suspend fun deleteConversation(id: String, email: String) = dao.deleteConversation(id, email)

    suspend fun clearForUser(email: String) = dao.clearForUser(email)

    override suspend fun beginTurn(conversationId: String, text: String): String {
        val now = System.currentTimeMillis()
        val conversation = requireNotNull(dao.conversation(conversationId))
        if (conversation.title == "New conversation") {
            dao.upsertConversation(conversation.copy(title = text.take(48), updatedAt = now))
        } else {
            dao.updateConversationState(conversationId, now, conversation.pendingConfirmationJson)
        }
        dao.upsertMessage(
            MessageEntity(UUID.randomUUID().toString(), conversationId, "USER", text, "COMPLETE", now),
        )
        val assistantId = UUID.randomUUID().toString()
        dao.upsertMessage(
            MessageEntity(assistantId, conversationId, "ASSISTANT", "", "STREAMING", now + 1),
        )
        return assistantId
    }

    override suspend fun beginAssistant(conversationId: String): String {
        val id = UUID.randomUUID().toString()
        dao.upsertMessage(
            MessageEntity(id, conversationId, "ASSISTANT", "", "STREAMING", System.currentTimeMillis()),
        )
        return id
    }

    override suspend fun beginRetry(conversationId: String, failedAssistantId: String): RetryTurn {
        val failed = requireNotNull(dao.message(failedAssistantId))
        require(failed.conversationId == conversationId && failed.role == "ASSISTANT")
        require(failed.status == "FAILED" || failed.status == "INTERRUPTED")
        val original = requireNotNull(dao.precedingUserMessage(conversationId, failed.timestamp))
        return RetryTurn(original.content, beginAssistant(conversationId))
    }

    override suspend fun applyEvent(
        conversationId: String,
        assistantId: String,
        event: SseEvent,
    ): PendingConfirmation? {
        val current = dao.message(assistantId) ?: return null
        var content = current.content
        var status = current.status
        val steps = decodeSteps(current.stepsJson).toMutableList()
        var matches = decodeMatches(current.matchesJson)
        var confirmation: PendingConfirmation? = null

        when (event.type) {
            "token" -> content += event.data["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "intent_detected" -> {
                val targets = (event.data["targets"] as? JsonArray)
                    ?.joinToString(", ") { it.jsonPrimitive.content }
                    .orEmpty()
                if (targets.isNotBlank()) steps += AgentStep(label = "🎯 $targets", status = "ok")
            }
            "agent_start" -> steps += AgentStep(
                agent = event.string("agent") ?: "Agent",
                label = "${event.string("agent") ?: "Agent"} processing…",
                status = "running",
            )
            "agent_step" -> steps += AgentStep(
                agent = event.string("agent"),
                label = event.string("action") ?: "Action",
                status = event.string("status") ?: "ok",
            )
            "agent_done" -> steps += AgentStep(
                agent = event.string("agent"),
                label = "${event.string("agent") ?: "Agent"} completed",
                status = "ok",
            )
            "agent_error" -> steps += AgentStep(
                agent = event.string("agent"),
                label = event.string("message") ?: "Agent failed",
                status = "error",
            )
            "utility_start" -> steps += AgentStep(
                tool = event.string("tool"),
                label = "${event.string("tool") ?: "Tool"} running…",
                status = "running",
            )
            "utility_result", "utility_done" -> steps += AgentStep(
                tool = event.string("tool"),
                label = "${event.string("tool") ?: "Tool"} completed",
                status = "ok",
            )
            "match_results" -> matches = event.data["items"]?.jsonArray?.mapNotNull {
                runCatching { json.decodeFromJsonElement<MatchResult>(it) }.getOrNull()
            }.orEmpty()
            "confirm_required" -> {
                confirmation = PendingConfirmation(
                    agent = event.string("agent").orEmpty(),
                    details = event.data["details"] as? kotlinx.serialization.json.JsonObject
                        ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                    message = event.string("message"),
                )
            }
            "message" -> content += event.string("content") ?: event.string("message")
                ?: event.string("raw").orEmpty()
            "error" -> {
                content += "\n[Error] ${event.string("message") ?: "Unknown error"}"
                status = "FAILED"
            }
            "done" -> if (status == "STREAMING") status = "COMPLETE"
        }

        dao.updateMessage(current.copy(
            content = content,
            status = status,
            stepsJson = json.encodeToString(steps),
            matchesJson = json.encodeToString(matches),
        ))
        if (confirmation != null) {
            dao.updateConversationState(conversationId, System.currentTimeMillis(), json.encodeToString(confirmation))
        }
        return confirmation
    }

    override suspend fun clearConfirmation(conversationId: String) {
        dao.updateConversationState(conversationId, System.currentTimeMillis(), null)
    }

    override suspend fun interrupt(assistantId: String) {
        val current = dao.message(assistantId) ?: return
        if (current.status == "STREAMING") dao.updateMessage(current.copy(status = "INTERRUPTED"))
    }

    suspend fun markInterruptedStreams() = dao.markInterruptedStreams()

    private fun conversationFromEntity(value: ConversationEntity) = Conversation(
        value.id,
        value.ownerEmail,
        value.title,
        value.createdAt,
        value.updatedAt,
        value.pendingConfirmationJson?.let { runCatching { json.decodeFromString<PendingConfirmation>(it) }.getOrNull() },
    )

    private fun messageFromEntity(value: MessageEntity) = ChatMessage(
        value.id,
        value.conversationId,
        MessageRole.valueOf(value.role),
        value.content,
        MessageStatus.valueOf(value.status),
        value.timestamp,
        decodeSteps(value.stepsJson),
        decodeMatches(value.matchesJson),
    )

    private fun decodeSteps(raw: String): List<AgentStep> = runCatching {
        json.decodeFromString(ListSerializer(AgentStep.serializer()), raw)
    }.getOrDefault(emptyList())

    private fun decodeMatches(raw: String): List<MatchResult> = runCatching {
        json.decodeFromString(ListSerializer(MatchResult.serializer()), raw)
    }.getOrDefault(emptyList())

    private fun SseEvent.string(key: String): String? = data[key]?.jsonPrimitive?.contentOrNull
}

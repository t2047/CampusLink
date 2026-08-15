package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SseParser(
    private val json: Json,
    private val onEvent: (SseEvent) -> Unit,
) {
    private var buffer = ""
    private var terminalEventSeen = false

    fun feed(chunk: String) {
        buffer += chunk
        // 保留分片末尾的单个 \r；下一分片可能以 \n 开始，不能提前制造空事件边界。
        buffer = buffer.replace("\r\n", "\n")
        while (true) {
            val separator = buffer.indexOf("\n\n")
            if (separator < 0) return
            val block = buffer.substring(0, separator)
            buffer = buffer.substring(separator + 2)
            parseBlock(block)?.let(::emit)
        }
    }

    fun finish() {
        buffer = buffer.replace('\r', '\n')
        if (buffer.isNotBlank()) parseBlock(buffer)?.let(::emit)
        buffer = ""
        if (!terminalEventSeen) emit(SseEvent("done", JsonObject(emptyMap())))
    }

    private fun emit(event: SseEvent) {
        if (event.type == "done" || event.type == "error") terminalEventSeen = true
        onEvent(event)
    }

    private fun parseBlock(block: String): SseEvent? {
        var eventType = "message"
        val dataLines = mutableListOf<String>()
        block.lineSequence().forEach { line ->
            when {
                line.isBlank() || line.startsWith(':') -> Unit
                line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                line.startsWith("data:") -> dataLines += line.substringAfter("data:").removePrefix(" ")
            }
        }
        if (dataLines.isEmpty() && eventType != "done") return null
        val safeType = if (eventType in VALID_TYPES) eventType else "message"
        val raw = dataLines.joinToString("\n")
        val data = if (raw.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: buildJsonObject { put("raw", JsonPrimitive(raw)) }
        }
        return SseEvent(safeType, data)
    }

    companion object {
        val VALID_TYPES = setOf(
            "intent_detected", "token", "agent_start", "agent_step", "agent_done",
            "agent_error", "match_results", "utility_start", "utility_result", "utility_done",
            "confirm_required", "error", "done", "message",
        )
    }
}

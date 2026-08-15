package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses fragmented CRLF and multiline data`() {
        val events = mutableListOf<SseEvent>()
        val parser = SseParser(json, events::add)

        parser.feed("event: token\r")
        parser.feed("\ndata: {\"content\":\"你")
        parser.feed("好\"}\r\n\r\n")
        parser.finish()

        assertEquals("token", events[0].type)
        assertEquals("你好", events[0].data["content"]?.jsonPrimitive?.content)
        assertEquals("done", events.last().type)
    }

    @Test
    fun `unknown event falls back to message and keeps malformed payload`() {
        val events = mutableListOf<SseEvent>()
        val parser = SseParser(json, events::add)

        parser.feed("event: future_event\ndata: not-json\n\n")
        parser.finish()

        assertEquals("message", events.first().type)
        assertEquals("not-json", events.first().data["raw"]?.jsonPrimitive?.content)
    }

    @Test
    fun `does not append duplicate done`() {
        val events = mutableListOf<SseEvent>()
        val parser = SseParser(json, events::add)

        parser.feed("event: done\n\n")
        parser.finish()

        assertEquals(listOf("done"), events.map { it.type })
    }

    @Test
    fun `ignores comments and joins data lines`() {
        val events = mutableListOf<SseEvent>()
        val parser = SseParser(json, events::add)

        parser.feed(": heartbeat\nevent: message\ndata: first\ndata: second\n\n")

        assertTrue(events.first().data["raw"]?.jsonPrimitive?.content?.contains("first\nsecond") == true)
    }
}

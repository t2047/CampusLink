package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.SseEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import java.io.IOException

interface ChatStreamClient {
    fun stream(message: String, sessionId: String, traceId: String): Flow<SseEvent>
    fun resume(sessionId: String, approved: Boolean, traceId: String): Flow<SseEvent>
}

class ChatSseClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) : ChatStreamClient {
    override fun stream(message: String, sessionId: String, traceId: String): Flow<SseEvent> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/chat/stream")
            .addQueryParameter("message", message)
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("traceId", traceId)
            .build()
        return execute(Request.Builder().url(url).get().build())
    }

    override fun resume(sessionId: String, approved: Boolean, traceId: String): Flow<SseEvent> {
        val body = json.encodeToString(buildJsonObject {
            put("sessionId", sessionId)
            put("approved", approved)
        }).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${baseUrl}api/chat/resume?traceId=$traceId")
            .post(body)
            .build()
        return execute(request)
    }

    private fun execute(baseRequest: Request): Flow<SseEvent> = callbackFlow {
        val token = tokenProvider()
        if (token == null) {
            close(ApiException(401, "Not authenticated"))
            return@callbackFlow
        }
        val request = baseRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) close(e) else close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        if (response.code == 401) onUnauthorized()
                        trySendBlocking(SseEvent("error", buildJsonObject {
                            put("message", "HTTP ${response.code}")
                            put("status", response.code)
                        }))
                        close()
                        return
                    }
                    try {
                        val parser = SseParser(json) { trySendBlocking(it) }
                        val source = response.body.source()
                        val sink = Buffer()
                        while (!source.exhausted() && !call.isCanceled() && !parser.isTerminal) {
                            val read = source.read(sink, 8192)
                            if (read <= 0) break
                            parser.feed(sink.readUtf8())
                        }
                        if (!call.isCanceled() && !parser.isTerminal && !parser.finish()) {
                            close(IOException("Chat stream ended before a terminal event"))
                            return
                        }
                        close()
                    } catch (exception: IOException) {
                        if (!call.isCanceled()) close(exception) else close()
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

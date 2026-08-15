package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.SseEvent
import com.campuslink.mobile.core.security.SessionStore
import kotlinx.coroutines.channels.awaitClose
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

class ChatSseClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val sessionStore: SessionStore,
    private val json: Json,
) {
    fun stream(message: String, sessionId: String, traceId: String): Flow<SseEvent> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/chat/stream")
            .addQueryParameter("message", message)
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("traceId", traceId)
            .build()
        return execute(Request.Builder().url(url).get().build())
    }

    fun resume(sessionId: String, approved: Boolean, traceId: String): Flow<SseEvent> {
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
        val token = sessionStore.session.value?.token
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
            override fun onFailure(call: Call, exception: IOException) {
                if (!call.isCanceled()) close(exception) else close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        if (response.code == 401) sessionStore.clear()
                        trySend(SseEvent("error", buildJsonObject {
                            put("message", "HTTP ${response.code}")
                            put("status", response.code)
                        }))
                        close()
                        return
                    }
                    val parser = SseParser(json) { trySend(it) }
                    val source = response.body.source()
                    val sink = Buffer()
                    while (!source.exhausted() && !call.isCanceled()) {
                        val read = source.read(sink, 8192)
                        if (read <= 0) break
                        parser.feed(sink.readUtf8())
                    }
                    if (!call.isCanceled()) parser.finish()
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

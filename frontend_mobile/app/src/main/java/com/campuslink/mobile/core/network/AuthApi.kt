package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.AuthRequest
import com.campuslink.mobile.core.model.AuthResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
) {
    suspend fun login(email: String, password: String): AuthResponse = authenticate("login", email, password)

    suspend fun register(email: String, password: String): AuthResponse = authenticate("register", email, password)

    private suspend fun authenticate(path: String, email: String, password: String): AuthResponse =
        suspendCancellableCoroutine { continuation ->
            val body = json.encodeToString(AuthRequest(email.trim(), password))
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("${baseUrl}api/auth/$path")
                .post(body)
                .header("Accept", "application/json")
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, exception: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = response.body.string()
                        if (!response.isSuccessful) {
                            val message = runCatching {
                                json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                            }.getOrNull() ?: "HTTP ${response.code}"
                            if (continuation.isActive) {
                                continuation.resumeWithException(ApiException(response.code, message))
                            }
                            return
                        }
                        runCatching { json.decodeFromString<AuthResponse>(text) }
                            .onSuccess { if (continuation.isActive) continuation.resume(it) }
                            .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                    }
                }
            })
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

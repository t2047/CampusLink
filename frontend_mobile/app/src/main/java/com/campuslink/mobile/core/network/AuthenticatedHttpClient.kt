package com.campuslink.mobile.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthenticatedHttpClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) {
    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): String =
        execute("GET", path, query, jsonBody = null)

    suspend fun post(path: String, jsonBody: String): String = execute("POST", path, emptyList(), jsonBody)

    suspend fun postMultipart(path: String, body: RequestBody): String =
        execute("POST", path, emptyList(), body)

    suspend fun patch(path: String, jsonBody: String? = null): String = execute("PATCH", path, emptyList(), jsonBody)

    private suspend fun execute(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        jsonBody: String?,
    ): String = execute(
        method = method,
        path = path,
        query = query,
        body = jsonBody?.toRequestBody(JSON_MEDIA_TYPE),
    )

    private suspend fun execute(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
    ): String {
        val token = tokenProvider()?.takeIf(String::isNotBlank)
            ?: throw ApiException(401, "Not authenticated")
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegments(path.trim('/'))
        query.forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requireNotNull(body))
            "PATCH" -> requestBuilder.patch(body ?: "".toRequestBody(JSON_MEDIA_TYPE))
            else -> error("Unsupported HTTP method: $method")
        }
        return await(requestBuilder.build())
    }

    private suspend fun await(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        if (response.code == 401) onUnauthorized()
                        if (continuation.isActive) {
                            val backendError = parseError(body, response.code)
                            continuation.resumeWithException(
                                ApiException(
                                    response.code,
                                    backendError.message,
                                    backendError.code,
                                    backendError.validationErrors,
                                ),
                            )
                        }
                        return
                    }
                    if (continuation.isActive) continuation.resume(body)
                }
            }
        })
    }

    private fun parseError(body: String, statusCode: Int): BackendError = runCatching {
        val value = json.parseToJsonElement(body).jsonObject
        val code = value["code"]?.jsonPrimitive?.contentOrNull
        val validationErrors = value["errors"]?.jsonObject?.mapNotNull { (field, message) ->
            message.jsonPrimitive.contentOrNull?.let { field to it }
        }?.toMap().orEmpty()
        val validationMessage = validationErrors.values.joinToString(" ").ifBlank { null }
        val message = value["error"]?.jsonPrimitive?.contentOrNull
            ?: value["message"]?.jsonPrimitive?.contentOrNull
            ?: validationMessage
            ?: code
            ?: "HTTP $statusCode"
        BackendError(message, code, validationErrors)
    }.getOrNull() ?: BackendError("HTTP $statusCode", null, emptyMap())

    private data class BackendError(
        val message: String,
        val code: String?,
        val validationErrors: Map<String, String>,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

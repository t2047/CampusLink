package com.campuslink.mobile.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class HttpClients(val rest: OkHttpClient, val sse: OkHttpClient)

object HttpClientFactory {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val REST_READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    fun create(): HttpClients {
        val rest = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REST_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val sse = rest.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        return HttpClients(rest, sse)
    }
}

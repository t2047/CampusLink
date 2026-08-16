package com.campuslink.mobile.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AuthApi(OkHttpClient(), server.url("/").toString(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login decodes session and uses expected endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"jwt","email":"student@nus.edu.sg","role":"STUDENT"}"""))

        val response = api.login("student@nus.edu.sg", "secret")

        assertEquals("jwt", response.token)
        assertEquals("/api/auth/login", server.takeRequest().path)
    }

    @Test
    fun `server error becomes ApiException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden"}"""))

        val failure = runCatching { api.login("student@nus.edu.sg", "wrong") }.exceptionOrNull()

        assertEquals(403, (failure as ApiException).statusCode)
    }
}

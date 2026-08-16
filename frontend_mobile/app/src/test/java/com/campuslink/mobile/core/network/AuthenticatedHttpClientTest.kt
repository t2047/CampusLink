package com.campuslink.mobile.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AuthenticatedHttpClientTest {
    private lateinit var server: MockWebServer
    private var token: String? = "test-jwt"
    private var unauthorized = false
    private lateinit var client: AuthenticatedHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AuthenticatedHttpClient(
            OkHttpClient(),
            server.url("/").toString(),
            Json { ignoreUnknownKeys = true },
            tokenProvider = { token },
            onUnauthorized = { unauthorized = true },
        )
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { Unit }

    @Test
    fun `adds bearer token and supports json methods`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        client.get("api/facilities/spaces", listOf("equipment" to "TV"))
        client.patch("api/example", "{\"status\":\"OK\"}")

        val get = server.takeRequest()
        assertEquals("Bearer test-jwt", get.getHeader("Authorization"))
        assertEquals("/api/facilities/spaces?equipment=TV", get.path)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("application/json; charset=utf-8", patch.getHeader("Content-Type"))
    }

    @Test
    fun `missing session fails before sending request`() = runTest {
        token = null

        val failure = runCatching { client.get("api/facilities/spaces") }.exceptionOrNull()

        assertEquals(401, (failure as ApiException).statusCode)
        assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `401 clears session and maps backend error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))

        val failure = runCatching { client.get("api/facilities/spaces") }.exceptionOrNull()

        assertTrue(unauthorized)
        assertEquals(401, (failure as ApiException).statusCode)
        assertEquals("Unauthorized", failure.message)
    }

    @Test
    fun `network failure remains an IOException`() = runTest {
        server.shutdown()

        val failure = runCatching { client.get("api/facilities/spaces") }.exceptionOrNull()

        assertTrue(failure is IOException)
    }
}

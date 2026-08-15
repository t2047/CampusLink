package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.SpaceSearchFilters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FacilitiesApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: FacilitiesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val authenticated = AuthenticatedHttpClient(
            OkHttpClient(), server.url("/").toString(), json, { "jwt" }, {},
        )
        api = FacilitiesApi(authenticated, json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `search serializes supported filters and parses spaces`() = runTest {
        server.enqueue(MockResponse().setBody("[$SPACE_JSON]"))

        val result = api.searchSpaces(
            SpaceSearchFilters(
                query = "project",
                building = "COM3",
                spaceType = "PROJECT_ROOM",
                minimumCapacity = 4,
                equipment = listOf("TV", "Whiteboard"),
                startDateTime = "2026-08-17T09:00:00",
                endDateTime = "2026-08-17T11:00:00",
            ),
        )

        assertEquals("COM3-01-20 Project Room", result.single().name)
        val url = server.takeRequest().requestUrl!!
        assertEquals("project", url.queryParameter("query"))
        assertEquals("4", url.queryParameter("minimumCapacity"))
        assertEquals(listOf("TV", "Whiteboard"), url.queryParameterValues("equipment"))
        assertEquals("2026-08-17T09:00:00", url.queryParameter("startDateTime"))
        assertFalse(url.toString().contains("Z"))
        assertFalse(url.toString().contains("userId"))
    }

    @Test
    fun `gets space details from expected endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(SPACE_JSON))

        val result = api.getSpace(4)

        assertEquals(4L, result.spaceId)
        assertEquals("/api/facilities/spaces/4", server.takeRequest().path)
    }

    @Test
    fun `availability sends local datetime parameters and parses response`() = runTest {
        val responseBody = """
            {
              "available": true,
              "reasonCode": null,
              "space": $SPACE_JSON,
              "startDateTime": "2026-08-17T09:00:00",
              "endDateTime": "2026-08-17T11:00:00"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(responseBody))

        val result = api.checkAvailability(4, "2026-08-17T09:00:00", "2026-08-17T11:00:00")

        assertTrue(result.available)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/facilities/spaces/4/availability", url.encodedPath)
        assertEquals("2026-08-17T11:00:00", url.queryParameter("endDateTime"))
    }

    @Test
    fun `backend error remains ApiException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"code":"SPACE_NOT_FOUND","error":"Space not found"}"""),
        )

        val failure = runCatching { api.getSpace(999) }.exceptionOrNull()

        assertEquals(404, (failure as ApiException).statusCode)
        assertEquals("Space not found", failure.message)
    }

    companion object {
        private val SPACE_JSON = """
            {
              "spaceId": 4,
              "name": "COM3-01-20 Project Room",
              "building": "COM3",
              "floor": "01",
              "roomNumber": "01-20",
              "spaceType": "PROJECT_ROOM",
              "capacity": 6,
              "equipment": ["TV", "Whiteboard"],
              "openingTime": "08:00:00",
              "closingTime": "22:00:00",
              "status": "AVAILABLE"
            }
        """.trimIndent()
    }
}

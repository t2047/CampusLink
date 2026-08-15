package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.model.BookingStatus
import com.campuslink.mobile.core.model.CreateBookingRequest
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

    @Test
    fun `create booking posts exact json and parses response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(BOOKING_JSON))

        val result = api.createBooking(
            CreateBookingRequest(4, "2026-08-17T09:00:00", "2026-08-17T11:00:00"),
        )

        assertEquals(72L, result.bookingId)
        assertEquals(BookingStatus.CONFIRMED, result.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/facilities/bookings", request.path)
        assertEquals("Bearer jwt", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"spaceId\":4"))
        assertTrue(body.contains("\"startDateTime\":\"2026-08-17T09:00:00\""))
        assertFalse(body.contains("Z"))
        assertFalse(body.contains("userId"))
    }

    @Test
    fun `create booking preserves conflict error code`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"code":"BOOKING_CONFLICT","error":"The requested time overlaps"}"""),
        )

        val failure = runCatching {
            api.createBooking(CreateBookingRequest(4, "2026-08-17T09:00:00", "2026-08-17T11:00:00"))
        }.exceptionOrNull() as ApiException

        assertEquals(409, failure.statusCode)
        assertEquals("BOOKING_CONFLICT", failure.errorCode)
    }

    @Test
    fun `create booking maps validation field errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"errors":{"startDateTime":"must not be null"}}"""),
        )

        val failure = runCatching {
            api.createBooking(CreateBookingRequest(4, "", "2026-08-17T11:00:00"))
        }.exceptionOrNull() as ApiException

        assertEquals(400, failure.statusCode)
        assertEquals("must not be null", failure.message)
    }

    @Test
    fun `list bookings gets authenticated array`() = runTest {
        server.enqueue(MockResponse().setBody("[$BOOKING_JSON]"))

        val result = api.listBookings()

        assertEquals(72L, result.single().bookingId)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/facilities/bookings", request.path)
        assertEquals("Bearer jwt", request.getHeader("Authorization"))
    }

    @Test
    fun `booking details uses id and maps not found`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"BOOKING_NOT_FOUND"}"""))

        val failure = runCatching { api.getBookingDetails(999) }.exceptionOrNull() as ApiException

        assertEquals("/api/facilities/bookings/999", server.takeRequest().path)
        assertEquals(404, failure.statusCode)
        assertEquals("BOOKING_NOT_FOUND", failure.errorCode)
    }

    @Test
    fun `cancel booking patches exact endpoint and parses cancelled response`() = runTest {
        server.enqueue(MockResponse().setBody(BOOKING_JSON.replace("CONFIRMED", "CANCELLED")))

        val result = api.cancelBooking(72)

        assertEquals(BookingStatus.CANCELLED, result.status)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/facilities/bookings/72/cancel", request.path)
    }

    @Test
    fun `cancel booking maps conflict and not found`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"code":"BOOKING_CANCELLATION_NOT_ALLOWED","error":"Cannot cancel"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"BOOKING_NOT_FOUND"}"""))

        val conflict = runCatching { api.cancelBooking(72) }.exceptionOrNull() as ApiException
        val missing = runCatching { api.cancelBooking(999) }.exceptionOrNull() as ApiException

        assertEquals(409, conflict.statusCode)
        assertEquals("BOOKING_CANCELLATION_NOT_ALLOWED", conflict.errorCode)
        assertEquals(404, missing.statusCode)
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

        private val BOOKING_JSON = """
            {
              "success": true,
              "bookingId": 72,
              "space": $SPACE_JSON,
              "startDateTime": "2026-08-17T09:00:00",
              "endDateTime": "2026-08-17T11:00:00",
              "status": "CONFIRMED",
              "createdAt": "2026-08-16T10:00:00",
              "updatedAt": "2026-08-16T10:00:00"
            }
        """.trimIndent()
    }
}

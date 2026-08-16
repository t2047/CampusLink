package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.CreateLostFoundReportRequest
import com.campuslink.mobile.core.model.ItemCategory
import com.campuslink.mobile.core.model.LostFoundSearchFilters
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.core.model.UploadImage
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

class LostFoundApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LostFoundApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val client = AuthenticatedHttpClient(OkHttpClient(), server.url("/").toString(), json, { "jwt" }, {})
        api = LostFoundApi(client, json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `search sends supported filters and parses page`() = runTest {
        server.enqueue(MockResponse().setBody(PAGE_JSON))

        val page = api.searchReports(
            LostFoundSearchFilters(
                reportType = ReportType.FOUND,
                keyword = "headphones",
                category = ItemCategory.ELECTRONICS,
                colour = "black",
                location = "CLB",
                dateFrom = "2026-08-01",
                dateTo = "2026-08-16",
            ),
        )

        assertEquals("Black headphones", page.content.single().itemName)
        val request = server.takeRequest()
        assertEquals("Bearer jwt", request.getHeader("Authorization"))
        val url = request.requestUrl!!
        assertEquals("FOUND", url.queryParameter("reportType"))
        assertEquals("OPEN", url.queryParameter("status"))
        assertEquals("ELECTRONICS", url.queryParameter("category"))
        assertEquals("createdAt,desc", url.queryParameter("sort"))
        assertFalse(url.toString().contains("userId"))
    }

    @Test
    fun `create sends report json and images as multipart`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(REPORT_JSON))

        val result = api.createReport(
            CreateLostFoundReportRequest(
                ReportType.LOST,
                "Black headphones",
                ItemCategory.ELECTRONICS,
                "Black over-ear headphones with a small scratch.",
                "black",
                "CLB",
                "2026-08-15",
                "around 9 pm",
            ),
            listOf(UploadImage("headphones.png", "image/png", byteArrayOf(1, 2, 3))),
        )

        assertEquals(8L, result.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/lost-found/reports", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"report\""))
        assertTrue(body.contains("\"reportType\":\"LOST\""))
        assertTrue(body.contains("filename=\"headphones.png\""))
    }

    @Test
    fun `details uses report id endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(REPORT_JSON))

        val result = api.getReport(8)

        assertEquals(8L, result.id)
        assertEquals("/api/lost-found/reports/8", server.takeRequest().path)
    }

    @Test
    fun `claim workflow uses exact endpoints and request bodies`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(CLAIM_JSON))
        server.enqueue(MockResponse().setBody(CLAIM_JSON.replace("SUBMITTED", "APPROVED")))

        val claim = api.submitClaim(8, "The left ear cup has my initials inside.")
        val approved = api.decideClaim(11, approve = true, decisionNote = "Proof verified")

        assertEquals(11L, claim.id)
        assertEquals("APPROVED", approved.status.name)
        val submitRequest = server.takeRequest()
        assertEquals("/api/lost-found/reports/8/claims", submitRequest.path)
        assertTrue(submitRequest.body.readUtf8().contains("proofDescription"))
        val approveRequest = server.takeRequest()
        assertEquals("/api/lost-found/claims/11/approve", approveRequest.path)
        assertTrue(approveRequest.body.readUtf8().contains("Proof verified"))
    }

    @Test
    fun `claims lists parse mine and received arrays`() = runTest {
        server.enqueue(MockResponse().setBody("[$CLAIM_JSON]"))
        server.enqueue(MockResponse().setBody("[$CLAIM_JSON]"))

        assertEquals(1, api.getMyClaims().size)
        assertEquals(1, api.getReceivedClaims().size)
        assertEquals("/api/lost-found/claims/mine", server.takeRequest().path)
        assertEquals("/api/lost-found/claims/received", server.takeRequest().path)
    }

    companion object {
        private val REPORT_JSON = """
            {
              "id": 8,
              "reportType": "LOST",
              "itemName": "Black headphones",
              "category": "ELECTRONICS",
              "description": "Black over-ear headphones with a small scratch.",
              "colour": "black",
              "location": "CLB",
              "eventDate": "2026-08-15",
              "timeDescription": "around 9 pm",
              "status": "OPEN",
              "images": [{"id":3,"url":"/api/lost-found/images/3","contentType":"image/png","fileSize":3,"sortOrder":0}],
              "createdByMe": true,
              "adminHidden": false,
              "createdAt": "2026-08-16T01:00:00Z",
              "updatedAt": "2026-08-16T01:00:00Z"
            }
        """.trimIndent()

        private val PAGE_JSON = """
            {"content":[$REPORT_JSON],"page":0,"size":20,"totalElements":1,"totalPages":1,"first":true,"last":true}
        """.trimIndent().replace("\"reportType\": \"LOST\"", "\"reportType\": \"FOUND\"")

        private val CLAIM_JSON = """
            {
              "id": 11,
              "report":{"id":8,"itemName":"Black headphones","category":"ELECTRONICS","location":"CLB","status":"OPEN"},
              "proofDescription":"The left ear cup has my initials inside.",
              "status":"SUBMITTED",
              "decisionNote":null,
              "submittedByMe":true,
              "createdAt":"2026-08-16T02:00:00Z",
              "updatedAt":"2026-08-16T02:00:00Z"
            }
        """.trimIndent()
    }
}

package com.campuslink.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientFactoryTest {
    @Test
    fun `REST is finite while SSE read timeout is unlimited`() {
        val clients = HttpClientFactory.create()

        assertTrue(clients.rest.readTimeoutMillis > 0)
        assertEquals(30_000, clients.rest.readTimeoutMillis)
        assertEquals(0, clients.sse.readTimeoutMillis)
        assertEquals(15_000, clients.rest.connectTimeoutMillis)
        assertEquals(30_000, clients.rest.writeTimeoutMillis)
        assertSame(clients.rest.connectionPool, clients.sse.connectionPool)
        assertSame(clients.rest.dispatcher, clients.sse.dispatcher)
    }
}

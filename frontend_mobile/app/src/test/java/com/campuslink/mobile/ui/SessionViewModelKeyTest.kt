package com.campuslink.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionViewModelKeyTest {
    @Test
    fun sessionViewModelKey_isolatedByAccount() {
        val reporterKey = sessionViewModelKey("lost-found-details-12", "reporter@example.com")
        val claimantKey = sessionViewModelKey("lost-found-details-12", "claimant@example.com")

        assertEquals("lost-found-details-12-reporter@example.com", reporterKey)
        assertNotEquals(reporterKey, claimantKey)
    }
}

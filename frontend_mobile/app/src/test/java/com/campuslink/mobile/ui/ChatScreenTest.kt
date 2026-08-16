package com.campuslink.mobile.ui

import com.campuslink.mobile.core.model.PendingConfirmation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatScreenTest {
    @Test
    fun `confirmation displays readable message without internal json`() {
        val pending = PendingConfirmation(
            agent = "lost-found-agent",
            message = "White shoes lost at CLB last night.",
            details = buildJsonObject {
                put("confirmation_id", "secret-confirmation-id")
                put("action", "report_lost")
                put("summary", "Internal summary")
                put("expires_at", "2026-08-16T02:06:10Z")
            },
        )

        val result = confirmationDisplayText(pending, "Please confirm this action")

        assertEquals("White shoes lost at CLB last night.", result)
        assertFalse(result.contains("confirmation_id"))
        assertFalse(result.contains("secret-confirmation-id"))
    }

    @Test
    fun `confirmation falls back to business summary`() {
        val pending = PendingConfirmation(
            agent = "lost-found-agent",
            details = buildJsonObject {
                put("confirmation_id", "secret-confirmation-id")
                put("summary", "Black shoes; category CLOTHING; location CLB")
            },
        )

        assertEquals(
            "Black shoes; category CLOTHING; location CLB",
            confirmationDisplayText(pending, "Please confirm this action"),
        )
    }

    @Test
    fun `confirmation uses safe fallback when readable text is missing`() {
        val pending = PendingConfirmation(
            agent = "lost-found-agent",
            details = buildJsonObject { put("confirmation_id", "secret-confirmation-id") },
        )

        assertEquals(
            "Please confirm this action",
            confirmationDisplayText(pending, "Please confirm this action"),
        )
    }
}

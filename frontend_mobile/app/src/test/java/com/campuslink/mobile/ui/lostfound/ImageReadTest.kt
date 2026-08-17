package com.campuslink.mobile.ui.lostfound

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class ImageReadTest {
    @Test
    fun readImageBytes_acceptsContentAtLimit() {
        val bytes = ByteArray(1_024) { it.toByte() }

        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readImageBytes(maxBytes = bytes.size))
    }

    @Test
    fun readImageBytes_rejectsContentOverLimit() {
        val bytes = ByteArray(1_025)

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(bytes).readImageBytes(maxBytes = 1_024)
        }
    }
}

package com.paddle.ocr.postprocess

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CTCDecoderTest {
    @Test
    fun `repeated labels collapse but a blank permits the same character again`() {
        val output = floatArrayOf(
            0.05f, 0.90f, 0.05f,
            0.10f, 0.85f, 0.05f,
            0.95f, 0.03f, 0.02f,
            0.04f, 0.80f, 0.16f,
            0.02f, 0.03f, 0.95f,
        )
        val result = CTCDecoder.decode(output, longArrayOf(1, 5, 3), listOf("가", "나")).single()
        assertEquals("가가나", result.first)
        assertEquals((0.90f + 0.80f + 0.95f) / 3, result.second, 0.00001f)
    }

    @Test
    fun `dictionary mismatch fails before silent text corruption`() {
        assertThrows(IllegalArgumentException::class.java) {
            CTCDecoder.decode(floatArrayOf(0.1f, 0.2f, 0.7f), longArrayOf(1, 1, 3), listOf("a"))
        }
    }

    @Test
    fun `malformed tensor size fails clearly`() {
        assertThrows(IllegalArgumentException::class.java) {
            CTCDecoder.decode(floatArrayOf(0.1f), longArrayOf(1, 1, 2), listOf("a"))
        }
    }

    @Test
    fun `blank only output is empty with zero confidence`() {
        assertEquals("" to 0f, CTCDecoder.decode(floatArrayOf(0.9f, 0.1f), longArrayOf(1, 1, 2), listOf("a")).single())
    }
}

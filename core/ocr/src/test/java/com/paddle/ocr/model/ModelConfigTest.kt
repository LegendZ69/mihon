package com.paddle.ocr.model

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ModelConfigTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `pinned YAML dictionary preserves punctuation and multilingual characters`() {
        val file = temporary.resolve("inference.yml").toFile()
        file.writeText(
            """
            Global:
              model_name: fixture
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - ''''
              - '"'
              - 가
              - 語
              - ' '
            """.trimIndent(),
        )
        val result = ModelConfig.parse(mockk<Context>(), file.absolutePath)
        assertEquals(listOf("!", "'", "\"", "가", "語", " "), result.characterList)
    }
}

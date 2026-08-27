package com.chatbuddy.ai.embedding

import java.io.ByteArrayInputStream
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniLmWordPieceTokenizerTest {
    @Test
    fun longInputIsSplitWithoutDroppingWordPieceTail() {
        val windows = tokenizer()
            .encodeWindows("hello world hello world hello", maxLength = 5)
            .toList()

        assertEquals(2, windows.size)
        assertEquals(listOf(2L, 4L, 5L, 4L, 3L), windows[0].inputIds.toList())
        assertEquals(listOf(2L, 5L, 4L, 3L, 0L), windows[1].inputIds.toList())
        assertEquals(5, windows[0].activeTokenCount)
        assertEquals(4, windows[1].activeTokenCount)
    }

    @Test
    fun singleWindowEncodeFailsInsteadOfSilentlyTruncating() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer().encode("hello world hello world", maxLength = 5)
        }
    }

    @Test
    fun weightedWindowAggregationIsDeterministicAndNormalized() {
        val aggregator = MiniLmSubwindowAggregator(dimensions = 2)
        aggregator.add(floatArrayOf(1f, 0f), activeTokenCount = 2)
        aggregator.add(floatArrayOf(0f, 1f), activeTokenCount = 1)

        val result = aggregator.finish()

        assertEquals(2f / sqrt(5f), result[0], 0.00001f)
        assertEquals(1f / sqrt(5f), result[1], 0.00001f)
        assertTrue(result.all { it.isFinite() })
    }

    private fun tokenizer(): MiniLmWordPieceTokenizer =
        MiniLmWordPieceTokenizer.fromVocab(
            ByteArrayInputStream(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\nhello\nworld\n".toByteArray()
            )
        )
}

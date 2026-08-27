package com.chatbuddy.ai.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class MiniLmSubwindowAggregatorTest {
    @Test
    fun weightedMeanUsesActiveTokenCountsAndNormalizes() {
        val aggregator = MiniLmSubwindowAggregator(dimensions = 2)

        aggregator.add(floatArrayOf(1f, 0f), activeTokenCount = 3)
        aggregator.add(floatArrayOf(0f, 1f), activeTokenCount = 1)

        val result = aggregator.finish()

        assertEquals(0.9486833f, result[0], 0.0001f)
        assertEquals(0.31622776f, result[1], 0.0001f)
        assertEquals(1f, sqrt(result.sumOf { it.toDouble() * it.toDouble() }).toFloat(), 0.0001f)
        assertTrue(result.all(Float::isFinite))
    }
}

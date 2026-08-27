package com.chatbuddy.ai.embedding

import kotlin.math.sqrt

/**
 * Combines mean-pooled MiniLM windows without retaining model outputs.
 * Weighting by each window's active-token count gives a deterministic
 * weighted mean before the final sentence-transformer normalization.
 */
internal class MiniLmSubwindowAggregator(
    private val dimensions: Int = EMBEDDING_DIMENSIONS
) {
    private val weightedSum = FloatArray(dimensions)
    private var totalWeight = 0f

    fun add(windowEmbedding: FloatArray, activeTokenCount: Int) {
        require(windowEmbedding.size == dimensions) {
            "Embedding must have $dimensions dimensions"
        }
        require(activeTokenCount > 0) { "Window must contain active tokens" }
        val weight = activeTokenCount.toFloat()
        windowEmbedding.forEachIndexed { index, value ->
            check(value.isFinite()) { "Embedding contains a non-finite value" }
            weightedSum[index] += value * weight
        }
        totalWeight += weight
    }

    fun finish(): FloatArray {
        check(totalWeight > 0f) { "At least one window is required" }
        val result = FloatArray(dimensions) { index -> weightedSum[index] / totalWeight }
        var squaredNorm = 0.0
        result.forEach { value -> squaredNorm += value.toDouble() * value.toDouble() }
        val norm = sqrt(squaredNorm).toFloat()
        check(norm.isFinite() && norm > NORMALIZATION_EPSILON) {
            "Embedding has zero or non-finite norm"
        }
        result.indices.forEach { index -> result[index] /= norm }
        return result
    }

    private companion object {
        const val EMBEDDING_DIMENSIONS = 384
        const val NORMALIZATION_EPSILON = 1e-12f
    }
}

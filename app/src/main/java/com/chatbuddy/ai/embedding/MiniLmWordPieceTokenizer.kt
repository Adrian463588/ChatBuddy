package com.chatbuddy.ai.embedding

import java.io.InputStream
import java.util.Locale

data class MiniLmEncodedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
)

class MiniLmWordPieceTokenizer private constructor(
    private val vocabulary: Map<String, Int>
) {
    fun encode(text: String, maxLength: Int = 256): MiniLmEncodedInput {
        require(maxLength >= 3) { "maxLength must allow special tokens" }
        val tokens = mutableListOf<Int>()
        tokens += vocabulary.getValue("[CLS]")
        BASIC_TOKEN_PATTERN.findAll(text.lowercase(Locale.US)).forEach { match ->
            if (tokens.size >= maxLength - 1) return@forEach
            tokens += wordPiece(match.value)
                .take(maxLength - 1 - tokens.size)
        }
        tokens += vocabulary.getValue("[SEP]")
        val ids = LongArray(maxLength)
        val mask = LongArray(maxLength)
        val types = LongArray(maxLength)
        tokens.forEachIndexed { index, id -> ids[index] = id.toLong(); mask[index] = 1L }
        return MiniLmEncodedInput(ids, mask, types)
    }

    private fun wordPiece(token: String): List<Int> {
        if (token.length > MAX_WORD_LENGTH) return listOf(unknownId)
        val pieces = mutableListOf<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: Int? = null
            while (start < end) {
                val candidate = token.substring(start, end).let { if (start == 0) it else "##$it" }
                val id = vocabulary[candidate]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found == null) return listOf(unknownId)
            pieces += found
            start = end
        }
        return pieces
    }

    private val unknownId: Int get() = vocabulary["[UNK]"] ?: 100

    companion object {
        private const val MAX_WORD_LENGTH = 100
        private val BASIC_TOKEN_PATTERN = Regex("\\p{L}+|\\p{N}+|[^\\p{L}\\p{N}\\s]")

        fun fromVocab(input: InputStream): MiniLmWordPieceTokenizer {
            val vocabulary = LinkedHashMap<String, Int>()
            input.bufferedReader().useLines { lines -> lines.forEachIndexed { index, token -> vocabulary[token] = index } }
            require("[CLS]" in vocabulary && "[SEP]" in vocabulary) { "MiniLM vocabulary is incomplete" }
            return MiniLmWordPieceTokenizer(vocabulary)
        }
    }
}

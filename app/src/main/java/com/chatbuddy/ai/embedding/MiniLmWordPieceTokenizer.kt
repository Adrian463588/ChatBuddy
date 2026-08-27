package com.chatbuddy.ai.embedding

import java.io.InputStream
import java.util.Locale

data class MiniLmEncodedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    val activeTokenCount: Int
        get() = attentionMask.count { it == 1L }
}

class MiniLmWordPieceTokenizer private constructor(
    private val vocabulary: Map<String, Int>
) {
    /**
     * Encodes only inputs that fit in one model window. Call [encodeWindows]
     * for document chunks; it never drops the tail of the input.
     */
    fun encode(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): MiniLmEncodedInput {
        val windows = encodeWindows(text, maxLength).toList()
        require(windows.size == 1) {
            "Input exceeds one MiniLM window; use encodeWindows for long text"
        }
        return windows.single()
    }

    /**
     * Splits WordPiece ids at model payload boundaries. Every window gets its
     * own special tokens and padding, making the result deterministic and
     * lossless with respect to the tokenizer output.
     */
    fun encodeWindows(
        text: String,
        maxLength: Int = DEFAULT_MAX_LENGTH
    ): Sequence<MiniLmEncodedInput> {
        require(maxLength >= 3) { "maxLength must allow special tokens" }
        val wordPieceIds = tokenize(text)
        val payloadSize = maxLength - SPECIAL_TOKEN_COUNT
        return sequence {
            if (wordPieceIds.isEmpty()) {
                yield(encodeWindow(emptyList(), maxLength))
            } else {
                var start = 0
                while (start < wordPieceIds.size) {
                    val end = (start + payloadSize).coerceAtMost(wordPieceIds.size)
                    yield(encodeWindow(wordPieceIds.subList(start, end), maxLength))
                    start = end
                }
            }
        }
    }

    private fun tokenize(text: String): List<Int> = buildList {
        BASIC_TOKEN_PATTERN.findAll(text.lowercase(Locale.US)).forEach { match ->
            addAll(wordPiece(match.value))
        }
    }

    private fun encodeWindow(payload: List<Int>, maxLength: Int): MiniLmEncodedInput {
        val ids = LongArray(maxLength) { vocabulary["[PAD]"].orZero().toLong() }
        val mask = LongArray(maxLength)
        val types = LongArray(maxLength)
        var index = 0
        ids[index] = vocabulary.getValue("[CLS]").toLong()
        mask[index++] = 1L
        payload.forEach { tokenId ->
            ids[index] = tokenId.toLong()
            mask[index++] = 1L
        }
        ids[index] = vocabulary.getValue("[SEP]").toLong()
        mask[index] = 1L
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

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        const val DEFAULT_MAX_LENGTH = 256
        private const val SPECIAL_TOKEN_COUNT = 2
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

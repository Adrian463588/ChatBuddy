package com.chatbuddy.domain.usecase

import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.DocumentId
import javax.inject.Inject

class ChunkDocumentUseCase @Inject constructor() {
    operator fun invoke(
        documentId: DocumentId,
        tokens: Sequence<String>,
        chunkSize: Int = 512,
        overlap: Int = 50
    ): Sequence<DocumentChunk> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap in 0 until chunkSize) { "overlap must be smaller than chunkSize" }

        return sequence {
            val iterator = tokens.iterator()
            val buffer = ArrayList<String>(chunkSize)
            var startToken = 0
            var ordinal = 0

            while (iterator.hasNext() || buffer.isNotEmpty()) {
                while (buffer.size < chunkSize && iterator.hasNext()) {
                    buffer += iterator.next()
                }
                if (buffer.isEmpty()) break

                val endTokenExclusive = startToken + buffer.size
                yield(
                    DocumentChunk(
                        documentId = documentId,
                        ordinal = ordinal++,
                        text = buffer.joinToString(separator = " "),
                        startToken = startToken,
                        endTokenExclusive = endTokenExclusive
                    )
                )

                if (!iterator.hasNext()) break

                val tail = buffer.takeLast(overlap)
                buffer.clear()
                buffer += tail
                startToken = endTokenExclusive - tail.size
            }
        }
    }
}

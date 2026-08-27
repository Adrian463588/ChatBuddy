package com.chatbuddy.domain.usecase

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Evidence
import javax.inject.Inject

data class RagContext(val text: String, val evidence: List<Evidence>)

/** Marks an expected retrieval miss so the caller may explicitly opt into web fallback. */
class NoRelevantEvidenceException : IllegalStateException("No relevant document evidence was found")

/** A local context budget failure is an infrastructure/data error, not a retrieval miss. */
class RagContextBudgetExceededException : IllegalStateException(
    "Retrieved evidence exceeded the local context budget"
)

class BuildRagContextUseCase @Inject constructor() {
    operator fun invoke(
        evidence: List<Evidence>,
        minimumScore: Float = 0.35f,
        limit: Int = 5,
        maxCharacters: Int = 9_000
    ): AppResult<RagContext> {
        require(limit > 0) { "limit must be positive" }
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        val relevant = evidence
            .asSequence()
            .filter { it.score >= minimumScore }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()

        if (relevant.isEmpty()) {
            return AppResult.Error(
                message = "No relevant document evidence was found",
                cause = NoRelevantEvidenceException()
            )
        }

        val selected = buildList<Pair<Evidence, String>> {
            var usedCharacters = 0
            relevant.forEach { item ->
                val sourceId = "local:${item.documentId.value}:${item.chunkOrdinal}"
                val header = "[$sourceId] ${item.documentName}#${item.chunkOrdinal}\n"
                val separatorLength = if (isEmpty()) 0 else 2
                val remaining = maxCharacters - usedCharacters - separatorLength - header.length
                if (remaining <= 0) return@forEach
                val excerpt = item.text.take(remaining)
                if (excerpt.isBlank()) return@forEach
                add(item to (header + excerpt))
                usedCharacters += separatorLength + header.length + excerpt.length
            }
        }
        if (selected.isEmpty()) {
            return AppResult.Error(
                message = "Retrieved evidence exceeded the local context budget",
                cause = RagContextBudgetExceededException()
            )
        }
        return AppResult.Success(
            RagContext(
                text = selected.joinToString(separator = "\n\n") { it.second },
                evidence = selected.map { it.first }
            )
        )
    }
}

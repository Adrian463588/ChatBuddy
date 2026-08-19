package com.chatbuddy.domain.usecase

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Evidence
import javax.inject.Inject

data class RagContext(val text: String, val evidence: List<Evidence>)

class BuildRagContextUseCase @Inject constructor() {
    operator fun invoke(
        evidence: List<Evidence>,
        minimumScore: Float = 0.35f,
        limit: Int = 5
    ): AppResult<RagContext> {
        require(limit > 0) { "limit must be positive" }
        val relevant = evidence
            .asSequence()
            .filter { it.score >= minimumScore }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()

        if (relevant.isEmpty()) {
            return AppResult.Error("No relevant document evidence was found")
        }

        val context = relevant.mapIndexed { index, item ->
            "[${index + 1}] ${item.documentName}#${item.chunkOrdinal}\n${item.text}"
        }.joinToString(separator = "\n\n")
        return AppResult.Success(RagContext(context, relevant))
    }
}

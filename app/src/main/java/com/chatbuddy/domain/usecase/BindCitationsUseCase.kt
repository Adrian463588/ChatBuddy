package com.chatbuddy.domain.usecase

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ChatCitation
import java.util.regex.Pattern
import javax.inject.Inject

data class BoundCitationAnswer(
    val text: String,
    val citations: List<ChatCitation>
)

class BindCitationsUseCase @Inject constructor() {
    operator fun invoke(
        answer: String,
        citations: List<ChatCitation>
    ): AppResult<BoundCitationAnswer> {
        if (citations.isEmpty()) return AppResult.Success(BoundCitationAnswer(answer, emptyList()))
        val usable = citations.filter { it.sourceId.isNotBlank() }
        if (usable.isEmpty()) {
            return AppResult.Error("Retrieved evidence has no stable citation identifiers")
        }

        val markers = CITATION_PATTERN.matcher(answer)
        val referenced = linkedSetOf<String>()
        while (markers.find()) markers.group(1)?.let { referenced += it }
        if (referenced.isEmpty()) {
            return AppResult.Error("Local answer did not include a verifiable citation")
        }
        val validIds = usable.mapTo(hashSetOf()) { it.sourceId }
        if (!referenced.all(validIds::contains)) {
            return AppResult.Error("Local answer referenced an unknown citation")
        }
        return AppResult.Success(
            BoundCitationAnswer(
                text = answer,
                citations = usable.filter { it.sourceId in referenced }
            )
        )
    }

    companion object {
        private val CITATION_PATTERN = Pattern.compile("\\[([A-Za-z0-9_:-]+)]")
    }
}

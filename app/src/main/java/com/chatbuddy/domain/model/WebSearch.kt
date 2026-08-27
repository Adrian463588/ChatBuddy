package com.chatbuddy.domain.model

data class WebEvidence(
    val title: String,
    val url: String,
    val excerpt: String,
    val content: String,
    val provider: String,
    val sourceId: String = "",
    val retrievedAtEpochMs: Long = 0L
) {
    fun asCitation(): ChatCitation = ChatCitation(
        kind = ChatCitationKind.WEB,
        title = title,
        uri = url,
        excerpt = excerpt,
        provider = provider,
        sourceId = sourceId.ifBlank { url },
        retrievedAtEpochMs = retrievedAtEpochMs
    )
}

data class WebSearchRequest(
    val query: String,
    val limit: Int = 3
)

enum class WebProviderId {
    MEDIAWIKI,
    BRAVE
}

enum class WebProviderAvailability {
    AVAILABLE,
    NOT_CONFIGURED,
    OFFLINE,
    UNAVAILABLE
}

data class WebProviderStatus(
    val provider: WebProviderId,
    val availability: WebProviderAvailability,
    val detail: String? = null
)

data class WebProviderSettings(
    val braveApiKeyConfigured: Boolean = false
)

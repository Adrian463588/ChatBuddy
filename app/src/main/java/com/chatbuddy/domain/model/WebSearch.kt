package com.chatbuddy.domain.model

data class WebEvidence(
    val title: String,
    val url: String,
    val excerpt: String,
    val content: String,
    val provider: String
) {
    fun asCitation(): ChatCitation = ChatCitation(
        kind = ChatCitationKind.WEB,
        title = title,
        uri = url,
        excerpt = excerpt,
        provider = provider
    )
}

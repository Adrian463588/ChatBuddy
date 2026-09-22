package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebEvidence
import com.chatbuddy.domain.model.WebProviderAvailability
import com.chatbuddy.domain.model.WebProviderId
import com.chatbuddy.domain.model.WebProviderStatus
import com.chatbuddy.domain.model.WebSearchRequest
import com.chatbuddy.domain.repository.OfficialWebSearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfficialWebSearchRepositoryImpl @Inject constructor(
    private val mediaWiki: MediaWikiWebSearchRepository,
    private val bing: BingWebSearchRepository,
    private val brave: BraveWebSearchRepository
) : OfficialWebSearchRepository {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<List<WebProviderStatus>> = _status.asStateFlow()

    override suspend fun search(request: WebSearchRequest): AppResult<List<WebEvidence>> {
        // 1. If Brave is explicitly configured by the user, try it first
        val braveStatus = brave.status()
        if (braveStatus.availability == WebProviderAvailability.AVAILABLE) {
            when (val braveResult = brave.search(request)) {
                is AppResult.Success -> {
                    if (braveResult.data.isNotEmpty()) return braveResult
                }
                is AppResult.Error -> Unit
                AppResult.Loading -> Unit
            }
        }

        // 2. Query Wikipedia (ID + EN) for reliable, encyclopedic facts
        val wiki = mediaWiki.search(request.query, request.limit)
        if (wiki is AppResult.Success && wiki.data.isNotEmpty()) {
            _status.value = _status.value.replaceStatus(
                WebProviderStatus(
                    WebProviderId.MEDIAWIKI,
                    WebProviderAvailability.AVAILABLE,
                    "Wikipedia verified knowledge"
                )
            )
            return wiki
        }

        // 3. Fallback to web search via Bing scraper, verifying relevance
        val bingResult = bing.search(request.query, request.limit)
        if (bingResult is AppResult.Success && bingResult.data.isNotEmpty()) {
            val queryWords = request.query.lowercase().split(" ", "?", "!", ",", ".")
                .map { it.trim() }
                .filter { it.length >= 3 }
            val relevant = bingResult.data.filter { item ->
                val text = (item.title + " " + item.content).lowercase()
                queryWords.isEmpty() || queryWords.any { text.contains(it) }
            }
            if (relevant.isNotEmpty()) {
                _status.value = _status.value.replaceStatus(
                    WebProviderStatus(
                        WebProviderId.MEDIAWIKI,
                        WebProviderAvailability.AVAILABLE,
                        "Web search & deep scraping active"
                    )
                )
                return AppResult.Success(relevant)
            }
        }

        if (wiki is AppResult.Error) {
            _status.value = _status.value.replaceStatus(
                WebProviderStatus(
                    WebProviderId.MEDIAWIKI,
                    if (wiki.cause is IOException) {
                        WebProviderAvailability.OFFLINE
                    } else {
                        WebProviderAvailability.UNAVAILABLE
                    },
                    wiki.message
                )
            )
        }

        return if (wiki is AppResult.Success) wiki else bingResult
    }

    private fun List<WebProviderStatus>.replaceStatus(status: WebProviderStatus): List<WebProviderStatus> =
        map { if (it.provider == status.provider) status else it }

    companion object {
        private fun initialStatus() = listOf(
            WebProviderStatus(
                WebProviderId.MEDIAWIKI,
                WebProviderAvailability.AVAILABLE,
                "Web search and Wikipedia active"
            ),
            WebProviderStatus(
                WebProviderId.BRAVE,
                WebProviderAvailability.NOT_CONFIGURED,
                "Optional provider; add a key in Settings"
            )
        )
    }
}

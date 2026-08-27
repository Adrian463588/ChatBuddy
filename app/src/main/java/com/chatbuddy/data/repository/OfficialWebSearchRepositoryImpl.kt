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
    private val brave: BraveWebSearchRepository
) : OfficialWebSearchRepository {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<List<WebProviderStatus>> = _status.asStateFlow()

    override suspend fun search(request: WebSearchRequest): AppResult<List<WebEvidence>> {
        val wiki = mediaWiki.search(request.query, request.limit)
        when (wiki) {
            is AppResult.Success -> {
                _status.value = _status.value.replaceStatus(
                    WebProviderStatus(
                        WebProviderId.MEDIAWIKI,
                        WebProviderAvailability.AVAILABLE,
                        "Wikipedia official API"
                    )
                )
                if (wiki.data.isNotEmpty()) return wiki
            }
            is AppResult.Error -> {
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
            AppResult.Loading -> Unit
        }

        val braveStatus = brave.status()
        _status.value = _status.value.replaceStatus(braveStatus)
        if (braveStatus.availability != WebProviderAvailability.AVAILABLE) return wiki

        return when (val braveResult = brave.search(request)) {
            is AppResult.Success -> {
                _status.value = _status.value.replaceStatus(
                    braveStatus.copy(
                        availability = WebProviderAvailability.AVAILABLE,
                        detail = "Official Brave Search API"
                    )
                )
                if (braveResult.data.isNotEmpty()) braveResult else wiki
            }
            is AppResult.Error -> {
                _status.value = _status.value.replaceStatus(
                    braveStatus.copy(
                        availability = if (braveResult.cause is IOException) {
                            WebProviderAvailability.OFFLINE
                        } else {
                            WebProviderAvailability.UNAVAILABLE
                        },
                        detail = braveResult.message
                    )
                )
                if (wiki is AppResult.Error) braveResult else wiki
            }
            AppResult.Loading -> wiki
        }
    }

    private fun List<WebProviderStatus>.replaceStatus(status: WebProviderStatus): List<WebProviderStatus> =
        map { if (it.provider == status.provider) status else it }

    companion object {
        private fun initialStatus() = listOf(
            WebProviderStatus(
                WebProviderId.MEDIAWIKI,
                WebProviderAvailability.AVAILABLE,
                "Wikipedia official API"
            ),
            WebProviderStatus(
                WebProviderId.BRAVE,
                WebProviderAvailability.NOT_CONFIGURED,
                "Optional provider; add a key in Settings"
            )
        )
    }
}

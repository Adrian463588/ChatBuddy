package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebEvidence
import com.chatbuddy.domain.model.WebProviderSettings
import com.chatbuddy.domain.model.WebProviderStatus
import com.chatbuddy.domain.model.WebSearchRequest
import kotlinx.coroutines.flow.StateFlow

interface OfficialWebSearchRepository : WebSearchRepository {
    suspend fun search(request: WebSearchRequest): AppResult<List<WebEvidence>>
    val status: StateFlow<List<WebProviderStatus>>

    override suspend fun search(query: String, limit: Int): AppResult<List<WebEvidence>> =
        search(WebSearchRequest(query = query, limit = limit))
}

interface WebProviderSettingsRepository {
    val settings: StateFlow<WebProviderSettings>
    suspend fun saveBraveApiKey(apiKey: String): AppResult<Unit>
    suspend fun clearBraveApiKey(): AppResult<Unit>
}

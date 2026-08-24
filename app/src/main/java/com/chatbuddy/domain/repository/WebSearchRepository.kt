package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebEvidence

interface WebSearchRepository {
    suspend fun search(query: String, limit: Int = 3): AppResult<List<WebEvidence>>
}

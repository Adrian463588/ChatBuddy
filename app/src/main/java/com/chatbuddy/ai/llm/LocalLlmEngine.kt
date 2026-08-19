package com.chatbuddy.ai.llm

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.Flow

interface LocalLlmEngine {
    fun stream(request: ChatRequest, context: String?): Flow<ChatStreamEvent>
    suspend fun verifyRuntime(): AppResult<Unit>
}

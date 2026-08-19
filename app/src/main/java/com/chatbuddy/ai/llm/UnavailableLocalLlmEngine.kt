package com.chatbuddy.ai.llm

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableLocalLlmEngine @Inject constructor() : LocalLlmEngine {
    override fun stream(request: ChatRequest, context: String?): Flow<ChatStreamEvent> = flowOf(
        ChatStreamEvent.Failed("llama.cpp JNI runtime is unavailable; no answer was generated")
    )

    override suspend fun verifyRuntime(): AppResult<Unit> =
        AppResult.Error("llama.cpp JNI runtime is not packaged")
}

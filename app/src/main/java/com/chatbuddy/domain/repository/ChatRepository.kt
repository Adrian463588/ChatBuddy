package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun stream(request: ChatRequest): Flow<ChatStreamEvent>
}

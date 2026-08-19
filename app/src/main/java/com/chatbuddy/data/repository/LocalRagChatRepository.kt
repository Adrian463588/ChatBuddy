package com.chatbuddy.data.repository

import com.chatbuddy.ai.llm.LocalLlmEngine
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import com.chatbuddy.domain.repository.ChatRepository
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.repository.VectorStoreRepository
import com.chatbuddy.domain.usecase.BuildRagContextUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRagChatRepository @Inject constructor(
    private val embedding: EmbeddingRepository,
    private val vectors: VectorStoreRepository,
    private val buildContext: BuildRagContextUseCase,
    private val llm: LocalLlmEngine
) : ChatRepository {
    override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Started)
        var context: String? = null
        var evidence = emptyList<com.chatbuddy.domain.model.Evidence>()
        if (request.useRag) {
            val queryEmbedding = when (val result = embedding.embed(request.text)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> {
                    emit(ChatStreamEvent.Failed("RAG is unavailable: ${result.message}"))
                    return@flow
                }
                AppResult.Loading -> {
                    emit(ChatStreamEvent.Failed("RAG embedding is still loading"))
                    return@flow
                }
            }
            val matches = when (val result = vectors.search(queryEmbedding, limit = 5)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> {
                    emit(ChatStreamEvent.Failed("RAG retrieval is unavailable: ${result.message}"))
                    return@flow
                }
                AppResult.Loading -> {
                    emit(ChatStreamEvent.Failed("RAG retrieval is still loading"))
                    return@flow
                }
            }
            val ragContext = when (val result = buildContext(matches)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> {
                    emit(ChatStreamEvent.Failed("No relevant document evidence was found; answer withheld"))
                    return@flow
                }
                AppResult.Loading -> {
                    emit(ChatStreamEvent.Failed("RAG context is still loading"))
                    return@flow
                }
            }
            context = ragContext.text
            evidence = ragContext.evidence
            emit(ChatStreamEvent.EvidenceFound(evidence))
        }
        llm.stream(request, context).collect { event ->
            emit(
                if (event is ChatStreamEvent.Completed) {
                    event.copy(message = event.message.copy(id = UUID.randomUUID().toString(), citations = evidence))
                } else event
            )
        }
    }
}

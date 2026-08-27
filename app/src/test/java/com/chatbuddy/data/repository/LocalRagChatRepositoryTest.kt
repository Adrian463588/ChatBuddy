package com.chatbuddy.data.repository

import com.chatbuddy.ai.llm.LocalLlmEngine
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.Evidence
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.model.WebEvidence
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.repository.VectorStoreRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRagChatRepositoryTest {
    @Test
    fun localEvidenceWinsAndDoesNotCallWeb() = runTest {
        val web = RecordingWebSearchRepository(
            AppResult.Success(listOf(webEvidence()))
        )
        val llm = RecordingLlm()
        val repository = repository(
            vectors = StaticVectorStore(
                AppResult.Success(
                    listOf(Evidence(DocumentId("doc"), "notes.txt", 0, "local answer", 0.9f))
                )
            ),
            web = web,
            llm = llm
        )

        val events = repository.stream(request(allowWebFallback = true)).toList()
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()

        assertEquals(0, web.calls)
        assertFalse(events.contains(ChatStreamEvent.WebSearchStarted))
        assertEquals("Local document", completed.message.citations.single().provider)
        assertTrue(llm.lastContext.orEmpty().contains("local answer"))
    }

    @Test
    fun webFallbackRunsOnlyAfterLocalEvidenceMiss() = runTest {
        val web = RecordingWebSearchRepository(
            AppResult.Success(listOf(webEvidence()))
        )
        val llm = RecordingLlm()
        val repository = repository(
            vectors = StaticVectorStore(AppResult.Success(emptyList())),
            web = web,
            llm = llm
        )

        val events = repository.stream(request(allowWebFallback = true)).toList()
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()

        assertEquals(1, web.calls)
        assertTrue(events.contains(ChatStreamEvent.WebSearchStarted))
        assertEquals("Wikipedia", completed.message.citations.single().provider)
        assertTrue(llm.lastContext.orEmpty().contains("untrusted"))
    }

    @Test
    fun localMissWithoutOptInWithholdsAnswer() = runTest {
        val llm = RecordingLlm()
        val repository = repository(
            vectors = StaticVectorStore(AppResult.Success(emptyList())),
            web = RecordingWebSearchRepository(AppResult.Success(listOf(webEvidence()))),
            llm = llm
        )

        val events = repository.stream(request(allowWebFallback = false)).toList()

        assertTrue(events.single { it is ChatStreamEvent.Failed }
            .let { it is ChatStreamEvent.Failed && it.message.contains("withheld") })
        assertEquals(null, llm.lastContext)
    }

    @Test
    fun localContextFailureDoesNotTriggerWebFallback() = runTest {
        val web = RecordingWebSearchRepository(
            AppResult.Success(listOf(webEvidence()))
        )
        val repository = repository(
            vectors = StaticVectorStore(
                AppResult.Success(
                    listOf(
                        Evidence(
                            documentId = DocumentId("doc"),
                            documentName = "x".repeat(10_000),
                            chunkOrdinal = 0,
                            text = "retrieved context",
                            score = 0.9f
                        )
                    )
                )
            ),
            web = web,
            llm = RecordingLlm()
        )

        val events = repository.stream(request(allowWebFallback = true)).toList()

        assertEquals(0, web.calls)
        assertTrue(
            events.filterIsInstance<ChatStreamEvent.Failed>()
                .single()
                .message
                .contains("Local RAG context could not be prepared")
        )
    }

    private fun repository(
        vectors: VectorStoreRepository,
        web: RecordingWebSearchRepository,
        llm: RecordingLlm
    ) = LocalRagChatRepository(
        embedding = StaticEmbedding(),
        vectors = vectors,
        buildContext = com.chatbuddy.domain.usecase.BuildRagContextUseCase(),
        bindCitations = com.chatbuddy.domain.usecase.BindCitationsUseCase(),
        webSearch = web,
        llm = llm
    )

    private fun request(allowWebFallback: Boolean) = ChatRequest(
        text = "What is local context?",
        persona = Persona("persona", "Companion", "Helpful", "", maxTokens = 4),
        useRag = true,
        allowWebFallback = allowWebFallback
    )

    private fun webEvidence() = WebEvidence(
        title = "Grounded result",
        url = "https://en.wikipedia.org/wiki/Example",
        excerpt = "A grounded excerpt",
        content = "A grounded web result.",
        provider = "Wikipedia",
        sourceId = "wikipedia:1"
    )

    private class StaticEmbedding : EmbeddingRepository {
        override suspend fun embed(text: String): AppResult<FloatArray> =
            AppResult.Success(FloatArray(384) { 0.1f })
    }

    private class StaticVectorStore(
        private val result: AppResult<List<Evidence>>
    ) : VectorStoreRepository {
        override suspend fun insert(
            chunk: com.chatbuddy.domain.model.DocumentChunk,
            embedding: FloatArray
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun search(
            embedding: FloatArray,
            limit: Int
        ): AppResult<List<Evidence>> = result
    }

    private class RecordingWebSearchRepository(
        private val result: AppResult<List<WebEvidence>>
    ) : com.chatbuddy.domain.repository.WebSearchRepository {
        var calls: Int = 0

        override suspend fun search(query: String, limit: Int): AppResult<List<WebEvidence>> {
            calls++
            return result
        }
    }

    private class RecordingLlm : LocalLlmEngine {
        var lastContext: String? = null

        override fun stream(request: ChatRequest, context: String?): Flow<ChatStreamEvent> {
            lastContext = context
            val marker = if (context?.contains("local:") == true) "[local:doc:0]" else "[wikipedia:1]"
            return flowOf(
                ChatStreamEvent.Started,
                ChatStreamEvent.Token("grounded $marker"),
                ChatStreamEvent.Completed(
                    ChatMessage("", ChatMessage.Role.ASSISTANT, "grounded $marker")
                )
            )
        }

        override suspend fun verifyRuntime(): AppResult<Unit> = AppResult.Success(Unit)
    }
}

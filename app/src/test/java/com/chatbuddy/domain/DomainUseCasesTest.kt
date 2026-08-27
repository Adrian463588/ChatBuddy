package com.chatbuddy.domain

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.AssistantBehaviorPolicy
import com.chatbuddy.domain.model.BuiltInPersonaCatalog
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.Evidence
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.usecase.BuildRagContextUseCase
import com.chatbuddy.domain.usecase.BindCitationsUseCase
import com.chatbuddy.domain.usecase.ChunkDocumentUseCase
import com.chatbuddy.domain.usecase.DownloadAction
import com.chatbuddy.domain.usecase.DownloadState
import com.chatbuddy.domain.usecase.DownloadStateMachine
import com.chatbuddy.domain.usecase.ValidatePersonaUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainUseCasesTest {
    @Test
    fun chunkingUsesConfiguredOverlap() {
        val chunks = ChunkDocumentUseCase()(
            documentId = DocumentId("doc"),
            tokens = (0 until 600).asSequence().map(Int::toString),
            chunkSize = 512,
            overlap = 50
        ).toList()

        assertEquals(2, chunks.size)
        assertEquals(462, chunks[1].startToken)
        assertEquals(600, chunks[1].endTokenExclusive)
    }

    @Test
    fun invalidPersonaReturnsActionableError() {
        val result = ValidatePersonaUseCase()(Persona("id", "", "", "", maxTokens = 128))
        assertTrue(result is AppResult.Error)
        assertEquals("Persona name is required", (result as AppResult.Error).message)
    }

    @Test
    fun bundledPersonasAreValidAndDefaultUsesBoundedProbing() {
        val validator = ValidatePersonaUseCase()

        BuiltInPersonaCatalog.all.forEach { template ->
            val result = validator(template.toPersona())
            assertTrue("Invalid bundled persona: ${template.id}", result is AppResult.Success)
        }

        val defaultPrompt = BuiltInPersonaCatalog.default.systemPrompt
        assertTrue(defaultPrompt.contains("exactly one short clarifying question"))
        assertTrue(defaultPrompt.contains("state a brief assumption and continue"))
        assertTrue(BuiltInPersonaCatalog.default.toPersona(active = true).active)
    }

    @Test
    fun customPersonaReceivesTheSameGroundingAndProbingContract() {
        assertTrue(AssistantBehaviorPolicy.prompt.contains("exactly one short"))
        assertTrue(AssistantBehaviorPolicy.prompt.contains("low-risk ambiguity"))
        assertTrue(AssistantBehaviorPolicy.prompt.contains("never invent facts"))
    }

    @Test
    fun ragContextAbstainsWithoutRelevantEvidence() {
        val result = BuildRagContextUseCase()(listOf(
            Evidence(DocumentId("doc"), "notes.txt", 0, "unrelated", 0.1f)
        ))
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun citationBindingRejectsUnknownSource() {
        val result = BindCitationsUseCase()(
            answer = "Answer [local:doc:9]",
            citations = listOf(
                com.chatbuddy.domain.model.ChatCitation(
                    kind = com.chatbuddy.domain.model.ChatCitationKind.LOCAL_DOCUMENT,
                    title = "notes",
                    uri = null,
                    excerpt = "evidence",
                    provider = "Local document",
                    sourceId = "local:doc:1"
                )
            )
        )
        assertTrue(result is AppResult.Error)
    }

    @Test
    fun citationBindingKeepsOnlyReferencedEvidence() {
        val result = BindCitationsUseCase()(
            answer = "Answer [local:doc:1]",
            citations = listOf(
                com.chatbuddy.domain.model.ChatCitation(
                    kind = com.chatbuddy.domain.model.ChatCitationKind.LOCAL_DOCUMENT,
                    title = "notes",
                    uri = null,
                    excerpt = "evidence",
                    provider = "Local document",
                    sourceId = "local:doc:1"
                ),
                com.chatbuddy.domain.model.ChatCitation(
                    kind = com.chatbuddy.domain.model.ChatCitationKind.LOCAL_DOCUMENT,
                    title = "other",
                    uri = null,
                    excerpt = "other evidence",
                    provider = "Local document",
                    sourceId = "local:doc:2"
                )
            )
        )
        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data.citations.size)
    }

    @Test
    fun downloadStateRequiresChecksumReadyBytesBeforeCompletion() {
        val machine = DownloadStateMachine(100)
        var state: DownloadState = machine.reduce(DownloadState.Idle, DownloadAction.Start(0))
        state = machine.reduce(state, DownloadAction.Progress(100))
        state = machine.reduce(state, DownloadAction.Verify)
        state = machine.reduce(state, DownloadAction.Verified)
        assertEquals(DownloadState.Complete, state)
    }
}

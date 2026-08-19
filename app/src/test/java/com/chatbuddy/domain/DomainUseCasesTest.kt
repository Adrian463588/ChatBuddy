package com.chatbuddy.domain

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.Evidence
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.usecase.BuildRagContextUseCase
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
    fun ragContextAbstainsWithoutRelevantEvidence() {
        val result = BuildRagContextUseCase()(listOf(
            Evidence(DocumentId("doc"), "notes.txt", 0, "unrelated", 0.1f)
        ))
        assertTrue(result is AppResult.Error)
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

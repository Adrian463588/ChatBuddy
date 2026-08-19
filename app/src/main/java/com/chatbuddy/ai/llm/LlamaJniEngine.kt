package com.chatbuddy.ai.llm

import android.os.ParcelFileDescriptor
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.data.model.ModelManifestDataSource
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaJniEngine @Inject constructor(
    private val manifest: ModelManifestDataSource,
    private val modelStore: SafModelStore
) : LocalLlmEngine {
    private val mutex = Mutex()
    private var nativeReady = false
    private var loadedModel: ParcelFileDescriptor? = null

    override fun stream(request: ChatRequest, context: String?): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Started)
        mutex.withLock {
            val setup = withContext(Dispatchers.IO) { ensureLoaded() }
            if (setup is AppResult.Error) {
                emit(ChatStreamEvent.Failed(setup.message))
                return@withLock
            }
            val start = runCatching {
                LlamaNative.nativeStart(
                    systemPrompt = buildSystemPrompt(request, context),
                    userPrompt = request.text,
                    maxTokens = request.persona.maxTokens,
                    temperature = request.persona.temperature,
                    topP = request.persona.topP
                )
            }.getOrElse { error ->
                emit(ChatStreamEvent.Failed("Unable to start local LLM: ${error.message}"))
                return@withLock
            }
            if (start != 0) {
                emit(ChatStreamEvent.Failed("Local LLM could not prepare the GGUF prompt (code $start)"))
                return@withLock
            }
            val output = StringBuilder()
            var generatedTokens = 0
            while (generatedTokens < request.persona.maxTokens) {
                val token = runCatching { LlamaNative.nativeNext() }.getOrElse { error ->
                    emit(ChatStreamEvent.Failed("Local LLM generation failed: ${error.message}"))
                    return@withLock
                } ?: break
                if (token.isNotEmpty()) {
                    output.append(token)
                    emit(ChatStreamEvent.Token(token))
                }
                generatedTokens++
            }
            emit(
                ChatStreamEvent.Completed(
                    ChatMessage(
                        id = "",
                        role = ChatMessage.Role.ASSISTANT,
                        text = output.toString()
                    )
                )
            )
        }
    }

    override suspend fun verifyRuntime(): AppResult<Unit> = withContext(Dispatchers.Default) {
        runCatching { ensureNative() }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error("llama.cpp JNI runtime is unavailable", it) }
        )
    }

    private suspend fun ensureLoaded(): AppResult<Unit> {
        val runtime = runCatching { ensureNative() }
            .getOrElse { return AppResult.Error("llama.cpp JNI runtime is unavailable", it) }
        if (!runtime) return AppResult.Error("llama.cpp backend initialization failed")

        val artifact = manifest.read().firstOrNull { it.id == GEMMA_ARTIFACT_ID }
            ?: return AppResult.Error("Gemma model manifest entry is missing")
        val file = modelStore.finalFile(artifact)
            ?: return AppResult.Error("Download the Gemma model into the selected SAF folder first")
        if (modelStore.fileLength(file) != artifact.sizeBytes) {
            return AppResult.Error("Gemma model size does not match its manifest")
        }
        if (loadedModel != null) return AppResult.Success(Unit)
        val descriptor = modelStore.openDescriptor(file)
            ?: return AppResult.Error("Unable to open Gemma model through SAF")
        val result = runCatching { LlamaNative.nativeLoad(descriptor.fd) }
            .getOrElse { error ->
                descriptor.close()
                return AppResult.Error("Unable to load Gemma GGUF", error)
            }
        if (result != 0) {
            descriptor.close()
            return AppResult.Error("Unable to load Gemma GGUF (native code $result)")
        }
        loadedModel = descriptor
        return AppResult.Success(Unit)
    }

    private fun ensureNative(): Boolean {
        if (nativeReady) return true
        nativeReady = LlamaNative.nativeInit()
        return nativeReady
    }

    private fun buildSystemPrompt(request: ChatRequest, context: String?): String = buildString {
        append(request.persona.description.trim())
        if (request.persona.systemPrompt.isNotBlank()) {
            append("\n\nPersona instructions:\n")
            append(request.persona.systemPrompt.trim())
        }
        if (!context.isNullOrBlank()) {
            append("\n\nUse only the following retrieved evidence. Cite the source names when answering:\n")
            append(context)
        }
    }

    companion object {
        private const val GEMMA_ARTIFACT_ID = "gemma-4-e2b-it-q4-0"
    }
}

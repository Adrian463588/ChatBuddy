package com.chatbuddy.ai.voice

import com.chatbuddy.data.download.ModelRuntimeCache
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.data.model.ModelManifestDataSource
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.VoiceTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhisperJniEngine @Inject constructor(
    private val manifest: ModelManifestDataSource,
    private val modelStore: SafModelStore,
    private val runtimeCache: ModelRuntimeCache
) : WhisperEngine {
    private val mutex = Mutex()
    private var loaded = false

    override val isReady: Boolean
        get() = loaded && runCatching { WhisperNative.nativeIsLoaded() }.getOrDefault(false)

    override suspend fun ensureLoaded(): AppResult<Unit> = mutex.withLock {
        if (isReady) return@withLock AppResult.Success(Unit)
        withContext(Dispatchers.IO) {
            runCatching {
                val artifact = manifest.read().firstOrNull { it.id == WHISPER_ARTIFACT_ID }
                    ?: return@runCatching AppResult.Error("Whisper model manifest entry is missing")
                val source = modelStore.finalFile(artifact)
                    ?: return@runCatching AppResult.Error(
                        "Download Whisper Base Q5_1 in Settings before using live translation"
                    )
                if (modelStore.fileLength(source) != artifact.sizeBytes) {
                    return@runCatching AppResult.Error("Whisper model size does not match its manifest")
                }
                val cache = when (val result = runtimeCache.prepare(artifact, source)) {
                    is AppResult.Success -> result.data
                    is AppResult.Error -> return@runCatching AppResult.Error(
                        "Unable to prepare Whisper runtime cache",
                        result.cause
                    )
                    AppResult.Loading -> return@runCatching AppResult.Error("Whisper cache is still preparing")
                } ?: return@runCatching AppResult.Error(
                    "Whisper model could not be materialized in the device cache"
                )
                if (!WhisperNative.nativeInit()) {
                    return@runCatching AppResult.Error("Whisper JNI runtime could not initialize")
                }
                val loadCode = WhisperNative.nativeLoad(cache.file.absolutePath)
                if (loadCode != 0) {
                    return@runCatching AppResult.Error("Whisper model failed to load (native code $loadCode)")
                }
                loaded = true
                AppResult.Success(Unit)
            }.getOrElse { error ->
                loaded = false
                AppResult.Error("Whisper JNI runtime is unavailable", error)
            }
        }
    }

    override fun transcribe(
        samples: ShortArray,
        languageTag: String,
        partial: Boolean
    ): Flow<VoiceTranscript> = flow {
        if (!isReady) {
            emit(VoiceTranscript.Failed("Whisper model is not ready"))
            return@flow
        }
        val text = runCatching {
            WhisperNative.nativeTranscribe(samples, languageTag)
        }.getOrElse { error ->
            emit(VoiceTranscript.Failed("Whisper transcription failed: ${error.message}"))
            return@flow
        }.trim()
        if (text.isNotBlank()) {
            emit(if (partial) VoiceTranscript.Partial(text) else VoiceTranscript.Final(text))
        }
    }

    companion object {
        private const val WHISPER_ARTIFACT_ID = "whisper-base-q5-1"
    }
}

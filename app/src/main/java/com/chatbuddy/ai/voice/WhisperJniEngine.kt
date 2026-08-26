package com.chatbuddy.ai.voice

import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.chatbuddy.data.download.CachedModelFile
import com.chatbuddy.data.download.ModelRuntimeCache
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.data.model.ModelManifestDataSource
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.VoiceTranscript
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class WhisperJniEngine @Inject constructor(
    private val manifest: ModelManifestDataSource,
    private val modelStore: SafModelStore,
    private val runtimeCache: ModelRuntimeCache
) : WhisperEngine {
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var loaded = false
    private var loadedFingerprint: String? = null
    private var safDescriptor: ParcelFileDescriptor? = null

    override val isReady: Boolean
        get() = loaded && runCatching { WhisperNative.nativeIsLoaded() }.getOrDefault(false)

    override suspend fun ensureLoaded(): AppResult<Unit> = loadMutex.withLock {
        if (isReady) return@withLock AppResult.Success(Unit)
        withContext(Dispatchers.IO) {
            loadFromDurableStorage()
        }
    }

    override fun transcribe(
        samples: ShortArray,
        languageTag: String,
        partial: Boolean
    ): Flow<VoiceTranscript> = flow {
        currentCoroutineContext().ensureActive()
        when (val setup = ensureLoaded()) {
            is AppResult.Error -> {
                emit(VoiceTranscript.Failed(setup.message))
                return@flow
            }

            is AppResult.Success -> Unit
            AppResult.Loading -> {
                emit(VoiceTranscript.Failed("Whisper model is still preparing"))
                return@flow
            }
        }
        if (samples.isEmpty()) return@flow

        val text = try {
            inferenceMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!isReady) {
                    return@withLock null
                }
                withContext(Dispatchers.Default) {
                    WhisperNative.nativeTranscribe(samples, languageTag)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            emit(VoiceTranscript.Failed("Whisper transcription failed: ${safeMessage(error)}"))
            return@flow
        }

        currentCoroutineContext().ensureActive()
        val cleanText = text?.trim().orEmpty()
        if (cleanText.isNotBlank()) {
            emit(if (partial) VoiceTranscript.Partial(cleanText) else VoiceTranscript.Final(cleanText))
        }
    }

    override suspend fun close() = loadMutex.withLock {
        inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                loaded = false
                loadedFingerprint = null
                runCatching { WhisperNative.nativeClose() }
                safDescriptor?.close()
                safDescriptor = null
            }
        }
    }

    private suspend fun loadFromDurableStorage(): AppResult<Unit> {
        val artifact = try {
            manifest.read().firstOrNull { it.id == WHISPER_ARTIFACT_ID }
        } catch (error: Exception) {
            return AppResult.Error("Whisper model manifest could not be read.", error)
        } ?: return AppResult.Error("Whisper model manifest entry is missing")

        val source = modelStore.finalFile(artifact)
            ?: return AppResult.Error(
                "Download Whisper Base Q5_1 in Settings before using live translation"
            )
        if (modelStore.fileLength(source) != artifact.sizeBytes) {
            return AppResult.Error("Whisper model size does not match its manifest")
        }
        when (val verification = verifySource(source, artifact)) {
            is AppResult.Error -> return verification
            is AppResult.Success -> Unit
            AppResult.Loading -> return AppResult.Error("Whisper model verification is still preparing")
        }

        closeNativeState()
        val cacheResult = try {
            runtimeCache.prepare(artifact, source)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppResult.Error("Whisper runtime cache could not be checked.", error)
        }
        val cache = when (val result = cacheResult) {
            is AppResult.Success -> result.data
            is AppResult.Error -> null
            AppResult.Loading -> null
        }

        val loadedFromCache = cache?.let { loadNative(it, artifact.sha256) } == true
        if (loadedFromCache) return AppResult.Success(Unit)

        val descriptor = modelStore.openDescriptor(source)
            ?: return AppResult.Error("Whisper model could not be opened from the selected SAF folder")
        val loadedFromSaf = loadNativeFromDescriptor(descriptor, artifact.sha256)
        if (!loadedFromSaf) {
            descriptor.close()
            return AppResult.Error("Whisper model failed to load from SAF")
        }
        safDescriptor = descriptor
        return AppResult.Success(Unit)
    }

    private fun loadNative(cache: CachedModelFile, fingerprint: String): Boolean {
        val loadedCode = runCatching {
            if (!WhisperNative.nativeInit()) return false
            WhisperNative.nativeLoad(cache.file.absolutePath)
        }.getOrElse { return false }
        if (loadedCode != 0) {
            runCatching { WhisperNative.nativeClose() }
            return false
        }
        loaded = true
        loadedFingerprint = fingerprint.normalizedSha()
        return true
    }

    private fun loadNativeFromDescriptor(
        descriptor: ParcelFileDescriptor,
        fingerprint: String
    ): Boolean {
        val loadedCode = runCatching {
            if (!WhisperNative.nativeInit()) return false
            WhisperNative.nativeLoad(safFdPath(descriptor.fd))
        }.getOrElse { return false }
        if (loadedCode != 0) {
            runCatching { WhisperNative.nativeClose() }
            return false
        }
        loaded = true
        loadedFingerprint = fingerprint.normalizedSha()
        return true
    }

    private fun closeNativeState() {
        loaded = false
        loadedFingerprint = null
        runCatching { WhisperNative.nativeClose() }
        safDescriptor?.close()
        safDescriptor = null
    }

    private fun verifySource(source: DocumentFile, artifact: ModelArtifact): AppResult<Unit> {
        val expectedSha = artifact.sha256.normalizedSha()
        if (!SHA256_PATTERN.matches(expectedSha)) {
            return AppResult.Error("Whisper model manifest checksum is invalid")
        }
        val input = modelStore.openInput(source)
            ?: return AppResult.Error("Whisper model could not be read from the selected SAF folder")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buffer = ByteArray(VERIFY_BUFFER_BYTES)
            input.use { rawInput ->
                BufferedInputStream(rawInput, VERIFY_BUFFER_BYTES).use { buffered ->
                    while (true) {
                        val read = buffered.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > artifact.sizeBytes) {
                            return AppResult.Error("Whisper model is larger than its manifest")
                        }
                        digest.update(buffer, 0, read)
                    }
                }
            }
            val actualSha = digest.digest().toHex()
            if (total != artifact.sizeBytes || actualSha != expectedSha) {
                AppResult.Error("Whisper model checksum does not match its manifest")
            } else {
                AppResult.Success(Unit)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppResult.Error("Whisper model verification failed.", error)
        }
    }

    private fun safeMessage(error: Exception): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "native runtime error"

    private fun String.normalizedSha(): String = trim().lowercase(Locale.ROOT)

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (value in this@toHex) {
            append(HEX[value.toInt() ushr 4 and 0x0f])
            append(HEX[value.toInt() and 0x0f])
        }
    }

    companion object {
        private const val WHISPER_ARTIFACT_ID = "whisper-base-q5-1"
        private const val VERIFY_BUFFER_BYTES = 1024 * 1024
        private const val HEX = "0123456789abcdef"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        internal fun safFdPath(fd: Int): String {
            require(fd >= 0) { "SAF file descriptor must be non-negative" }
            return "/proc/self/fd/$fd"
        }
    }
}

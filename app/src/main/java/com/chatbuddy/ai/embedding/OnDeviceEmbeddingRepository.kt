package com.chatbuddy.ai.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import com.chatbuddy.data.download.ModelStateStore
import com.chatbuddy.data.download.ResumableDownloadManager
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.model.ModelArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceEmbeddingRepository @Inject constructor(
    private val stateStore: ModelStateStore,
    private val safStore: SafModelStore,
    private val downloadManager: ResumableDownloadManager
) : EmbeddingRepository {
    private val sessionMutex = Mutex()
    private val tokenizerMutex = Mutex()
    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: MiniLmWordPieceTokenizer? = null

    override suspend fun embed(text: String): AppResult<FloatArray> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext AppResult.Error("Cannot embed empty text")
        try {
            val model = stateStore.find(MODEL_ID)
                ?: return@withContext AppResult.Error("MiniLM model is not in the manifest")
            val vocab = stateStore.find(VOCAB_ID)
                ?: return@withContext AppResult.Error("MiniLM vocabulary is not in the manifest")
            when (val modelCheck = downloadManager.verify(model)) {
                is AppResult.Error -> return@withContext AppResult.Error(modelCheck.message, modelCheck.cause)
                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext AppResult.Error("MiniLM model verification is still running")
            }
            when (val vocabCheck = downloadManager.verify(vocab)) {
                is AppResult.Error -> return@withContext AppResult.Error(vocabCheck.message, vocabCheck.cause)
                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext AppResult.Error("MiniLM vocabulary verification is still running")
            }
            val runtime = ensureRuntime(model)
                ?: return@withContext AppResult.Error("ONNX Runtime could not open MiniLM model")
            val encoded = ensureTokenizer(vocab).encode(text)
            val ids = OnnxTensor.createTensor(runtime.first, LongBuffer.wrap(encoded.inputIds), longArrayOf(1L, encoded.inputIds.size.toLong()))
            val mask = OnnxTensor.createTensor(runtime.first, LongBuffer.wrap(encoded.attentionMask), longArrayOf(1L, encoded.attentionMask.size.toLong()))
            val types = OnnxTensor.createTensor(runtime.first, LongBuffer.wrap(encoded.tokenTypeIds), longArrayOf(1L, encoded.tokenTypeIds.size.toLong()))
            ids.use { inputIds ->
                mask.use { attentionMask ->
                    types.use { tokenTypes ->
                        runtime.second.run(
                            mapOf(
                                "input_ids" to inputIds,
                                "attention_mask" to attentionMask,
                                "token_type_ids" to tokenTypes
                            )
                        ).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val output = result[0].value as? Array<Array<FloatArray>>
                                ?: return@withContext AppResult.Error("MiniLM output tensor has an unsupported shape")
                            AppResult.Success(meanPool(output[0], encoded.attentionMask))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            AppResult.Error("On-device embedding failed", error)
        }
    }

    private suspend fun ensureRuntime(model: ModelArtifact): Pair<OrtEnvironment, OrtSession>? = sessionMutex.withLock {
        val currentEnvironment = environment
        val currentSession = session
        if (currentEnvironment != null && currentSession != null) return@withLock currentEnvironment to currentSession
        val file = safStore.finalFile(model) ?: return@withLock null
        val bytes = safStore.openInput(file)?.use { it.readBytes() } ?: return@withLock null
        val createdEnvironment = OrtEnvironment.getEnvironment()
        val options = SessionOptions()
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        val createdSession = options.use { createdEnvironment.createSession(bytes, it) }
        environment = createdEnvironment
        session = createdSession
        createdEnvironment to createdSession
    }

    private suspend fun ensureTokenizer(vocabArtifact: ModelArtifact): MiniLmWordPieceTokenizer = tokenizerMutex.withLock {
        tokenizer?.let { return@withLock it }
        val file = safStore.finalFile(vocabArtifact) ?: error("MiniLM vocabulary file is missing")
        val created = safStore.openInput(file)?.use(MiniLmWordPieceTokenizer::fromVocab)
            ?: error("Unable to read MiniLM vocabulary file")
        tokenizer = created
        created
    }

    private fun meanPool(output: Array<FloatArray>, mask: LongArray): FloatArray {
        val pooled = FloatArray(384)
        var count = 0f
        output.forEachIndexed { index, vector ->
            if (mask[index] == 1L) {
                vector.forEachIndexed { dimension, value -> pooled[dimension] += value }
                count += 1f
            }
        }
        if (count == 0f) error("MiniLM returned no active tokens")
        for (index in pooled.indices) pooled[index] /= count
        var norm = 0f
        pooled.forEach { norm += it * it }
        val magnitude = kotlin.math.sqrt(norm).coerceAtLeast(1e-12f)
        for (index in pooled.indices) pooled[index] /= magnitude
        return pooled
    }

    companion object {
        private const val MODEL_ID = "all-minilm-l6-v2-qint8-arm64"
        private const val VOCAB_ID = "all-minilm-l6-v2-vocab"
    }
}

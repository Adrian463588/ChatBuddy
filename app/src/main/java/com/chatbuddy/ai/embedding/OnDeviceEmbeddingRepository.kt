package com.chatbuddy.ai.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import com.chatbuddy.data.download.ModelRuntimeCache
import com.chatbuddy.data.download.ModelStateStore
import com.chatbuddy.data.download.ResumableDownloadManager
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.model.ModelArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceEmbeddingRepository @Inject constructor(
    private val stateStore: ModelStateStore,
    private val safStore: SafModelStore,
    private val downloadManager: ResumableDownloadManager,
    private val runtimeCache: ModelRuntimeCache
) : EmbeddingRepository {
    private val sessionMutex = Mutex()
    private val tokenizerMutex = Mutex()
    private val verificationMutex = Mutex()
    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: MiniLmWordPieceTokenizer? = null
    private var sessionFingerprint: String? = null
    private var tokenizerFingerprint: String? = null
    private var verifiedModelFingerprint: String? = null
    private var verifiedVocabFingerprint: String? = null

    override suspend fun embed(text: String): AppResult<FloatArray> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext AppResult.Error("Cannot embed empty text")
        try {
            val model = stateStore.find(MODEL_ID)
                ?: return@withContext AppResult.Error("MiniLM model is not in the manifest")
            val vocab = stateStore.find(VOCAB_ID)
                ?: return@withContext AppResult.Error("MiniLM vocabulary is not in the manifest")
            when (val modelCheck = ensureVerified(model, isModel = true)) {
                is AppResult.Error -> return@withContext AppResult.Error(modelCheck.message, modelCheck.cause)
                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext AppResult.Error("MiniLM model verification is still running")
            }
            when (val vocabCheck = ensureVerified(vocab, isModel = false)) {
                is AppResult.Error -> return@withContext AppResult.Error(vocabCheck.message, vocabCheck.cause)
                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext AppResult.Error("MiniLM vocabulary verification is still running")
            }
            val runtime = ensureRuntime(model)
                ?: return@withContext AppResult.Error("ONNX Runtime could not open MiniLM model")
            val aggregator = MiniLmSubwindowAggregator()
            var windowCount = 0
            ensureTokenizer(vocab).encodeWindows(text).forEach { encoded ->
                addWindowEmbedding(runtime, encoded, aggregator)
                windowCount++
            }
            if (windowCount == 0) return@withContext AppResult.Error("MiniLM produced no token windows")
            AppResult.Success(aggregator.finish())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("On-device embedding failed", error)
        }
    }

    private fun addWindowEmbedding(
        runtime: Pair<OrtEnvironment, OrtSession>,
        encoded: MiniLmEncodedInput,
        aggregator: MiniLmSubwindowAggregator
    ) {
        val ids = OnnxTensor.createTensor(
            runtime.first,
            LongBuffer.wrap(encoded.inputIds),
            longArrayOf(1L, encoded.inputIds.size.toLong())
        )
        val mask = OnnxTensor.createTensor(
            runtime.first,
            LongBuffer.wrap(encoded.attentionMask),
            longArrayOf(1L, encoded.attentionMask.size.toLong())
        )
        val types = OnnxTensor.createTensor(
            runtime.first,
            LongBuffer.wrap(encoded.tokenTypeIds),
            longArrayOf(1L, encoded.tokenTypeIds.size.toLong())
        )
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
                            ?: error("MiniLM output tensor has an unsupported shape")
                        val sequenceOutput = output.singleOrNull()
                            ?: error("MiniLM output tensor batch shape is unsupported")
                        aggregator.add(
                            windowEmbedding = meanPool(sequenceOutput, encoded.attentionMask),
                            activeTokenCount = encoded.activeTokenCount
                        )
                    }
                }
            }
        }
    }

    private suspend fun ensureRuntime(model: ModelArtifact): Pair<OrtEnvironment, OrtSession>? = sessionMutex.withLock {
        val currentEnvironment = environment
        val currentSession = session
        if (
            currentEnvironment != null &&
            currentSession != null &&
            sessionFingerprint == model.sha256
        ) {
            return@withLock currentEnvironment to currentSession
        }
        currentSession?.close()
        session = null
        sessionFingerprint = null
        val file = safStore.finalFile(model) ?: return@withLock null
        val cached = runtimeCache.prepare(model, file)
        val cachedFile = (cached as? AppResult.Success)?.data?.file
        val bytes = withContext(Dispatchers.IO) {
            cachedFile?.takeIf { it.isFile }?.readBytes()
                ?: safStore.openInput(file)?.use { it.readBytes() }
        } ?: return@withLock null
        val createdEnvironment = OrtEnvironment.getEnvironment()
        val options = SessionOptions()
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        val createdSession = options.use { createdEnvironment.createSession(bytes, it) }
        environment = createdEnvironment
        session = createdSession
        sessionFingerprint = model.sha256
        createdEnvironment to createdSession
    }

    private suspend fun ensureTokenizer(vocabArtifact: ModelArtifact): MiniLmWordPieceTokenizer = tokenizerMutex.withLock {
        if (tokenizerFingerprint == vocabArtifact.sha256) {
            tokenizer?.let { return@withLock it }
        }
        tokenizer = null
        tokenizerFingerprint = null
        val file = safStore.finalFile(vocabArtifact) ?: error("MiniLM vocabulary file is missing")
        val cached = runtimeCache.prepare(vocabArtifact, file)
        val cachedFile = (cached as? AppResult.Success)?.data?.file
        val created = withContext(Dispatchers.IO) {
            cachedFile?.takeIf { it.isFile }?.inputStream()?.use(MiniLmWordPieceTokenizer::fromVocab)
                ?: safStore.openInput(file)?.use(MiniLmWordPieceTokenizer::fromVocab)
        }
            ?: error("Unable to read MiniLM vocabulary file")
        tokenizer = created
        tokenizerFingerprint = vocabArtifact.sha256
        created
    }

    private suspend fun ensureVerified(
        artifact: ModelArtifact,
        isModel: Boolean
    ): AppResult<Unit> = verificationMutex.withLock {
        val fingerprint = artifact.sha256
        val cachedFingerprint = if (isModel) verifiedModelFingerprint else verifiedVocabFingerprint
        if (cachedFingerprint == fingerprint) return@withLock AppResult.Success(Unit)
        val result = downloadManager.verify(artifact)
        if (result is AppResult.Success) {
            if (isModel) verifiedModelFingerprint = fingerprint else verifiedVocabFingerprint = fingerprint
        }
        result
    }

    private fun meanPool(output: Array<FloatArray>, mask: LongArray): FloatArray {
        require(output.size == mask.size) { "MiniLM output sequence length does not match input" }
        val pooled = FloatArray(EMBEDDING_DIMENSIONS)
        var count = 0f
        output.forEachIndexed { index, vector ->
            if (mask[index] == 1L) {
                require(vector.size == EMBEDDING_DIMENSIONS) {
                    "MiniLM output embedding dimension is unsupported"
                }
                vector.forEachIndexed { dimension, value -> pooled[dimension] += value }
                count += 1f
            }
        }
        if (count == 0f) error("MiniLM returned no active tokens")
        for (index in pooled.indices) pooled[index] /= count
        return pooled
    }

    companion object {
        private const val EMBEDDING_DIMENSIONS = 384
        private const val MODEL_ID = "all-minilm-l6-v2-qint8-arm64"
        private const val VOCAB_ID = "all-minilm-l6-v2-vocab"
    }
}

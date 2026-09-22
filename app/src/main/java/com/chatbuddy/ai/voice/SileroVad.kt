package com.chatbuddy.ai.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silero VAD v5 — Neural Voice Activity Detection.
 *
 * Wraps the pre-bundled `silero_vad.onnx` asset (≈2.3 MB) to provide per-chunk
 * speech probability in [0.0, 1.0]. Uses a stateful LSTM that accumulates temporal
 * context across consecutive 32ms frames, making it robust to transient noise that
 * would fool energy-based VAD (HVAC, fabric rustle, brief coughs, background music).
 *
 * The model processes 512-sample windows (32ms @ 16kHz). Each call also prepends
 * a 64-sample rolling context from the previous window so the LSTM has continuity.
 *
 * Thread-safety: NOT thread-safe. Call only from a single coroutine/IO dispatcher.
 *
 * Usage:
 * ```
 * val vad = SileroVad(context)
 * vad.resetState()                                // at start of each recording session
 * val prob = vad.infer(floatArrayOf512Samples)    // 0.0 = silence, 1.0 = speech
 * vad.close()                                     // when done
 * ```
 */
@Singleton
class SileroVad @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context?
) : Closeable {

    private val env: OrtEnvironment? by lazy {
        runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    }

    /**
     * Lazily initialised ONNX session — avoids blocking the Hilt injection thread.
     * Access only after [isReady] returns true or after calling [ensureLoaded].
     */
    @Volatile
    private var session: OrtSession? = null

    @Volatile
    var isReady: Boolean = false
        private set

    // ── LSTM state: shape [2, 1, 128] flattened → 256 floats ────────────────
    // First 128 = hidden state (h), second 128 = cell state (c).
    private var state = FloatArray(STATE_SIZE) { 0f }

    // Rolling context: last CONTEXT_SIZE samples of the previous inference window.
    // Prepended to each new 512-sample chunk → 576-sample input to the model.
    private var rollingContext = FloatArray(CONTEXT_SIZE) { 0f }

    /**
     * Load the ONNX session from bundled assets (or explicitly provided bytes).
     * Idempotent — safe to call multiple times.
     * Should be called on a background thread before the first recording session.
     */
    @Synchronized
    fun ensureLoaded(modelBytes: ByteArray? = null): Boolean {
        if (isReady) return true
        val e = env ?: return false
        return try {
            val bytes = modelBytes ?: context?.assets?.open(MODEL_ASSET)?.use { it.readBytes() }
                ?: return false
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)   // 1 thread is enough; model is very small
                setInterOpNumThreads(1)
            }
            session = e.createSession(bytes, opts)
            isReady = true
            Log.i(TAG, "Silero VAD loaded (${bytes.size / 1024} KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Silero VAD failed to load: ${e.message}")
            false
        }
    }

    /**
     * Run one Silero VAD inference step.
     *
     * @param samples Exactly [FRAME_SIZE] (512) float samples, each in [-1.0, 1.0].
     * @return Speech probability in [0.0, 1.0], or 0.0 if the model is not loaded.
     */
    fun infer(samples: FloatArray): Float {
        val s = session ?: return 0f
        val e = env ?: return 0f
        if (samples.size != FRAME_SIZE) {
            Log.w(TAG, "infer: expected $FRAME_SIZE samples, got ${samples.size}")
            return 0f
        }

        // Build [1, CONTEXT_SIZE + FRAME_SIZE] = [1, 576] input tensor.
        val window = FloatArray(CONTEXT_SIZE + FRAME_SIZE)
        rollingContext.copyInto(window, destinationOffset = 0)
        samples.copyInto(window, destinationOffset = CONTEXT_SIZE)

        val inputTensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(window),
            longArrayOf(1L, (CONTEXT_SIZE + FRAME_SIZE).toLong())
        )
        val stateTensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(state),
            longArrayOf(2L, 1L, 128L)
        )
        val srTensor = OnnxTensor.createTensor(
            e,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
            longArrayOf(1L)
        )

        return try {
            s.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor))
                .use { result ->
                    // Output[0]: speech probability, shape [1, 1] or scalar
                    val probOutput = result[0].value
                    val speechProb: Float = when (probOutput) {
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            (probOutput as Array<FloatArray>)[0][0]
                        }
                        is FloatArray -> probOutput[0]
                        is Float -> probOutput
                        else -> 0f
                    }

                    // Output[1]: updated LSTM state, shape [2, 1, 128]
                    val newState = result[1].value
                    when (newState) {
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val s3d = newState as Array<Array<FloatArray>>
                            var idx = 0
                            for (i in 0 until 2) {
                                for (j in 0 until 128) {
                                    state[idx++] = s3d[i][0][j]
                                }
                            }
                        }
                        is FloatArray -> newState.copyInto(state)
                        else -> { /* state not updated — leave previous */ }
                    }

                    // Advance rolling context: last CONTEXT_SIZE samples of this window.
                    window.copyInto(
                        rollingContext,
                        destinationOffset = 0,
                        startIndex = window.size - CONTEXT_SIZE,
                        endIndex = window.size
                    )

                    speechProb
                }
        } catch (e: Exception) {
            Log.e(TAG, "infer error: ${e.message}")
            0f
        } finally {
            runCatching { inputTensor.close() }
            runCatching { stateTensor.close() }
            runCatching { srTensor.close() }
        }
    }

    /**
     * Reset LSTM state and rolling context. Call at the start of each new recording session
     * to prevent state from a previous conversation affecting the new one.
     */
    fun resetState() {
        state.fill(0f)
        rollingContext.fill(0f)
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        isReady = false
    }

    companion object {
        private const val TAG = "SileroVad"
        const val MODEL_ASSET = "silero_vad.onnx"
        const val SAMPLE_RATE = 16_000
        /** Samples per inference frame: 512 @ 16kHz = 32ms */
        const val FRAME_SIZE = 512
        /** Rolling context prepended to each frame: 64 samples = 4ms */
        const val CONTEXT_SIZE = 64
        private const val STATE_SIZE = 2 * 1 * 128  // [2, 1, 128] flattened
    }
}

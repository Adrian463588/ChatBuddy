package com.chatbuddy.data.repository

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.OcrTextBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraOcrAnalyzer @Inject constructor() : ImageAnalysis.Analyzer {
    private val lock = Any()
    private val recognizers = mutableMapOf<RecognizerKind, TextRecognizer>()
    private val frameInFlight = AtomicBoolean(false)
    private var generation = 0L
    private var activeTasks = 0
    private var closeRequested = false
    private var closed = false
    private var languageTag: String = DEFAULT_LANGUAGE
    private var onResult: (OcrResult) -> Unit = {}
    private var onError: (String) -> Unit = {}

    fun setCallbacks(
        onResult: (OcrResult) -> Unit,
        onError: (String) -> Unit
    ) {
        synchronized(lock) {
            generation += 1
            closed = false
            closeRequested = false
            this.onResult = onResult
            this.onError = onError
        }
    }

    fun clearCallbacks() {
        synchronized(lock) {
            generation += 1
            onResult = {}
            onError = {}
        }
    }

    fun setLanguageTag(languageTag: String) {
        synchronized(lock) {
            val normalized = normalizeLanguageTag(languageTag)
            if (this.languageTag != normalized) {
                generation += 1
                this.languageTag = normalized
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (!frameInFlight.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            frameInFlight.set(false)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val imageWidth = if (rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
        val imageHeight = if (rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width
        val inputImage = try {
            InputImage.fromMediaImage(mediaImage, rotationDegrees)
        } catch (error: Exception) {
            finishFrame(imageProxy)
            deliverError(null, error.message ?: "Unable to prepare camera image for OCR.")
            return
        }

        val session = try {
            synchronized(lock) {
                if (closed) {
                    null
                } else {
                    val selectedLanguage = languageTag
                    val recognizer = recognizerForLocked(selectedLanguage)
                    activeTasks += 1
                    AnalysisSession(
                        generation = generation,
                        languageTag = selectedLanguage,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        recognizer = recognizer
                    )
                }
            }
        } catch (error: Exception) {
            finishFrame(imageProxy)
            deliverError(null, error.message ?: "On-device OCR is unavailable.")
            return
        }

        if (session == null) {
            finishFrame(imageProxy)
            return
        }

        try {
            session.recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    deliver(session) {
                        onResult(text.toDomain(session.languageTag, session.imageWidth, session.imageHeight))
                    }
                }
                .addOnFailureListener { error ->
                    deliver(session) {
                        onError(error.message ?: "On-device OCR failed.")
                    }
                }
                .addOnCompleteListener {
                    finishTask(session, imageProxy)
                }
        } catch (error: Exception) {
            finishTask(session, imageProxy)
            deliverError(session, error.message ?: "On-device OCR failed.")
        }
    }

    /** Invalidates callbacks immediately and closes ML Kit clients after pending frames finish. */
    fun close() {
        val clientsToClose = synchronized(lock) {
            generation += 1
            closed = true
            closeRequested = true
            onResult = {}
            onError = {}
            if (activeTasks == 0) takeRecognizersLocked() else emptyList()
        }
        clientsToClose.forEach { client -> runCatching { client.close() } }
    }

    private fun finishTask(session: AnalysisSession, imageProxy: ImageProxy) {
        finishFrame(imageProxy)
        val clientsToClose = synchronized(lock) {
            activeTasks = (activeTasks - 1).coerceAtLeast(0)
            if (activeTasks == 0 && closeRequested) takeRecognizersLocked() else emptyList()
        }
        clientsToClose.forEach { client -> runCatching { client.close() } }
    }

    private fun finishFrame(imageProxy: ImageProxy) {
        frameInFlight.set(false)
        runCatching { imageProxy.close() }
    }

    private fun deliver(session: AnalysisSession, callback: () -> Unit) {
        synchronized(lock) {
            if (!closed && session.generation == generation) callback()
        }
    }

    private fun deliverError(session: AnalysisSession?, message: String) {
        if (session == null) {
            synchronized(lock) {
                if (!closed) onError(message)
            }
        } else {
            deliver(session) { onError(message) }
        }
    }

    private fun recognizerForLocked(languageTag: String): TextRecognizer {
        val kind = RecognizerKind.from(languageTag)
        return recognizers.getOrPut(kind) {
            when (kind) {
                RecognizerKind.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                RecognizerKind.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                RecognizerKind.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                RecognizerKind.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    private fun takeRecognizersLocked(): List<TextRecognizer> {
        closeRequested = false
        val clients = recognizers.values.toList()
        recognizers.clear()
        return clients
    }

    private fun normalizeLanguageTag(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() }
        ?: DEFAULT_LANGUAGE

    private fun Text.toDomain(languageTag: String, imageWidth: Int, imageHeight: Int): OcrResult = OcrResult(
        text = text,
        blocks = textBlocks.flatMap { block -> block.lines.mapNotNull { line -> line.toDomain() } },
        languageTag = languageTag,
        imageWidth = imageWidth,
        imageHeight = imageHeight
    )

    private fun Text.Line.toDomain(): OcrTextBlock? {
        val box = boundingBox ?: return null
        return OcrTextBlock(
            text = text,
            left = box.left.toFloat(),
            top = box.top.toFloat(),
            right = box.right.toFloat(),
            bottom = box.bottom.toFloat()
        )
    }

    private data class AnalysisSession(
        val generation: Long,
        val languageTag: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val recognizer: TextRecognizer
    )

    private enum class RecognizerKind {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN;

        companion object {
            fun from(languageTag: String): RecognizerKind = when (languageTag.lowercase(Locale.ROOT)) {
                "zh", "zh-cn", "zh-tw" -> CHINESE
                "ja" -> JAPANESE
                "ko" -> KOREAN
                else -> LATIN
            }
        }
    }

    companion object {
        private const val DEFAULT_LANGUAGE = "en"
    }
}

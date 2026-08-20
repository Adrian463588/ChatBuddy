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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraOcrAnalyzer @Inject constructor() : ImageAnalysis.Analyzer {
    private val recognizers = mutableMapOf<RecognizerKind, TextRecognizer>()
    @Volatile
    private var languageTag: String = "en"
    private var onResult: (OcrResult) -> Unit = {}
    private var onError: (String) -> Unit = {}

    fun setCallbacks(
        onResult: (OcrResult) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onResult = onResult
        this.onError = onError
    }

    fun setLanguageTag(languageTag: String) {
        this.languageTag = languageTag.ifBlank { "en" }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val selectedLanguageTag = languageTag
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizerFor(selectedLanguageTag).process(inputImage)
                .addOnSuccessListener { text -> onResult(text.toDomain(selectedLanguageTag)) }
                .addOnFailureListener { error -> onError(error.message ?: "On-device OCR failed.") }
                .addOnCompleteListener { imageProxy.close() }
        } catch (error: Exception) {
            imageProxy.close()
            onError(error.message ?: "On-device OCR failed.")
        }
    }

    fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        recognizers.clear()
    }

    private fun recognizerFor(languageTag: String): TextRecognizer {
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

    private fun Text.toDomain(languageTag: String): OcrResult = OcrResult(
        text = text,
        blocks = textBlocks.flatMap { block -> block.lines.mapNotNull { line -> line.toDomain() } },
        languageTag = languageTag
    )

    private fun Text.Line.toDomain(): OcrTextBlock? {
        val box = boundingBox ?: return null
        return OcrTextBlock(text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
    }

    private enum class RecognizerKind {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN;

        companion object {
            fun from(languageTag: String): RecognizerKind = when (languageTag.lowercase()) {
                "zh", "zh-cn", "zh-tw" -> CHINESE
                "ja" -> JAPANESE
                "ko" -> KOREAN
                else -> LATIN
            }
        }
    }
}

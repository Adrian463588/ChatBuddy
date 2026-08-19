package com.chatbuddy.data.repository

import android.annotation.SuppressLint
import android.content.Context
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.OcrTextBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraOcrAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @SuppressLint("UnsafeOptInUsageError")
    fun analyze(imageProxy: ImageProxy, onResult: (OcrResult) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(inputImage)
            .addOnSuccessListener { text -> onResult(text.toDomain()) }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() = recognizer.close()

    private fun Text.toDomain(): OcrResult = OcrResult(
        text = text,
        blocks = textBlocks.flatMap { block -> block.lines.mapNotNull { line -> line.toDomain() } },
        languageTag = "en"
    )

    private fun Text.Line.toDomain(): OcrTextBlock? {
        val box = boundingBox ?: return null
        return OcrTextBlock(text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
    }
}

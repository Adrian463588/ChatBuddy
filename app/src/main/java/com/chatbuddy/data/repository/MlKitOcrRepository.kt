package com.chatbuddy.data.repository

import android.content.Context
import android.net.Uri
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.OcrTextBlock
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.utils.awaitTask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Singleton
class MlKitOcrRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrRepository {
    override suspend fun recognizeImage(uri: String, languageTag: String): AppResult<OcrResult> =
        withContext(Dispatchers.IO) {
            var recognizer: TextRecognizer? = null
            try {
                recognizer = when (languageTag.lowercase()) {
                    "zh", "zh-cn", "zh-tw" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }
                val image = InputImage.fromFilePath(context, Uri.parse(uri))
                val result = recognizer.process(image).awaitTask()
                AppResult.Success(result.toDomain(languageTag))
            } catch (error: Exception) {
                AppResult.Error("On-device OCR failed", error)
            } finally {
                recognizer?.close()
            }
        }

    private fun Text.toDomain(languageTag: String): OcrResult {
        val blocks = textBlocks.flatMap { block -> block.lines.mapNotNull { line -> line.toDomain() } }
        return OcrResult(text = text, blocks = blocks, languageTag = languageTag)
    }

    private fun Text.Line.toDomain(): OcrTextBlock? {
        val box = boundingBox ?: return null
        return OcrTextBlock(text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
    }
}

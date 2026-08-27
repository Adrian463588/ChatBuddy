package com.chatbuddy.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
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
import kotlinx.coroutines.CancellationException
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
                val imageUri = Uri.parse(uri)
                val image = InputImage.fromFilePath(context, imageUri)
                val (imageWidth, imageHeight) = readImageDimensions(imageUri)
                val result = recognizer.process(image).awaitTask()
                AppResult.Success(result.toDomain(languageTag, imageWidth, imageHeight))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("On-device OCR failed", error)
            } finally {
                recognizer?.close()
            }
        }

    private fun Text.toDomain(languageTag: String, imageWidth: Int, imageHeight: Int): OcrResult {
        val blocks = textBlocks.flatMap { block -> block.lines.mapNotNull { line -> line.toDomain() } }
        return OcrResult(
            text = text,
            blocks = blocks,
            languageTag = languageTag,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    private fun readImageDimensions(uri: Uri): Pair<Int, Int> = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
        }
        val orientation = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val width = options.outWidth.coerceAtLeast(0)
        val height = options.outHeight.coerceAtLeast(0)
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            height to width
        } else {
            width to height
        }
    }.getOrDefault(0 to 0)

    private fun Text.Line.toDomain(): OcrTextBlock? {
        val box = boundingBox ?: return null
        return OcrTextBlock(text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
    }
}

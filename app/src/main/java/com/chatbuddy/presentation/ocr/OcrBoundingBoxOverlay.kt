package com.chatbuddy.presentation.ocr

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.OcrResult
import kotlin.math.max

@Composable
fun OcrBoundingBoxOverlay(
    result: OcrResult?,
    modifier: Modifier = Modifier
) {
    val imageWidth = result?.imageWidth?.toFloat() ?: 0f
    val imageHeight = result?.imageHeight?.toFloat() ?: 0f
    val blocks = result?.blocks.orEmpty()
    if (imageWidth <= 0f || imageHeight <= 0f || blocks.isEmpty()) return

    val overlayColor = MaterialTheme.colorScheme.tertiary
    Canvas(
        modifier = modifier.semantics {
            contentDescription = "OCR detected text regions"
        }
    ) {
        val scale = max(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f
        val strokeWidth = 2.dp.toPx()

        blocks.forEach { block ->
            val left = (block.left * scale + offsetX).coerceIn(0f, size.width)
            val top = (block.top * scale + offsetY).coerceIn(0f, size.height)
            val right = (block.right * scale + offsetX).coerceIn(0f, size.width)
            val bottom = (block.bottom * scale + offsetY).coerceIn(0f, size.height)
            if (right > left && bottom > top) {
                drawRoundRect(
                    color = overlayColor,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }
}

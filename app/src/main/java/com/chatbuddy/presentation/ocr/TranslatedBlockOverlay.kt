package com.chatbuddy.presentation.ocr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun TranslatedBlockOverlay(
    result: OcrResult,
    blocks: List<TranslatedBlock>,
    modifier: Modifier = Modifier
) {
    if (result.imageWidth <= 0 || result.imageHeight <= 0 || blocks.isEmpty()) return
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Translated text overlay" }
    ) {
        val scale = max(
            constraints.maxWidth.toFloat() / result.imageWidth,
            constraints.maxHeight.toFloat() / result.imageHeight
        )
        val offsetX = (constraints.maxWidth - result.imageWidth * scale) / 2f
        val offsetY = (constraints.maxHeight - result.imageHeight * scale) / 2f
        blocks.forEach { translated ->
            val source = translated.source
            val left = source.left * scale + offsetX
            val top = source.top * scale + offsetY
            Box(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .semantics {
                        contentDescription = "Translated text: ${translated.translatedText}"
                    }
            ) {
                Text(
                    translated.translatedText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        }
    }
}

package com.chatbuddy.presentation.ocr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Google Lens-style AR text replacement overlay.
 * Renders translated text cleanly over detected text bounding boxes in real time.
 */
@Composable
fun TranslatedBlockOverlay(
    result: OcrResult,
    blocks: List<TranslatedBlock>,
    modifier: Modifier = Modifier
) {
    if (result.imageWidth <= 0 || result.imageHeight <= 0 || blocks.isEmpty()) return

    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Live translated text overlay" }
    ) {
        val scale = max(
            constraints.maxWidth.toFloat() / result.imageWidth,
            constraints.maxHeight.toFloat() / result.imageHeight
        )
        val offsetX = (constraints.maxWidth - result.imageWidth * scale) / 2f
        val offsetY = (constraints.maxHeight - result.imageHeight * scale) / 2f

        blocks.forEach { translated ->
            if (translated.translatedText.isBlank() || translated.translatedText.trim() == translated.source.text.trim()) {
                return@forEach
            }
            val source = translated.source
            val leftPx = (source.left * scale + offsetX).coerceAtLeast(0f)
            val topPx = (source.top * scale + offsetY).coerceAtLeast(0f)
            val rightPx = (source.right * scale + offsetX).coerceAtMost(constraints.maxWidth.toFloat())
            val bottomPx = (source.bottom * scale + offsetY).coerceAtMost(constraints.maxHeight.toFloat())

            val boxWidthPx = (rightPx - leftPx).coerceAtLeast(10f)
            val boxHeightPx = (bottomPx - topPx).coerceAtLeast(10f)

            val boxWidthDp = with(density) { boxWidthPx.toDp() }
            val boxHeightDp = with(density) { boxHeightPx.toDp() }
            val dynamicFontSize = ((boxHeightPx * 0.62f) / density.density).coerceIn(10f, 16f).sp

            Box(
                modifier = Modifier
                    .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                    .defaultMinSize(minWidth = boxWidthDp, minHeight = boxHeightDp)
                    .widthIn(max = boxWidthDp.coerceAtLeast(120.dp))
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .semantics {
                        contentDescription = "Translated text: ${translated.translatedText}"
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = translated.translatedText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = dynamicFontSize,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = (dynamicFontSize.value * 1.2f).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

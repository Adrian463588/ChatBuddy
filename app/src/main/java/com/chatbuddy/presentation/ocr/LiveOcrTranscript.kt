package com.chatbuddy.presentation.ocr

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.presentation.translate.TranslationUiState

@Composable
internal fun LiveOcrTranscript(
    result: OcrResult?,
    translationState: TranslationUiState,
    translationProvider: String?,
    onDownloadTranslation: () -> Unit,
    onStopCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transcript = result?.text?.trim().orEmpty()
    val translation = translationState.result
        ?.takeIf { translationState.sourceText.trim() == transcript }
        ?.text
        ?.trim()
        .orEmpty()
    val accessibilityText = if (transcript.isBlank()) {
        "Live OCR transcript: scanning"
    } else {
        "Live OCR transcript: $transcript"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = accessibilityText
            },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LiveTranscriptStatus(transcript)
                        TextButton(
                            onClick = onStopCamera,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop camera")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveTranscriptStatus(
                            transcript = transcript,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onStopCamera) {
                            Text("Stop camera")
                        }
                    }
                }
            }
            if (transcript.isBlank()) {
                Text(
                    text = "Point the camera at text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                when {
                    translationState.loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    translation.isNotBlank() -> {
                        Text(
                            text = "Translation",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = translation,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        translationProvider?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    translationState.error != null -> Text(
                        text = translationState.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    !translationState.modelReady &&
                        !translationState.modelChecking &&
                        !translationState.modelDownloading -> {
                        Text(
                            text = "Translation pack is not ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onDownloadTranslation,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download translation pack")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTranscriptStatus(
    transcript: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Live transcript",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (transcript.isBlank()) "Scanning" else "Detected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

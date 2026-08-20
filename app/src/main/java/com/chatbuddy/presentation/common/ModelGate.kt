package com.chatbuddy.presentation.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.ModelStatus
import com.chatbuddy.utils.formatBytes

@Composable
fun ModelGate(
    status: ModelStatus,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onPause: () -> Unit = {},
    modelName: String? = null,
    content: @Composable () -> Unit
) {
    if (status is ModelStatus.Ready) {
        content()
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(titleFor(status), style = MaterialTheme.typography.titleLarge)
                when (status) {
                    ModelStatus.NotInstalled -> {
                        Text(
                            "Download ${modelName ?: "the local model"} to unlock offline chat. " +
                                "It will be saved in your selected SAF folder.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        ModelActionButton(
                            label = "Download local model",
                            accessibilityLabel = "Download local AI model",
                            onClick = onDownload
                        )
                    }
                    is ModelStatus.Queued -> {
                        Text("The download is queued and will resume automatically when a connection is available.")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        ModelActionButton(
                            label = "Pause download",
                            accessibilityLabel = "Pause local AI model download",
                            onClick = onPause,
                            outlined = true
                        )
                    }
                    is ModelStatus.Downloading -> {
                        Text("Downloading ${modelName ?: "local model"}…")
                        DownloadProgress(
                            downloadedBytes = status.downloadedBytes,
                            totalBytes = status.totalBytes
                        )
                        ModelActionButton(
                            label = "Pause download",
                            accessibilityLabel = "Pause local AI model download",
                            onClick = onPause,
                            outlined = true
                        )
                    }
                    is ModelStatus.Paused -> {
                        Text("The download is paused and can be resumed from this screen.")
                        DownloadProgress(
                            downloadedBytes = status.downloadedBytes,
                            totalBytes = status.totalBytes
                        )
                        ModelActionButton(
                            label = "Resume download",
                            accessibilityLabel = "Resume local AI model download",
                            onClick = onDownload
                        )
                    }
                    is ModelStatus.Verifying -> {
                        Text("Checking the SHA-256 checksum before enabling local chat.")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is ModelStatus.Error -> {
                        Text(status.message, color = MaterialTheme.colorScheme.error)
                        ModelActionButton(
                            label = "Retry download",
                            accessibilityLabel = "Retry local AI model download",
                            onClick = onDownload
                        )
                    }
                    ModelStatus.Unavailable -> {
                        Text("The local model runtime is unavailable for this device.")
                    }
                    is ModelStatus.Ready -> Unit
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(downloadedBytes: Long, totalBytes: Long) {
    val target = if (totalBytes > 0L) {
        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progress by animateFloatAsState(targetValue = target, label = "model download progress")
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            "${(progress * 100).toInt()}% · ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ModelActionButton(
    label: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    val modifier = Modifier
        .fillMaxWidth()
        .semantics { contentDescription = accessibilityLabel }
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

private fun titleFor(status: ModelStatus): String = when (status) {
    ModelStatus.NotInstalled -> "Local chat is unavailable"
    is ModelStatus.Queued -> "Download queued"
    is ModelStatus.Downloading -> "Downloading local model"
    is ModelStatus.Paused -> "Download paused"
    is ModelStatus.Verifying -> "Checking model integrity"
    is ModelStatus.Error -> "Model download needs attention"
    ModelStatus.Unavailable -> "Local model unavailable"
    is ModelStatus.Ready -> "Local model ready"
}

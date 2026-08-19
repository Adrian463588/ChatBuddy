package com.chatbuddy.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.ModelStatus

@Composable
fun ModelGate(
    status: ModelStatus,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (status is ModelStatus.Ready) {
        content()
    } else {
        val message = when (status) {
            ModelStatus.NotInstalled -> "Local model is not installed"
            is ModelStatus.Downloading -> "Model download in progress"
            is ModelStatus.Paused -> "Model download paused"
            is ModelStatus.Verifying -> "Verifying model integrity"
            is ModelStatus.Error -> status.message
            ModelStatus.Unavailable -> "Local model runtime is unavailable"
            is ModelStatus.Ready -> ""
        }
        Card(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Local AI setup required", style = MaterialTheme.typography.titleLarge)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = onDownload,
                    enabled = status is ModelStatus.NotInstalled || status is ModelStatus.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Download local AI model" }
                ) {
                    Text("Download model")
                }
            }
        }
    }
}

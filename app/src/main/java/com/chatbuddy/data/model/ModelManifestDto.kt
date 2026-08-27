package com.chatbuddy.data.model

import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelStorageKind
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class ModelManifestDto(
    val schemaVersion: Int,
    val artifacts: List<ModelArtifactDto>
)

@Serializable
data class ModelArtifactDto(
    val id: String,
    val displayName: String,
    val url: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val abi: List<String>,
    val storageKind: String,
    val format: String = "binary",
    val mimeType: String = "application/octet-stream"
) {
    fun toDomain(): ModelArtifact {
        require(id.isNotBlank()) { "Model manifest id is empty" }
        require(displayName.isNotBlank()) { "Model manifest display name is empty" }
        require(revision.isNotBlank()) { "Model manifest revision is empty" }
        require(sizeBytes > 0L) { "Model manifest size must be greater than zero" }
        require(SHA256_PATTERN.matches(sha256.trim().lowercase(Locale.US))) {
            "Model manifest SHA-256 is invalid for $id"
        }
        require(license.isNotBlank()) { "Model manifest license is empty for $id" }
        require(abi.isNotEmpty() && abi.none { it.isBlank() }) {
            "Model manifest ABI is empty for $id"
        }
        require(url.startsWith("https://", ignoreCase = true)) {
            "Model manifest URL must use HTTPS for $id"
        }
        require(format.isNotBlank()) { "Model manifest format is empty for $id" }
        require(mimeType.isNotBlank()) { "Model manifest MIME type is empty for $id" }
        val parsedStorageKind = runCatching { ModelStorageKind.valueOf(storageKind) }
            .getOrElse { error("Unknown model storage kind '$storageKind' for $id") }
        return ModelArtifact(
            id = id,
            displayName = displayName,
            url = url,
            revision = revision,
            sizeBytes = sizeBytes,
            sha256 = sha256.trim().lowercase(Locale.US),
            license = license,
            abi = abi.toSet(),
            storageKind = parsedStorageKind,
            format = format,
            mimeType = mimeType
        )
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

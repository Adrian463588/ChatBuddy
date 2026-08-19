package com.chatbuddy.data.model

import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelStorageKind
import kotlinx.serialization.Serializable

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
    val storageKind: String
) {
    fun toDomain(): ModelArtifact = ModelArtifact(
        id = id,
        displayName = displayName,
        url = url,
        revision = revision,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        license = license,
        abi = abi.toSet(),
        storageKind = ModelStorageKind.valueOf(storageKind)
    )
}

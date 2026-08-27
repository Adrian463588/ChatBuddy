package com.chatbuddy.data.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManifestDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = false }

    fun read(): List<com.chatbuddy.domain.model.ModelArtifact> {
        val manifest = context.assets
            .open("model-manifest.json")
            .bufferedReader()
            .use { reader -> json.decodeFromString<ModelManifestDto>(reader.readText()) }
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported model manifest schema ${manifest.schemaVersion}"
        }
        val artifacts = manifest.artifacts.map(ModelArtifactDto::toDomain)
        require(artifacts.map { it.id }.toSet().size == artifacts.size) {
            "Model manifest contains duplicate artifact ids"
        }
        return artifacts
    }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

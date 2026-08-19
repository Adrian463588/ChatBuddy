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

    fun read(): List<com.chatbuddy.domain.model.ModelArtifact> = context.assets
        .open("model-manifest.json")
        .bufferedReader()
        .use { reader -> json.decodeFromString<ModelManifestDto>(reader.readText()) }
        .artifacts
        .map(ModelArtifactDto::toDomain)
}

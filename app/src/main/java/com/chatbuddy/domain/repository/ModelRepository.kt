package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelState
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun hasStorageFolder(): Boolean
    suspend fun listArtifacts(): AppResult<List<ModelArtifact>>
    fun observeStates(): Flow<List<ModelState>>
    suspend fun selectStorageFolder(treeUri: String): AppResult<Unit>
    suspend fun download(artifactId: String): AppResult<Unit>
    suspend fun pause(artifactId: String): AppResult<Unit>
    suspend fun verify(artifactId: String): AppResult<Unit>
}

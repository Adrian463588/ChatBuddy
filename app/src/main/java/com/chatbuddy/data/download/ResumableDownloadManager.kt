package com.chatbuddy.data.download

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class ResumableDownloadManager @Inject constructor(
    private val client: OkHttpClient,
    private val store: SafModelStore,
    private val stateStore: ModelStateStore
) {
    suspend fun download(
        artifact: ModelArtifact,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (store.treeUri() == null) {
                return@withContext failure(artifact.id, "Choose a SAF storage folder before downloading")
            }
            val fileName = fileName(artifact.url, artifact.id)
            val existingFinal = store.finalFile(artifact.id, fileName)
            if (existingFinal != null && store.fileLength(existingFinal) == artifact.sizeBytes) {
                if (sha256(existingFinal) == artifact.sha256) {
                    stateStore.update(artifact.id, ModelStatus.Ready(artifact.storageKind))
                    return@withContext AppResult.Success(Unit)
                }
                store.delete(existingFinal)
            }

            val tempPair = store.openTemp(artifact.id, fileName)
                ?: return@withContext failure(artifact.id, "Unable to create model temporary file in SAF")
            val temp = tempPair.first
            tempPair.second.use { output ->
                var offset = store.fileLength(temp)
                store.saveCheckpoint(artifact.id, offset)
                onProgress(offset, artifact.sizeBytes)
                if (offset > artifact.sizeBytes) {
                    store.delete(temp)
                    store.clearCheckpoint(artifact.id)
                    return@withContext failure(artifact.id, "Temporary model file is larger than manifest size")
                }
                val response = request(artifact, offset)
                response.use { bodyResponse ->
                    if (offset > 0L && bodyResponse.code == 200) {
                        output.close()
                        store.delete(temp)
                        store.clearCheckpoint(artifact.id)
                        return@withContext download(artifact, onProgress)
                    }
                    if (bodyResponse.code != 200 && bodyResponse.code != 206) {
                        return@withContext failure(
                            artifact.id,
                            "Model download failed with HTTP ${bodyResponse.code}"
                        )
                    }
                    if (offset > 0L) {
                        val start = bodyResponse.header("Content-Range")
                            ?.substringAfter("bytes ")
                            ?.substringBefore("-")
                            ?.toLongOrNull()
                        if (bodyResponse.code != 206 || start != offset) {
                            return@withContext failure(artifact.id, "Server did not honor the requested byte range")
                        }
                    }
                    val responseBody = bodyResponse.body
                        ?: return@withContext failure(artifact.id, "Model download returned an empty body")
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            try {
                                output.write(buffer, 0, read)
                                output.flush()
                            } catch (error: IOException) {
                                throw SafStorageException("Unable to write model to SAF storage", error)
                            }
                            offset += read
                            store.saveCheckpoint(artifact.id, offset)
                            stateStore.update(
                                artifact.id,
                                ModelStatus.Downloading(offset, artifact.sizeBytes)
                            )
                            onProgress(offset, artifact.sizeBytes)
                        }
                    }
                }
            }

            if (store.fileLength(temp) != artifact.sizeBytes) {
                stateStore.update(artifact.id, ModelStatus.Error("Downloaded size does not match manifest"))
                return@withContext AppResult.Error("Downloaded size does not match manifest")
            }
            stateStore.update(artifact.id, ModelStatus.Verifying(artifact.sizeBytes, artifact.sizeBytes))
            if (sha256(temp) != artifact.sha256) {
                store.delete(temp)
                stateStore.update(artifact.id, ModelStatus.Error("SHA-256 verification failed"))
                return@withContext AppResult.Error("SHA-256 verification failed")
            }
            store.renameTemp(temp, fileName)
                ?: return@withContext failure(artifact.id, "Unable to atomically rename verified model")
            store.clearCheckpoint(artifact.id)
            stateStore.update(artifact.id, ModelStatus.Ready(artifact.storageKind))
            AppResult.Success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: SafStorageException) {
            val message = error.message ?: "Unable to use model SAF storage"
            stateStore.update(artifact.id, ModelStatus.Error(message))
            AppResult.Error(message, error)
        } catch (error: IOException) {
            stateStore.update(artifact.id, ModelStatus.Error("Network interrupted; retry will resume"))
            AppResult.Error("Network interrupted; retry will resume", error)
        } catch (error: Exception) {
            stateStore.update(artifact.id, ModelStatus.Error("Model download failed: ${error.message}"))
            AppResult.Error("Model download failed", error)
        }
    }

    suspend fun verify(artifact: ModelArtifact): AppResult<Unit> = withContext(Dispatchers.IO) {
        val file = store.finalFile(artifact.id, fileName(artifact.url, artifact.id))
            ?: return@withContext AppResult.Error("Model file is not present in SAF")
        if (store.fileLength(file) != artifact.sizeBytes || sha256(file) != artifact.sha256) {
            stateStore.update(artifact.id, ModelStatus.Error("Model file failed manifest verification"))
            return@withContext AppResult.Error("Model file failed manifest verification")
        }
        stateStore.update(artifact.id, ModelStatus.Ready(artifact.storageKind))
        AppResult.Success(Unit)
    }

    private fun request(artifact: ModelArtifact, offset: Long) = client.newCall(
        Request.Builder()
            .url(artifact.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
    ).execute()

    private fun failure(artifactId: String, message: String): AppResult<Unit> {
        stateStore.update(artifactId, ModelStatus.Error(message))
        return AppResult.Error(message)
    }

    private fun sha256(file: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            store.openInput(file)?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: throw SafStorageException("Unable to read SAF model file")
        } catch (error: SafStorageException) {
            throw error
        } catch (error: IOException) {
            throw SafStorageException("Unable to read SAF model file", error)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private class SafStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun fileName(url: String, fallback: String): String =
        Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}

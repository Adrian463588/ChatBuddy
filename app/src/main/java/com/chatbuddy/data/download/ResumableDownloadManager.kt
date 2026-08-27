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
import java.util.Locale
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
            when (val storage = store.validateTree()) {
                is AppResult.Error -> return@withContext failure(
                    artifact.id,
                    storage.message,
                    storage.cause
                )

                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext failure(
                    artifact.id,
                    "SAF storage validation is still running"
                )
            }
            validateArtifact(artifact)

            val fileName = store.fileName(artifact)
            val existingFinal = store.finalFile(artifact.id, fileName)
            if (existingFinal != null) {
                val finalLength = store.fileLength(existingFinal)
                if (finalLength == artifact.sizeBytes && sha256(existingFinal) == expectedSha(artifact)) {
                    store.clearCheckpoint(artifact.id)
                    stateStore.update(artifact.id, ModelStatus.Ready(artifact.storageKind))
                    return@withContext AppResult.Success(Unit)
                }
                // A final file that fails either size or checksum must never be
                // opened by a model runtime. It is app-owned inside ChatBuddyModels.
                store.delete(existingFinal)
            }

            val tempPair = store.openTemp(artifact.id, fileName)
                ?: return@withContext failure(
                    artifact.id,
                    "Unable to create model temporary file in SAF"
                )
            val temp = tempPair.first
            var restartFromZero = false
            var completeTemporaryFile = false
            var invalidTemporaryFile = false
            tempPair.second.use { output ->
                val actualOffset = store.fileLength(temp)
                if (actualOffset < 0L) {
                    throw SafStorageException("Unable to determine temporary model file size")
                }
                // The provider's actual file length is the source of truth. A
                // checkpoint can lag after a process kill, so it is reconciled to
                // the durable SAF bytes before constructing Range.
                if (store.checkpoint(artifact) != actualOffset) {
                    store.saveCheckpoint(artifact, actualOffset)
                }
                if (actualOffset > artifact.sizeBytes) {
                    invalidTemporaryFile = true
                    return@use
                }
                stateStore.update(
                    artifact.id,
                    ModelStatus.Downloading(actualOffset, artifact.sizeBytes)
                )
                onProgress(actualOffset, artifact.sizeBytes)
                if (actualOffset == artifact.sizeBytes) {
                    completeTemporaryFile = true
                    return@use
                }

                when (downloadResponse(artifact, actualOffset, output, onProgress)) {
                    DownloadAttempt.RestartFromZero -> restartFromZero = true
                    DownloadAttempt.Completed -> Unit
                }
            }

            if (invalidTemporaryFile) {
                store.delete(temp)
                store.clearCheckpoint(artifact.id)
                // The partial file is not trustworthy, but a fresh request is a
                // safe and recoverable outcome for the next attempt.
                return@withContext download(artifact, onProgress)
            }
            if (restartFromZero) {
                store.delete(temp)
                store.clearCheckpoint(artifact.id)
                return@withContext download(artifact, onProgress)
            }
            if (completeTemporaryFile) {
                return@withContext finalizeTemporaryFile(artifact, temp, fileName)
            }

            finalizeTemporaryFile(artifact, temp, fileName)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: RetryableDownloadException) {
            stateStore.update(artifact.id, ModelStatus.Error(error.message ?: "Download will retry"))
            AppResult.Error(error.message ?: "Network interrupted; retry will resume", error)
        } catch (error: NonRetryableDownloadException) {
            stateStore.update(artifact.id, ModelStatus.Error(error.message ?: "Model download failed"))
            AppResult.Error(error.message ?: "Model download failed", error)
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

    private suspend fun downloadResponse(
        artifact: ModelArtifact,
        offset: Long,
        output: java.io.OutputStream,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): DownloadAttempt {
        request(artifact, offset).use { response ->
            val plan = DownloadProtocol.plan(
                offset = offset,
                totalBytes = artifact.sizeBytes,
                responseCode = response.code,
                contentRange = response.header("Content-Range"),
                contentLength = response.body?.contentLength() ?: -1L
            )
            if (plan is DownloadResponsePlan.RestartFromZero) {
                return DownloadAttempt.RestartFromZero
            }

            val body = response.body
                ?: throw RetryableDownloadException("Model download returned an empty body")
            val appendPlan = plan as? DownloadResponsePlan.Append
                ?: throw NonRetryableDownloadException("Download response plan is invalid")
            val expectedBodyBytes = appendPlan.expectedBodyBytes
            var receivedBodyBytes = 0L
            var downloadedBytes = offset
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    val readBytes = read.toLong()
                    if (receivedBodyBytes + readBytes > expectedBodyBytes) {
                        throw NonRetryableDownloadException(
                            "Download stream exceeded the declared response range"
                        )
                    }
                    if (downloadedBytes + readBytes > artifact.sizeBytes) {
                        throw NonRetryableDownloadException(
                            "Download stream exceeded the manifest size"
                        )
                    }
                    try {
                        output.write(buffer, 0, read)
                        output.flush()
                    } catch (error: IOException) {
                        throw SafStorageException(
                            "Unable to write model to SAF storage",
                            error
                        )
                    }
                    receivedBodyBytes += readBytes
                    downloadedBytes += readBytes
                    store.saveCheckpoint(artifact, downloadedBytes)
                    stateStore.update(
                        artifact.id,
                        ModelStatus.Downloading(downloadedBytes, artifact.sizeBytes)
                    )
                    onProgress(downloadedBytes, artifact.sizeBytes)
                }
            }
            DownloadProtocol.validateBodyBytes(expectedBodyBytes, receivedBodyBytes)
            if (downloadedBytes < artifact.sizeBytes) {
                // A valid partial 206 response can cover only part of the
                // remaining range. Preserve it and let WorkManager retry with a
                // new Range request from this exact offset.
                throw RetryableDownloadException(
                    "Server returned only $downloadedBytes of ${artifact.sizeBytes} bytes"
                )
            }
            return DownloadAttempt.Completed
        }
    }

    private fun finalizeTemporaryFile(
        artifact: ModelArtifact,
        temp: DocumentFile,
        fileName: String
    ): AppResult<Unit> {
        if (store.fileLength(temp) != artifact.sizeBytes) {
            stateStore.update(artifact.id, ModelStatus.Error("Downloaded size does not match manifest"))
            return AppResult.Error("Downloaded size does not match manifest")
        }
        stateStore.update(artifact.id, ModelStatus.Verifying(artifact.sizeBytes, artifact.sizeBytes))
        if (sha256(temp) != expectedSha(artifact)) {
            store.delete(temp)
            store.clearCheckpoint(artifact.id)
            stateStore.update(artifact.id, ModelStatus.Error("SHA-256 verification failed"))
            return AppResult.Error("SHA-256 verification failed")
        }
        store.renameTemp(temp, fileName)
            ?: return failure(artifact.id, "Unable to atomically rename verified model")
        store.clearCheckpoint(artifact.id)
        stateStore.update(artifact.id, ModelStatus.Ready(artifact.storageKind))
        return AppResult.Success(Unit)
    }

    suspend fun verify(artifact: ModelArtifact): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            when (val storage = store.validateTree()) {
                is AppResult.Error -> return@withContext failure(
                    artifact.id,
                    storage.message,
                    storage.cause
                )

                is AppResult.Success -> Unit
                AppResult.Loading -> return@withContext failure(
                    artifact.id,
                    "SAF storage validation is still running"
                )
            }
            validateArtifact(artifact)
            val file = store.finalFile(artifact)
                ?: return@withContext failure(
                    artifact.id,
                    "Model file is not present in SAF"
                )
            if (store.fileLength(file) != artifact.sizeBytes || sha256(file) != expectedSha(artifact)) {
                store.delete(file)
                store.clearCheckpoint(artifact.id)
                stateStore.update(artifact.id, ModelStatus.Error("Model file failed manifest verification"))
                return@withContext AppResult.Error("Model file failed manifest verification")
            }
            stateStore.update(artifact.id, ModelStatus.Ready(artifact.storageKind))
            AppResult.Success(Unit)
        } catch (error: SafStorageException) {
            failure(artifact.id, error.message ?: "Unable to verify model in SAF", error)
        } catch (error: Exception) {
            failure(artifact.id, "Unable to verify model", error)
        }
    }

    private fun request(artifact: ModelArtifact, offset: Long) = client.newCall(
        Request.Builder()
            .url(artifact.url)
            .header("Accept", "application/octet-stream")
            .header("Accept-Encoding", "identity")
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
            .build()
    ).execute()

    private fun validateArtifact(artifact: ModelArtifact) {
        if (artifact.sizeBytes <= 0L) {
            throw NonRetryableDownloadException("Model manifest size is invalid")
        }
        if (!SHA256_PATTERN.matches(expectedSha(artifact))) {
            throw NonRetryableDownloadException("Model manifest SHA-256 is invalid")
        }
        val parsed = Uri.parse(artifact.url)
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) {
            throw NonRetryableDownloadException("Model manifest URL must use HTTPS")
        }
    }

    private fun failure(
        artifactId: String,
        message: String,
        cause: Throwable? = null
    ): AppResult<Unit> {
        stateStore.update(artifactId, ModelStatus.Error(message))
        return AppResult.Error(message, cause)
    }

    private fun sha256(file: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            store.openInput(file)?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            } ?: throw SafStorageException("Unable to read SAF model file")
        } catch (error: SafStorageException) {
            throw error
        } catch (error: IOException) {
            throw SafStorageException("Unable to read SAF model file", error)
        } catch (error: SecurityException) {
            throw SafStorageException("Unable to read SAF model file", error)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }

    private fun expectedSha(artifact: ModelArtifact): String =
        artifact.sha256.trim().lowercase(Locale.US)

    private sealed interface DownloadAttempt {
        data object RestartFromZero : DownloadAttempt
        data object Completed : DownloadAttempt
    }

    private class SafStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

package com.chatbuddy.data.download

import android.content.Context
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelCacheState
import com.chatbuddy.domain.model.ModelCacheStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class CachedModelFile(
    val file: File,
    val hit: Boolean
)

@Singleton
class ModelRuntimeCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safModelStore: SafModelStore
) {
    fun inspect(artifact: ModelArtifact): ModelCacheStatus {
        if (artifact.storageKind != com.chatbuddy.domain.model.ModelStorageKind.SAF_PERSISTENT) {
            return ModelCacheStatus(
                artifactId = artifact.id,
                displayName = artifact.displayName,
                state = ModelCacheState.UNAVAILABLE,
                detail = "Cache is only used for SAF-persistent artifacts"
            )
        }
        val expectedSha = artifact.sha256.trim().lowercase(Locale.US)
        if (artifact.sizeBytes <= 0L || !SHA256_PATTERN.matches(expectedSha)) {
            return ModelCacheStatus(
                artifactId = artifact.id,
                displayName = artifact.displayName,
                state = ModelCacheState.UNAVAILABLE,
                detail = "Manifest fingerprint is invalid"
            )
        }
        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        val extension = extensionFor(safModelStore.fileName(artifact))
        val cacheFile = File(directory, "$expectedSha.$extension")
        val metadataFile = File(directory, "$expectedSha.meta")
        val valid = isValid(
            cacheFile = cacheFile,
            metadataFile = metadataFile,
            artifact = artifact,
            expectedMetadata = metadataFor(artifact, expectedSha)
        )
        return ModelCacheStatus(
            artifactId = artifact.id,
            displayName = artifact.displayName,
            state = if (valid) ModelCacheState.HIT else ModelCacheState.MISS,
            detail = if (valid) {
                "Verified model copy is available in cacheDir"
            } else {
                "Cache will be rebuilt from the verified SAF file when the runtime loads"
            }
        )
    }

    /**
     * Materializes a verified SAF file into app-private cacheDir when possible.
     * A null payload is an intentional cache miss: callers must continue with
     * the verified SAF descriptor/stream. Cache pressure or provider failure is
     * never allowed to delete or replace the durable SAF source.
     */
    suspend fun prepare(
        artifact: ModelArtifact,
        source: DocumentFile
    ): AppResult<CachedModelFile?> = withContext(Dispatchers.IO) {
        AppResult.Success(runCatching { prepareBlocking(artifact, source) }
            .getOrNull())
    }

    private fun prepareBlocking(
        artifact: ModelArtifact,
        source: DocumentFile
    ): CachedModelFile? {
        val expectedSha = artifact.sha256.trim().lowercase(Locale.US)
        if (artifact.sizeBytes <= 0L || !SHA256_PATTERN.matches(expectedSha)) return null
        if (safModelStore.fileLength(source) != artifact.sizeBytes) return null

        val directory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) return null

        val extension = extensionFor(safModelStore.fileName(artifact))
        val cacheFile = File(directory, "$expectedSha.$extension")
        val metadataFile = File(directory, "$expectedSha.meta")
        val metadata = metadataFor(artifact, expectedSha)
        if (isValid(cacheFile, metadataFile, artifact, metadata)) {
            return CachedModelFile(cacheFile, hit = true)
        }

        cacheFile.delete()
        metadataFile.delete()

        val requiredBytes = artifact.sizeBytes + COPY_RESERVE_BYTES
        if (StatFs(directory.path).availableBytes < requiredBytes) return null

        val tempFile = File(directory, "$expectedSha.$extension.tmp")
        val tempMetadataFile = File(directory, "$expectedSha.meta.tmp")
        tempFile.delete()
        tempMetadataFile.delete()

        val sourceStream = safModelStore.openInput(source) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        val copied = runCatching {
            sourceStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    BufferedInputStream(input, COPY_BUFFER_BYTES).use { buffered ->
                        while (true) {
                            val read = buffered.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    output.fd.sync()
                    total
                }
            }
        }.getOrElse {
            tempFile.delete()
            return null
        }

        val actualSha = digest.digest().toHex()
        if (copied != artifact.sizeBytes || tempFile.length() != artifact.sizeBytes || actualSha != expectedSha) {
            tempFile.delete()
            return null
        }
        if (!atomicReplace(tempFile, cacheFile)) {
            tempFile.delete()
            return null
        }

        if (!writeMetadata(tempMetadataFile, metadata) || !atomicReplace(tempMetadataFile, metadataFile)) {
            tempMetadataFile.delete()
            cacheFile.delete()
            metadataFile.delete()
            return null
        }
        return CachedModelFile(cacheFile, hit = false)
    }

    private fun isValid(
        cacheFile: File,
        metadataFile: File,
        artifact: ModelArtifact,
        expectedMetadata: String
    ): Boolean = runCatching {
        cacheFile.isFile &&
            cacheFile.length() == artifact.sizeBytes &&
            metadataFile.isFile &&
            metadataFile.readText() == expectedMetadata
    }.getOrDefault(false)

    private fun writeMetadata(file: File, metadata: String): Boolean = runCatching {
        FileOutputStream(file).use { output ->
            output.write(metadata.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun atomicReplace(source: File, target: File): Boolean {
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                ATOMIC_MOVE,
                REPLACE_EXISTING
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            if (target.exists() && !target.delete()) false else source.renameTo(target)
        } catch (_: UnsupportedOperationException) {
            if (target.exists() && !target.delete()) false else source.renameTo(target)
        } catch (_: IOException) {
            false
        }
    }

    private fun metadataFor(artifact: ModelArtifact, expectedSha: String): String = buildString {
        append("version=2\n")
        append("artifact=").append(artifact.id).append('\n')
        append("size=").append(artifact.sizeBytes).append('\n')
        append("sha256=").append(expectedSha).append('\n')
        append("revision=").append(artifact.revision).append('\n')
        append("file=").append(safModelStore.fileName(artifact)).append('\n')
    }

    private fun extensionFor(fileName: String): String = fileName
        .substringAfterLast('.', missingDelimiterValue = "bin")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]"), "")
        .take(12)
        .takeIf { it.isNotBlank() }
        ?: "bin"

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (value in this@toHex) {
            append(HEX[value.toInt() ushr 4 and 0x0f])
            append(HEX[value.toInt() and 0x0f])
        }
    }

    companion object {
        private const val CACHE_DIRECTORY = "chatbuddy-model-cache"
        private const val COPY_RESERVE_BYTES = 16L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val HEX = "0123456789abcdef"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

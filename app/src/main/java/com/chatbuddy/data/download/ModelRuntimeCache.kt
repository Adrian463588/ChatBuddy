package com.chatbuddy.data.download

import android.content.Context
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
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
    suspend fun prepare(
        artifact: ModelArtifact,
        source: DocumentFile
    ): AppResult<CachedModelFile?> = withContext(Dispatchers.IO) {
        AppResult.Success(runCatching { prepareBlocking(artifact, source) }
            .getOrDefault(null))
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

        val cacheFile = File(directory, "$expectedSha.gguf")
        val metadataFile = File(directory, "$expectedSha.meta")
        val metadata = metadataFor(artifact, expectedSha)
        if (isValid(cacheFile, metadataFile, artifact, metadata)) {
            return CachedModelFile(cacheFile, hit = true)
        }

        cacheFile.delete()
        metadataFile.delete()

        val requiredBytes = artifact.sizeBytes + COPY_RESERVE_BYTES
        if (StatFs(directory.path).availableBytes < requiredBytes) return null

        val tempFile = File(directory, "$expectedSha.gguf.tmp")
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
        append("version=1\n")
        append("size=").append(artifact.sizeBytes).append('\n')
        append("sha256=").append(expectedSha).append('\n')
        append("revision=").append(artifact.revision).append('\n')
    }

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

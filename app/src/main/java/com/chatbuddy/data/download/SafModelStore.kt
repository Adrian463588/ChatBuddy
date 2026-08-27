package com.chatbuddy.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectTree(treeUri: Uri): AppResult<Unit> = runCatching {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Selected SAF folder is not available")
        if (!root.exists() || !root.canRead()) error("Selected SAF folder cannot be read")
        if (!root.canWrite()) error("Selected SAF folder is read-only")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
        check(validateTree() is AppResult.Success) {
            "Selected SAF folder is no longer available"
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Error("Unable to use selected SAF folder", it) }
    )

    fun treeUri(): Uri? = runCatching {
        preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)
    }.getOrNull()

    /**
     * Verifies the persisted tree permission and the app-owned model directory.
     * This deliberately performs no writes so it is safe to call during startup.
     */
    fun validateTree(): AppResult<Unit> = runCatching {
        val persistedUri = treeUri() ?: error("No SAF storage folder is configured")
        val root = DocumentFile.fromTreeUri(context, persistedUri)
            ?: error("Persisted SAF folder is unavailable")
        if (!root.exists() || !root.canRead()) error("Persisted SAF folder cannot be read")
        if (!root.canWrite()) error("Persisted SAF folder is read-only")

        root.findFile(MODELS_DIRECTORY)?.let { models ->
            if (!models.exists() || !models.isDirectory || !models.canRead() || !models.canWrite()) {
                error("ChatBuddy model directory is unavailable")
            }
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Error("SAF storage folder is unavailable", it) }
    )

    fun hasValidTree(): Boolean = validateTree() is AppResult.Success

    fun checkpoint(artifactId: String): Long = preferences.getLong(checkpointKey(artifactId), 0L)

    fun checkpoint(artifact: ModelArtifact): Long {
        val fingerprint = preferences.getString(checkpointFingerprintKey(artifact.id), null)
        if (fingerprint != artifactFingerprint(artifact)) return 0L
        return checkpoint(artifact.id)
    }

    fun saveCheckpoint(artifactId: String, offset: Long) {
        preferences.edit().putLong(checkpointKey(artifactId), offset.coerceAtLeast(0L)).commit()
    }

    fun saveCheckpoint(artifact: ModelArtifact, offset: Long) {
        preferences.edit()
            .putLong(checkpointKey(artifact.id), offset.coerceAtLeast(0L))
            .putString(checkpointFingerprintKey(artifact.id), artifactFingerprint(artifact))
            .commit()
    }

    fun clearCheckpoint(artifactId: String) {
        preferences.edit()
            .remove(checkpointKey(artifactId))
            .remove(checkpointFingerprintKey(artifactId))
            .commit()
    }

    fun openTemp(artifactId: String, fileName: String): Pair<DocumentFile, OutputStream>? {
        val parent = modelsDirectory() ?: return null
        val existing = parent.findFile(tempName(artifactId, fileName))
        if (existing != null && (!existing.exists() || !existing.isFile)) return null
        val file = existing ?: parent.createFile("application/octet-stream", tempName(artifactId, fileName))
            ?: return null
        // ContentResolver supports "wa" for write-append. "rwa" is not a valid
        // Android mode and fails before the HTTP request can write its first byte.
        val stream = context.contentResolver.openOutputStream(file.uri, SAF_APPEND_MODE) ?: return null
        return file to stream
    }

    fun openInput(file: DocumentFile): InputStream? = runCatching {
        context.contentResolver.openInputStream(file.uri)
    }.getOrNull()

    fun fileLength(file: DocumentFile): Long = if (file.exists()) file.length() else -1L

    fun finalFile(artifactId: String, fileName: String): DocumentFile? =
        modelsDirectory()?.findFile(fileName)

    fun finalFile(artifact: ModelArtifact): DocumentFile? =
        finalFile(artifact.id, fileName(artifact))

    fun openDescriptor(file: DocumentFile): ParcelFileDescriptor? = runCatching {
        context.contentResolver.openFileDescriptor(file.uri, "r")
    }.getOrNull()

    fun delete(file: DocumentFile): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun renameTemp(file: DocumentFile, fileName: String): DocumentFile? = runCatching {
        android.provider.DocumentsContract.renameDocument(context.contentResolver, file.uri, fileName)
            ?.let { DocumentFile.fromSingleUri(context, it) }
    }.getOrNull()

    fun fileName(artifact: ModelArtifact): String =
        safeFileName(Uri.parse(artifact.url).lastPathSegment, artifact.id)

    private fun modelsDirectory(): DocumentFile? {
        val rootUri = treeUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        if (!root.exists() || !root.canWrite()) return null
        val existing = root.findFile(MODELS_DIRECTORY)
        if (existing != null) {
            return existing.takeIf {
                it.exists() && it.isDirectory && it.canRead() && it.canWrite()
            }
        }
        return root.createDirectory(MODELS_DIRECTORY)?.takeIf {
            it.exists() && it.isDirectory && it.canRead() && it.canWrite()
        }
    }

    private fun tempName(artifactId: String, fileName: String): String =
        "${safeFileName(artifactId, "artifact")}.${safeFileName(fileName, "model")}.tmp"

    private fun checkpointKey(artifactId: String): String = "checkpoint_$artifactId"

    private fun checkpointFingerprintKey(artifactId: String): String = "checkpoint_fingerprint_$artifactId"

    private fun artifactFingerprint(artifact: ModelArtifact): String =
        "${artifact.revision}:${artifact.sha256.lowercase(Locale.US)}:${artifact.sizeBytes}"

    private fun safeFileName(value: String?, fallback: String): String {
        val decoded = value?.let { runCatching { Uri.decode(it) }.getOrDefault(it) }
        val candidate = decoded
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val sanitized = candidate.replace(INVALID_FILE_NAME_CHARS, "_")
            .take(MAX_FILE_NAME_LENGTH)
            .trim('.', ' ')
        return sanitized.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: fallback.replace(INVALID_FILE_NAME_CHARS, "_").take(MAX_FILE_NAME_LENGTH)
    }

    companion object {
        private const val PREFERENCES = "chatbuddy_saf"
        private const val KEY_TREE_URI = "tree_uri"
        private const val MODELS_DIRECTORY = "ChatBuddyModels"
        private const val SAF_APPEND_MODE = "wa"
        private const val MAX_FILE_NAME_LENGTH = 180
        private val INVALID_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}

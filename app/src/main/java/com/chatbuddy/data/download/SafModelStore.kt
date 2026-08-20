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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectTree(treeUri: Uri): AppResult<Unit> = runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Selected SAF folder is not available")
        if (!root.canWrite()) error("Selected SAF folder is read-only")
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Error("Unable to use selected SAF folder", it) }
    )

    fun treeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun checkpoint(artifactId: String): Long = preferences.getLong(checkpointKey(artifactId), 0L)

    fun saveCheckpoint(artifactId: String, offset: Long) {
        preferences.edit().putLong(checkpointKey(artifactId), offset).apply()
    }

    fun clearCheckpoint(artifactId: String) {
        preferences.edit().remove(checkpointKey(artifactId)).apply()
    }

    fun openTemp(artifactId: String, fileName: String): Pair<DocumentFile, OutputStream>? {
        val parent = modelsDirectory() ?: return null
        val existing = parent.findFile(tempName(artifactId, fileName))
        val file = existing ?: parent.createFile("application/octet-stream", tempName(artifactId, fileName))
            ?: return null
        // ContentResolver supports "wa" for write-append. "rwa" is not a valid
        // Android mode and fails before the HTTP request can write its first byte.
        val stream = context.contentResolver.openOutputStream(file.uri, SAF_APPEND_MODE) ?: return null
        return file to stream
    }

    fun openInput(file: DocumentFile): InputStream? = context.contentResolver.openInputStream(file.uri)

    fun fileLength(file: DocumentFile): Long = file.length()

    fun finalFile(artifactId: String, fileName: String): DocumentFile? =
        modelsDirectory()?.findFile(fileName)

    fun finalFile(artifact: ModelArtifact): DocumentFile? =
        finalFile(artifact.id, Uri.parse(artifact.url).lastPathSegment ?: artifact.id)

    fun openDescriptor(file: DocumentFile): ParcelFileDescriptor? =
        context.contentResolver.openFileDescriptor(file.uri, "r")

    fun delete(file: DocumentFile): Boolean = file.delete()

    fun renameTemp(file: DocumentFile, fileName: String): DocumentFile? =
        android.provider.DocumentsContract.renameDocument(context.contentResolver, file.uri, fileName)
            ?.let { DocumentFile.fromSingleUri(context, it) }

    private fun modelsDirectory(): DocumentFile? {
        val rootUri = treeUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        return root.findFile(MODELS_DIRECTORY)
            ?: root.createDirectory(MODELS_DIRECTORY)
    }

    private fun tempName(artifactId: String, fileName: String): String = "$artifactId.$fileName.tmp"

    private fun checkpointKey(artifactId: String): String = "checkpoint_$artifactId"

    companion object {
        private const val PREFERENCES = "chatbuddy_saf"
        private const val KEY_TREE_URI = "tree_uri"
        private const val MODELS_DIRECTORY = "ChatBuddyModels"
        private const val SAF_APPEND_MODE = "wa"
    }
}

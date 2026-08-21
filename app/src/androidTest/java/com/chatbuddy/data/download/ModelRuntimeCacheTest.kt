package com.chatbuddy.data.download

import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelStorageKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ModelRuntimeCacheTest {
    @Test
    fun validSafFileIsMaterializedOnceThenReusedFromCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ByteArray(256) { index -> index.toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val source = File.createTempFile("chatbuddy-cache-source", ".gguf", context.cacheDir)
        val artifact = ModelArtifact(
            id = "cache-test",
            displayName = "Cache test fixture",
            url = "cache-test.gguf",
            revision = "test-revision",
            sizeBytes = bytes.size.toLong(),
            sha256 = expectedSha,
            license = "test-only",
            abi = setOf("arm64-v8a"),
            storageKind = ModelStorageKind.SAF_PERSISTENT
        )
        source.writeBytes(bytes)

        try {
            val cache = ModelRuntimeCache(context, SafModelStore(context))
            val document = DocumentFile.fromFile(source)
            val first = cache.prepare(artifact, document).requireFile()
            assertFalse(first.hit)
            assertTrue(first.file.isFile)

            val second = cache.prepare(artifact, document).requireFile()
            assertTrue(second.hit)
            assertNotNull(second.file)
        } finally {
            source.delete()
            File(context.cacheDir, "chatbuddy-model-cache/$expectedSha.gguf").delete()
            File(context.cacheDir, "chatbuddy-model-cache/$expectedSha.meta").delete()
            File(context.cacheDir, "chatbuddy-model-cache/$expectedSha.gguf.tmp").delete()
            File(context.cacheDir, "chatbuddy-model-cache/$expectedSha.meta.tmp").delete()
        }
    }

    private fun AppResult<CachedModelFile?>.requireFile(): CachedModelFile {
        check(this is AppResult.Success)
        return data ?: error("Runtime cache did not produce a file")
    }
}

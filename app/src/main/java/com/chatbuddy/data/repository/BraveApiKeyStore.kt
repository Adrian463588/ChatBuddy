package com.chatbuddy.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.chatbuddy.domain.model.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BraveApiKeyStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): String? = runCatching {
        val encodedValue = preferences.getString(KEY_VALUE, null) ?: return@runCatching null
        val encodedIv = preferences.getString(KEY_IV, null) ?: return@runCatching null
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP))
        )
        cipher.doFinal(Base64.decode(encodedValue, Base64.NO_WRAP))
            .toString(StandardCharsets.UTF_8)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    fun save(apiKey: String): AppResult<Unit> {
        val normalized = apiKey.trim()
        if (normalized.isBlank() || normalized.length > MAX_KEY_LENGTH) {
            return AppResult.Error("Enter a valid Brave Search API key")
        }
        return runCatching {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val editor = preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_VALUE, Base64.encodeToString(cipher.doFinal(normalized.toByteArray()), Base64.NO_WRAP))
            check(editor.commit()) { "Unable to persist encrypted search key" }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error("Unable to protect the search API key", it) }
        )
    }

    fun clear(): AppResult<Unit> = runCatching {
        check(preferences.edit().remove(KEY_VALUE).remove(KEY_IV).commit()) {
            "Unable to clear encrypted search key"
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Error("Unable to clear the search API key", it) }
    )

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "chatbuddy.brave.search"
        private const val PREFERENCES = "chatbuddy_web_secure"
        private const val KEY_VALUE = "brave_api_key"
        private const val KEY_IV = "brave_api_key_iv"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val MAX_KEY_LENGTH = 512
    }
}

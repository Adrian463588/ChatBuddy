package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebProviderSettings
import com.chatbuddy.domain.repository.WebProviderSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebProviderSettingsRepositoryImpl @Inject constructor(
    private val keyStore: BraveApiKeyStore
) : WebProviderSettingsRepository {
    private val _settings = MutableStateFlow(
        WebProviderSettings(braveApiKeyConfigured = keyStore.read() != null)
    )
    override val settings: StateFlow<WebProviderSettings> = _settings.asStateFlow()

    override suspend fun saveBraveApiKey(apiKey: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            keyStore.save(apiKey).also { result ->
                if (result is AppResult.Success) {
                    _settings.value = WebProviderSettings(braveApiKeyConfigured = true)
                }
            }
        }

    override suspend fun clearBraveApiKey(): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            keyStore.clear().also { result ->
                if (result is AppResult.Success) {
                    _settings.value = WebProviderSettings(braveApiKeyConfigured = false)
                }
            }
        }
}

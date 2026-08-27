package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.WebEvidence
import com.chatbuddy.domain.model.WebProviderAvailability
import com.chatbuddy.domain.model.WebProviderId
import com.chatbuddy.domain.model.WebProviderStatus
import com.chatbuddy.domain.model.WebSearchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BraveWebSearchRepository @Inject constructor(
    private val client: OkHttpClient,
    private val apiKeyStore: BraveApiKeyStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val webClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun status(): WebProviderStatus = if (apiKeyStore.read() == null) {
        WebProviderStatus(
            provider = WebProviderId.BRAVE,
            availability = WebProviderAvailability.NOT_CONFIGURED,
            detail = "Optional provider; add a key in Settings"
        )
    } else {
        WebProviderStatus(
            provider = WebProviderId.BRAVE,
            availability = WebProviderAvailability.AVAILABLE,
            detail = "Official Brave Search API"
        )
    }

    suspend fun search(request: WebSearchRequest): AppResult<List<WebEvidence>> =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyStore.read()
                ?: return@withContext AppResult.Success(emptyList())
            val query = request.query.trim()
            if (query.length !in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH) {
                return@withContext AppResult.Error("Web search query must be between 2 and 400 characters")
            }
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("count", request.limit.coerceIn(1, MAX_RESULTS).toString())
                .build()
            val httpRequest = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            try {
                webClient.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Error(
                            "Brave Search failed with HTTP ${response.code}"
                        )
                    }
                    if (!response.header("Content-Type").orEmpty()
                            .lowercase()
                            .startsWith("application/json")
                    ) {
                        return@withContext AppResult.Error("Brave Search returned an unexpected content type")
                    }
                    val payload = json.decodeFromString<BraveResponse>(
                        response.readBoundedBody(MAX_RESPONSE_BYTES)
                    )
                    val retrievedAt = System.currentTimeMillis()
                    AppResult.Success(
                        payload.web?.results.orEmpty().mapNotNull { result ->
                            val resultUrl = result.url.trim()
                            val description = result.description
                                .replace(HTML_TAG, " ")
                                .replace(WHITESPACE, " ")
                                .trim()
                            if (result.title.isBlank() || description.isBlank() ||
                                !resultUrl.isSafeExternalHttps()
                            ) {
                                null
                            } else {
                                WebEvidence(
                                    title = result.title.trim().take(TITLE_LENGTH),
                                    url = resultUrl,
                                    excerpt = description.take(EXCERPT_LENGTH),
                                    content = description.take(CONTENT_LENGTH),
                                    provider = PROVIDER,
                                    sourceId = "$SOURCE_PREFIX${stableId(resultUrl)}",
                                    retrievedAtEpochMs = retrievedAt
                                )
                            }
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Brave Search could not be completed", error)
            }
        }

    private fun String.isSafeExternalHttps(): Boolean {
        val url = toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.host.isNotBlank() &&
            url.username.isEmpty() &&
            url.password.isEmpty()
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @Serializable
    private data class BraveResponse(val web: BraveWeb? = null)

    @Serializable
    private data class BraveWeb(val results: List<BraveResult> = emptyList())

    @Serializable
    private data class BraveResult(
        val title: String = "",
        val url: String = "",
        val description: String = ""
    )

    companion object {
        private const val BASE_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val PROVIDER = "Brave Search"
        private const val SOURCE_PREFIX = "brave:"
        private const val USER_AGENT = "ChatBuddy/0.1 (https://github.com/Adrian463588/ChatBuddy)"
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_QUERY_LENGTH = 400
        private const val MAX_RESULTS = 5
        private const val MAX_RESPONSE_BYTES = 512L * 1024L
        private const val TITLE_LENGTH = 240
        private const val EXCERPT_LENGTH = 320
        private const val CONTENT_LENGTH = 1200
        private val HTML_TAG = Regex("<[^>]*>")
        private val WHITESPACE = Regex("\\s+")
    }
}
